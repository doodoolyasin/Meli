//go:build e2e

// Package e2e drives the real meli-server and meli-client binaries over a
// loopback DNS path. It is a true end-to-end test: the binaries are executed as
// separate processes, the tunnel carries real TCP payloads from a SOCKS5 client
// to independent echo destinations, and every assertion is made on bytes that
// travelled through the DNS tunnel framing.
//
// Run with:
//
//	go test -tags=e2e ./test/e2e -v -timeout 20m
//
// The suite is intentionally excluded from `go test ./...` because it compiles
// two binaries and needs a handful of loopback ports.
package e2e

import (
	"bytes"
	"crypto/rand"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

const testKey = "0123456789abcdef0123456789abcdef"

var (
	buildOnce sync.Once
	buildDir  string
	buildErr  error
)

func TestMain(m *testing.M) {
	code := m.Run()
	if buildDir != "" {
		_ = os.RemoveAll(buildDir)
	}
	os.Exit(code)
}

// binaries builds meli-server and meli-client once per test run.
func binaries(t *testing.T) (serverBin, clientBin string) {
	t.Helper()
	buildOnce.Do(func() {
		dir, err := os.MkdirTemp("", "meli-e2e-bin-")
		if err != nil {
			buildErr = err
			return
		}
		buildDir = dir
		root, err := repoRoot()
		if err != nil {
			buildErr = err
			return
		}
		for _, target := range []struct{ out, pkg string }{
			{"meli-server", "./cmd/server"},
			{"meli-client", "./cmd/client"},
		} {
			cmd := exec.Command("go", "build", "-o", filepath.Join(dir, target.out), target.pkg)
			cmd.Dir = root
			cmd.Env = append(os.Environ(), "GOFLAGS=-p=1")
			if out, err := cmd.CombinedOutput(); err != nil {
				buildErr = fmt.Errorf("build %s: %v\n%s", target.pkg, err, out)
				return
			}
		}
	})
	if buildErr != nil {
		t.Fatalf("building binaries: %v", buildErr)
	}
	return filepath.Join(buildDir, "meli-server"), filepath.Join(buildDir, "meli-client")
}

func repoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("go.mod not found above %s", dir)
		}
		dir = parent
	}
}

func freePort(t *testing.T, network string) int {
	t.Helper()
	switch network {
	case "udp":
		conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatalf("reserve udp port: %v", err)
		}
		defer conn.Close()
		return conn.LocalAddr().(*net.UDPAddr).Port
	default:
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatalf("reserve tcp port: %v", err)
		}
		defer ln.Close()
		return ln.Addr().(*net.TCPAddr).Port
	}
}

// echoServer is a real TCP destination that prefixes every echoed chunk with a
// per-server tag so a test can prove which destination the tunnel reached.
type echoServer struct {
	ln   net.Listener
	tag  string
	port int
	wg   sync.WaitGroup
}

func startEcho(t *testing.T, tag string) *echoServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	e := &echoServer{ln: ln, tag: tag, port: ln.Addr().(*net.TCPAddr).Port}
	e.wg.Add(1)
	go func() {
		defer e.wg.Done()
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			e.wg.Add(1)
			go func(c net.Conn) {
				defer e.wg.Done()
				defer c.Close()
				buf := make([]byte, 32*1024)
				for {
					n, err := c.Read(buf)
					if n > 0 {
						if _, werr := c.Write(append([]byte(e.tag), buf[:n]...)); werr != nil {
							return
						}
					}
					if err != nil {
						return
					}
				}
			}(conn)
		}
	}()
	t.Cleanup(func() {
		_ = ln.Close()
	})
	return e
}

type proc struct {
	cmd *exec.Cmd
	log string
}

func startProc(t *testing.T, bin, cfgPath, cwd, name string) *proc {
	t.Helper()
	logPath := filepath.Join(cwd, name+".log")
	f, err := os.Create(logPath)
	if err != nil {
		t.Fatalf("create %s log: %v", name, err)
	}
	cmd := exec.Command(bin, "-config", cfgPath)
	cmd.Dir = cwd
	cmd.Stdout = f
	cmd.Stderr = f
	if err := cmd.Start(); err != nil {
		f.Close()
		t.Fatalf("start %s: %v", name, err)
	}
	p := &proc{cmd: cmd, log: logPath}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
		f.Close()
		if t.Failed() {
			if data, err := os.ReadFile(logPath); err == nil {
				tail := data
				if len(tail) > 6000 {
					tail = tail[len(tail)-6000:]
				}
				t.Logf("---- %s log tail ----\n%s", name, tail)
			}
		}
	})
	return p
}

func (p *proc) alive() bool {
	if p == nil || p.cmd == nil || p.cmd.Process == nil {
		return false
	}
	return p.cmd.Process.Signal(syscall.Signal(0)) == nil
}

type tunnel struct {
	proxyPort int
	server    *proc
	client    *proc
}

// startTunnel writes configs, starts both binaries and waits until the client's
// SOCKS5 listener accepts connections.
func startTunnel(t *testing.T, allowPrivate bool, forwardPort int) *tunnel {
	t.Helper()
	serverBin, clientBin := binaries(t)
	dir := t.TempDir()

	udpPort := freePort(t, "udp")
	proxyPort := freePort(t, "tcp")

	if err := os.WriteFile(filepath.Join(dir, "encrypt_key.txt"), []byte(testKey+"\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}

	forwardIP := ""
	if forwardPort > 0 {
		forwardIP = "127.0.0.1"
	}
	serverCfg := fmt.Sprintf(`
PROTOCOL_TYPE = "TCP"
UDP_HOST = "127.0.0.1"
UDP_PORT = %d
DOMAIN = ["v.e2e.test"]
FORWARD_IP = %q
FORWARD_PORT = %d
ALLOW_PRIVATE_TARGETS = %t
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = %q
UDP_READERS = 1
DNS_REQUEST_WORKERS = 2
DEFERRED_SESSION_WORKERS = 2
LOG_LEVEL = "INFO"
`, udpPort, map[bool]string{true: "", false: "127.0.0.1"}[forwardPort > 0], forwardPort, allowPrivate, testKey)
	cfgPath := filepath.Join(dir, "server.toml")
	if err := os.WriteFile(cfgPath, []byte(serverCfg), 0o600); err != nil {
		t.Fatalf("write server config: %v", err)
	}

	clientCfg := fmt.Sprintf(`
PROTOCOL_TYPE = "TCP"
DOMAINS = ["v.e2e.test"]
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = %d
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = %q
AUTO_REMOVE_LOW_MTU_SERVERS = false
MTU_TEST_RETRIES = 1
MTU_TEST_TIMEOUT = 2.0
RESOLVER_BALANCING_STRATEGY = 1
RX_TX_WORKERS = 1
`, proxyPort, testKey)
	clientCfgPath := filepath.Join(dir, "client.toml")
	if err := os.WriteFile(clientCfgPath, []byte(clientCfg), 0o600); err != nil {
		t.Fatalf("write client config: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "resolvers.txt"), []byte(fmt.Sprintf("127.0.0.1:%d\n", udpPort)), 0o600); err != nil {
		t.Fatalf("write resolvers: %v", err)
	}

	server := startProc(t, serverBin, cfgPath, dir, "server")
	time.Sleep(300 * time.Millisecond)
	client := startProc(t, clientBin, clientCfgPath, dir, "client")

	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", proxyPort), 500*time.Millisecond)
		if err == nil {
			conn.Close()
			return &tunnel{proxyPort: proxyPort, server: server, client: client}
		}
		if !server.alive() || !client.alive() {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("client SOCKS5 listener on 127.0.0.1:%d never became ready", proxyPort)
	return nil
}

// socks5Connect performs a full RFC 1928 no-auth CONNECT and returns the
// established stream plus the reply code (0x00 means success).
func socks5Connect(t *testing.T, proxyPort int, host string, port int) (net.Conn, byte) {
	t.Helper()
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", proxyPort), 5*time.Second)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		conn.Close()
		t.Fatalf("socks greeting: %v", err)
	}
	var greeting [2]byte
	if _, err := io.ReadFull(conn, greeting[:]); err != nil {
		conn.Close()
		t.Fatalf("socks greeting reply: %v", err)
	}
	if greeting[0] != 0x05 || greeting[1] != 0x00 {
		conn.Close()
		t.Fatalf("unexpected socks greeting reply %v", greeting)
	}

	var req []byte
	if ip := net.ParseIP(host); ip != nil && ip.To4() != nil {
		req = append([]byte{0x05, 0x01, 0x00, 0x01}, ip.To4()...)
	} else {
		if len(host) > 255 {
			conn.Close()
			t.Fatalf("host too long: %s", host)
		}
		req = append([]byte{0x05, 0x01, 0x00, 0x03, byte(len(host))}, []byte(host)...)
	}
	req = append(req, byte(port>>8), byte(port))
	if _, err := conn.Write(req); err != nil {
		conn.Close()
		t.Fatalf("socks connect: %v", err)
	}
	var head [4]byte
	if _, err := io.ReadFull(conn, head[:]); err != nil {
		conn.Close()
		t.Fatalf("socks connect reply: %v", err)
	}
	if head[0] != 0x05 {
		conn.Close()
		t.Fatalf("bad socks version in reply: %v", head)
	}
	switch head[3] {
	case 0x01:
		_, _ = io.ReadFull(conn, make([]byte, 4+2))
	case 0x04:
		_, _ = io.ReadFull(conn, make([]byte, 16+2))
	case 0x03:
		var l [1]byte
		if _, err := io.ReadFull(conn, l[:]); err != nil {
			conn.Close()
			t.Fatalf("socks reply addr len: %v", err)
		}
		_, _ = io.ReadFull(conn, make([]byte, int(l[0])+2))
	}
	return conn, head[1]
}

func mustConnect(t *testing.T, proxyPort int, host string, port int) net.Conn {
	t.Helper()
	conn, rep := socks5Connect(t, proxyPort, host, port)
	if rep != 0x00 {
		conn.Close()
		t.Fatalf("SOCKS5 CONNECT %s:%d refused with reply 0x%02x", host, port, rep)
	}
	return conn
}

func randomPayload(t *testing.T, n int) []byte {
	t.Helper()
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("rand: %v", err)
	}
	return buf
}

// echoRoundTrip writes payload and reads the echoed bytes, requiring the exact
// tag prefix and byte-exact content.
func echoRoundTrip(t *testing.T, conn net.Conn, tag string, payload []byte, timeout time.Duration) {
	t.Helper()
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		t.Fatalf("set deadline: %v", err)
	}
	writeDone := make(chan error, 1)
	go func() {
		_, err := conn.Write(payload)
		writeDone <- err
	}()

	want := append([]byte(tag), payload...)
	got := make([]byte, len(want))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read echo (%d bytes expected): %v", len(want), err)
	}
	if err := <-writeDone; err != nil {
		t.Fatalf("write payload: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("echo mismatch: got %d bytes, want %d bytes (tag %q)", len(got), len(want), tag)
	}
}

func TestSOCKSPerTargetForwarding(t *testing.T) {
	echoA := startEcho(t, "A:")
	echoB := startEcho(t, "B:")
	tn := startTunnel(t, true, 0)

	t.Run("single_stream", func(t *testing.T) {
		conn := mustConnect(t, tn.proxyPort, "127.0.0.1", echoA.port)
		defer conn.Close()
		echoRoundTrip(t, conn, "A:", []byte("hello-through-the-dns-tunnel"), 45*time.Second)
	})

	t.Run("two_distinct_targets", func(t *testing.T) {
		connA := mustConnect(t, tn.proxyPort, "127.0.0.1", echoA.port)
		defer connA.Close()
		connB := mustConnect(t, tn.proxyPort, "127.0.0.1", echoB.port)
		defer connB.Close()

		echoRoundTrip(t, connA, "A:", []byte("destination-a"), 45*time.Second)
		echoRoundTrip(t, connB, "B:", []byte("destination-b"), 45*time.Second)
		// Interleave to prove per-stream state isolation.
		echoRoundTrip(t, connA, "A:", []byte("a-again"), 45*time.Second)
		echoRoundTrip(t, connB, "B:", []byte("b-again"), 45*time.Second)
	})

	t.Run("large_payload", func(t *testing.T) {
		payload := randomPayload(t, 32*1024)
		conn := mustConnect(t, tn.proxyPort, "127.0.0.1", echoA.port)
		defer conn.Close()
		echoRoundTrip(t, conn, "A:", payload, 180*time.Second)
	})

	t.Run("concurrent_streams", func(t *testing.T) {
		const streams = 6
		var wg sync.WaitGroup
		errs := make(chan error, streams)
		for i := 0; i < streams; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				target := echoA
				tag := "A:"
				if i%2 == 1 {
					target, tag = echoB, "B:"
				}
				conn, rep := socks5Connect(t, tn.proxyPort, "127.0.0.1", target.port)
				if rep != 0x00 {
					errs <- fmt.Errorf("stream %d refused: 0x%02x", i, rep)
					return
				}
				defer conn.Close()
				payload := bytes.Repeat([]byte{byte('a' + i)}, 2048)
				_ = conn.SetDeadline(time.Now().Add(180 * time.Second))
				if _, err := conn.Write(payload); err != nil {
					errs <- fmt.Errorf("stream %d write: %w", i, err)
					return
				}
				want := append([]byte(tag), payload...)
				got := make([]byte, len(want))
				if _, err := io.ReadFull(conn, got); err != nil {
					errs <- fmt.Errorf("stream %d read: %w", i, err)
					return
				}
				if !bytes.Equal(got, want) {
					errs <- fmt.Errorf("stream %d corrupted", i)
				}
			}(i)
		}
		wg.Wait()
		close(errs)
		for err := range errs {
			t.Error(err)
		}
	})

	t.Run("half_close", func(t *testing.T) {
		conn := mustConnect(t, tn.proxyPort, "127.0.0.1", echoA.port)
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(45 * time.Second))
		payload := []byte("half-close-payload")
		if _, err := conn.Write(payload); err != nil {
			t.Fatalf("write: %v", err)
		}
		tcp, ok := conn.(*net.TCPConn)
		if !ok {
			t.Fatalf("unexpected conn type %T", conn)
		}
		if err := tcp.CloseWrite(); err != nil {
			t.Fatalf("close write: %v", err)
		}
		got, err := io.ReadAll(conn)
		if err != nil {
			t.Fatalf("read after half-close: %v", err)
		}
		want := append([]byte("A:"), payload...)
		if !bytes.Equal(got, want) {
			t.Fatalf("half-close echo mismatch: got %q want %q", got, want)
		}
	})
}

func TestSOCKSTargetPolicyDefaultDeny(t *testing.T) {
	echo := startEcho(t, "D:")
	tn := startTunnel(t, false, 0)

	conn, rep := socks5Connect(t, tn.proxyPort, "127.0.0.1", echo.port)
	defer conn.Close()
	if rep == 0x00 {
		t.Fatalf("default server policy established a loopback tunnel (reply 0x00); SSRF protection is not enforced")
	}
	if rep != 0x02 {
		t.Logf("loopback target refused with reply 0x%02x (expected 0x02 ruleset denied)", rep)
	}
}

func TestFixedForwardMode(t *testing.T) {
	echo := startEcho(t, "F:")
	tn := startTunnel(t, false, echo.port)

	conn := mustConnect(t, tn.proxyPort, "example.com", 80)
	defer conn.Close()
	echoRoundTrip(t, conn, "F:", []byte("fixed-forward"), 45*time.Second)
}

func TestInvalidConfigFailsFast(t *testing.T) {
	serverBin, clientBin := binaries(t)
	dir := t.TempDir()
	_ = serverBin

	bad := filepath.Join(dir, "bad.toml")
	if err := os.WriteFile(bad, []byte("PROTOCOL_TYPE = \"TCP\"\nUDP_PORT = -1\n"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	cmd := exec.Command(clientBin, "-config", bad)
	out, err := cmd.CombinedOutput()
	if err == nil {
		t.Fatalf("client accepted an invalid config; output:\n%s", out)
	}
	if !strings.Contains(strings.ToLower(string(out)), "config") && len(out) == 0 {
		t.Logf("client exited with %v and no diagnostic output", err)
	}
}
