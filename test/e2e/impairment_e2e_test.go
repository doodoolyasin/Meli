//go:build e2e

package e2e

import (
	"fmt"
	"io"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// --- impairment proxy --------------------------------------------------------

// impairProxy sits between the client and the server and can drop, duplicate
// and delay datagrams in both directions. It is a real UDP hop, so the tunnel
// sees genuine loss and reordering rather than an injected error at the API
// level.
type impairProxy struct {
	conn     *net.UDPConn
	upstream *net.UDPAddr

	lossPct  atomic.Int64
	dupPct   atomic.Int64
	delayMS  atomic.Int64
	jitterMS atomic.Int64

	client atomic.Pointer[net.UDPAddr]

	forwarded  atomic.Int64
	dropped    atomic.Int64
	duplicated atomic.Int64
	delayed    atomic.Int64

	wg sync.WaitGroup
}

func startImpairProxy(t *testing.T, upstream string) *impairProxy {
	t.Helper()
	up, err := net.ResolveUDPAddr("udp", upstream)
	if err != nil {
		t.Fatalf("resolve upstream %s: %v", upstream, err)
	}
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("impair proxy listen: %v", err)
	}
	p := &impairProxy{conn: conn, upstream: up}
	t.Cleanup(func() {
		_ = conn.Close()
		p.wg.Wait()
	})

	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		buf := make([]byte, 65535)
		for {
			n, src, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			payload := append([]byte(nil), buf[:n]...)

			if src.Port == up.Port && src.IP.Equal(up.IP) {
				// Server -> client.
				dst := p.client.Load()
				if dst == nil {
					continue
				}
				p.relay(payload, dst, "down")
				continue
			}

			// Client -> server. Remember where to send answers back.
			p.client.Store(src)
			p.relay(payload, up, "up")
		}
	}()

	return p
}

func (p *impairProxy) addr() string { return p.conn.LocalAddr().String() }

func (p *impairProxy) set(lossPct, dupPct int, delayMS, jitterMS int) {
	p.lossPct.Store(int64(lossPct))
	p.dupPct.Store(int64(dupPct))
	p.delayMS.Store(int64(delayMS))
	p.jitterMS.Store(int64(jitterMS))
}

func (p *impairProxy) stats() (forwarded, dropped, duplicated, delayed int64) {
	return p.forwarded.Load(), p.dropped.Load(), p.duplicated.Load(), p.delayed.Load()
}

func (p *impairProxy) relay(payload []byte, dst *net.UDPAddr, _ string) {
	if p.lossPct.Load() > 0 && int64(rand.Intn(100)) < p.lossPct.Load() {
		p.dropped.Add(1)
		return
	}

	delay := p.delayMS.Load()
	if jitter := p.jitterMS.Load(); jitter > 0 && delay >= 0 {
		delay += rand.Int63n(jitter + 1)
	}

	send := func() {
		_, _ = p.conn.WriteToUDP(payload, dst)
		p.forwarded.Add(1)
	}

	if delay > 0 {
		p.delayed.Add(1)
		time.AfterFunc(time.Duration(delay)*time.Millisecond, send)
	} else {
		send()
	}

	if p.dupPct.Load() > 0 && int64(rand.Intn(100)) < p.dupPct.Load() {
		p.duplicated.Add(1)
		extra := append([]byte(nil), payload...)
		time.AfterFunc(time.Duration(delay+3)*time.Millisecond, func() {
			_, _ = p.conn.WriteToUDP(extra, dst)
		})
	}
}

// --- helpers -----------------------------------------------------------------

// startPlainEcho is a byte-exact echo destination (no tag prefix) so the test
// can compare payloads byte for byte.
func startPlainEcho(t *testing.T) (addr string, conns *atomic.Int64) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	var opened atomic.Int64
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			opened.Add(1)
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()
	return ln.Addr().String(), &opened
}

// startTunnelViaResolver starts server+client like startTunnel, but points the
// client at an arbitrary resolver address (the impairment proxy).
func startTunnelViaResolver(t *testing.T, resolverAddr string, allowPrivate bool) (proxyPort int, dir string, client *proc) {
	t.Helper()
	serverBin, clientBin := binaries(t)
	dir = t.TempDir()
	udpPort := freePort(t, "udp")
	proxyPort = freePort(t, "tcp")

	if err := os.WriteFile(filepath.Join(dir, "encrypt_key.txt"), []byte(testKey+"\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}

	serverCfg := fmt.Sprintf(`
PROTOCOL_TYPE = "SOCKS5"
UDP_HOST = "127.0.0.1"
UDP_PORT = %d
DOMAIN = ["v.e2e.test"]
ALLOW_PRIVATE_TARGETS = %t
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = %q
UDP_READERS = 1
DNS_REQUEST_WORKERS = 2
DEFERRED_SESSION_WORKERS = 2
LOG_LEVEL = "INFO"
`, udpPort, allowPrivate, testKey)
	serverCfgPath := filepath.Join(dir, "server.toml")
	if err := os.WriteFile(serverCfgPath, []byte(serverCfg), 0o600); err != nil {
		t.Fatalf("write server config: %v", err)
	}

	clientCfg := fmt.Sprintf(`
PROTOCOL_TYPE = "SOCKS5"
DOMAINS = ["v.e2e.test"]
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = %d
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = %q
AUTO_REMOVE_LOW_MTU_SERVERS = false
MTU_TEST_RETRIES = 3
MTU_TEST_TIMEOUT = 2.0
RESOLVER_BALANCING_STRATEGY = 1
RX_TX_WORKERS = 1
LOG_LEVEL = "INFO"
`, proxyPort, testKey)
	clientCfgPath := filepath.Join(dir, "client.toml")
	if err := os.WriteFile(clientCfgPath, []byte(clientCfg), 0o600); err != nil {
		t.Fatalf("write client config: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "client_resolvers.txt"), []byte(resolverAddr+"\n"), 0o600); err != nil {
		t.Fatalf("write resolvers: %v", err)
	}

	_ = startProc(t, serverBin, serverCfgPath, dir, "impair-server")
	time.Sleep(300 * time.Millisecond)
	client = startProc(t, clientBin, clientCfgPath, dir, "impair-client")

	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", "127.0.0.1:"+itoa(proxyPort), 500*time.Millisecond)
		if err == nil {
			conn.Close()
			return proxyPort, dir, client
		}
		if !client.alive() {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("client SOCKS5 listener on 127.0.0.1:%d never became ready", proxyPort)
	return 0, "", nil
}

func roundTripThroughSOCKS(t *testing.T, proxyPort int, target string, payload []byte, timeout time.Duration) ([]byte, time.Duration, error) {
	t.Helper()
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		t.Fatalf("split target %s: %v", target, err)
	}
	port := 0
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil {
		t.Fatalf("parse port %s: %v", portStr, err)
	}

	started := time.Now()
	conn, code, err := trySOCKS5Connect(proxyPort, host, port, timeout)
	if err != nil {
		return nil, time.Since(started), err
	}
	if code != 0 {
		conn.Close()
		return nil, time.Since(started), fmt.Errorf("socks reply code %#x", code)
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(timeout))
	if _, err := conn.Write(payload); err != nil {
		return nil, time.Since(started), fmt.Errorf("write payload: %w", err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		return nil, time.Since(started), fmt.Errorf("read echo: %w", err)
	}
	return got, time.Since(started), nil
}

// --- tests -------------------------------------------------------------------

// TestTunnelResilienceUnderImpairment exercises the ARQ tunnel over a UDP hop
// that drops, duplicates, delays and reorders datagrams, then removes all
// connectivity for a few seconds and checks that a fresh connection recovers.
func TestTunnelResilienceUnderImpairment(t *testing.T) {
	serverUDP := freePort(t, "udp")
	target, accepted := startPlainEcho(t)

	// The proxy has to point at the port the server will bind, so start the
	// proxy first and hand its address to the client.
	px := startImpairProxy(t, "127.0.0.1:"+itoa(serverUDP))

	serverBin, _ := binaries(t)
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "encrypt_key.txt"), []byte(testKey+"\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	serverCfg := fmt.Sprintf(`
PROTOCOL_TYPE = "SOCKS5"
UDP_HOST = "127.0.0.1"
UDP_PORT = %d
DOMAIN = ["v.e2e.test"]
ALLOW_PRIVATE_TARGETS = true
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = %q
UDP_READERS = 1
DNS_REQUEST_WORKERS = 2
DEFERRED_SESSION_WORKERS = 2
LOG_LEVEL = "INFO"
`, serverUDP, testKey)
	serverCfgPath := filepath.Join(dir, "server.toml")
	if err := os.WriteFile(serverCfgPath, []byte(serverCfg), 0o600); err != nil {
		t.Fatalf("write server config: %v", err)
	}
	_ = startProc(t, serverBin, serverCfgPath, dir, "impair-server")

	clientBin, _ := binaries(t)
	_ = clientBin
	proxyPort, _, _ := startTunnelViaResolver(t, px.addr(), true)

	// Baseline with a clean path.
	payload := make([]byte, 2048)
	for i := range payload {
		payload[i] = byte(i % 251)
	}
	got, rtt, err := roundTripThroughSOCKS(t, proxyPort, target, payload, 30*time.Second)
	if err != nil {
		t.Fatalf("baseline round trip failed: %v", err)
	}
	if string(got) != string(payload) {
		t.Fatalf("baseline payload corrupted: %d bytes back", len(got))
	}
	t.Logf("baseline: %d bytes in %s", len(payload), rtt.Round(time.Millisecond))

	// 10% loss, 2% duplication, 20-60 ms extra delay (and therefore reordering).
	px.set(10, 2, 20, 40)
	const rounds = 6
	successes := 0
	var worst time.Duration
	for i := 0; i < rounds; i++ {
		got, rtt, err := roundTripThroughSOCKS(t, proxyPort, target, payload, 45*time.Second)
		if err != nil {
			t.Logf("round %d failed under impairment after %s: %v", i, rtt.Round(time.Millisecond), err)
			continue
		}
		if string(got) != string(payload) {
			t.Fatalf("round %d returned corrupted payload", i)
		}
		successes++
		if rtt > worst {
			worst = rtt
		}
	}
	fwd, dropped, dup, delayed := px.stats()
	t.Logf("impairment: %d/%d rounds intact, worst %s, proxy forwarded=%d dropped=%d duplicated=%d delayed=%d, echo connections=%d",
		successes, rounds, worst.Round(time.Millisecond), fwd, dropped, dup, delayed, accepted.Load())
	if dropped == 0 {
		t.Fatalf("impairment proxy dropped nothing: the loss path was not exercised")
	}
	if successes < rounds-1 {
		t.Fatalf("only %d/%d round trips survived 10%% loss", successes, rounds)
	}

	// Total outage: nothing gets through for 2.5 s, then the path is restored.
	px.set(100, 0, 0, 0)
	time.Sleep(2500 * time.Millisecond)
	px.set(0, 0, 0, 0)

	recovered := false
	var recovery time.Duration
	start := time.Now()
	for attempt := 0; attempt < 8; attempt++ {
		got, _, err := roundTripThroughSOCKS(t, proxyPort, target, payload, 30*time.Second)
		if err == nil && string(got) == string(payload) {
			recovered = true
			recovery = time.Since(start)
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if !recovered {
		t.Fatalf("tunnel did not recover within 30s of the outage ending")
	}
	t.Logf("recovered %.1fs after a 2.5s total outage", recovery.Seconds())
}
