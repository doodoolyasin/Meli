//go:build e2e

package e2e

import (
	"encoding/binary"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// --- a tiny authoritative DNS responder -------------------------------------

// startDNSResponder answers every A query with answerIP and counts the queries
// it saw, so a test can prove the query actually reached the upstream resolver
// through the tunnel instead of being answered locally.
func startDNSResponder(t *testing.T, answerIP net.IP) (addr string, queries func() int) {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("dns responder listen: %v", err)
	}
	t.Cleanup(func() { conn.Close() })

	var seen int
	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 1500)
		for {
			n, peer, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			resp, ok := buildDNSAnswer(buf[:n], answerIP)
			if !ok {
				continue
			}
			seen++
			_, _ = conn.WriteToUDP(resp, peer)
		}
	}()
	return conn.LocalAddr().String(), func() int { return seen }
}

// buildDNSAnswer turns a query into a minimal NOERROR/A response.
func buildDNSAnswer(query []byte, ip net.IP) ([]byte, bool) {
	if len(query) < 12 {
		return nil, false
	}
	// Walk the QNAME to find the end of the question section.
	off := 12
	for {
		if off >= len(query) {
			return nil, false
		}
		l := int(query[off])
		if l == 0 {
			off++
			break
		}
		if l&0xC0 != 0 || off+1+l > len(query) {
			return nil, false
		}
		off += 1 + l
	}
	if off+4 > len(query) {
		return nil, false
	}
	questionEnd := off + 4

	resp := make([]byte, questionEnd, questionEnd+16)
	copy(resp, query[:questionEnd])
	binary.BigEndian.PutUint16(resp[0:2], binary.BigEndian.Uint16(query[0:2])) // same ID
	binary.BigEndian.PutUint16(resp[2:4], 0x8180)                              // QR=1 RD=1 RA=1
	binary.BigEndian.PutUint16(resp[4:6], 1)                                   // QDCOUNT
	binary.BigEndian.PutUint16(resp[6:8], 1)                                   // ANCOUNT
	binary.BigEndian.PutUint16(resp[8:10], 0)                                  // NSCOUNT
	binary.BigEndian.PutUint16(resp[10:12], 0)                                 // ARCOUNT

	resp = append(resp,
		0xC0, 0x0C, // name pointer to the question
		0x00, 0x01, // TYPE A
		0x00, 0x01, // CLASS IN
		0x00, 0x00, 0x00, 0x3C, // TTL 60
		0x00, 0x04, // RDLENGTH
	)
	resp = append(resp, ip.To4()...)
	return resp, true
}

func buildDNSQuery(id uint16, name string, qtype uint16) []byte {
	q := make([]byte, 12, 64)
	binary.BigEndian.PutUint16(q[0:2], id)
	binary.BigEndian.PutUint16(q[2:4], 0x0100) // RD
	binary.BigEndian.PutUint16(q[4:6], 1)      // QDCOUNT
	for _, label := range strings.Split(name, ".") {
		if label == "" {
			continue
		}
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	q = append(q, 0x00)
	q = binary.BigEndian.AppendUint16(q, qtype)
	q = binary.BigEndian.AppendUint16(q, 0x0001)
	return q
}

// waitForLog polls a process log for a substring.
func waitForLog(t *testing.T, p *proc, substr string, timeout time.Duration) bool {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(p.log); err == nil && strings.Contains(string(data), substr) {
			return true
		}
		if !p.alive() {
			return false
		}
		time.Sleep(100 * time.Millisecond)
	}
	return false
}

// --- the test ---------------------------------------------------------------

// dnsTunnel is a running client/server pair wired to an in-test upstream DNS
// responder, with the client's local DNS listener enabled.
type dnsTunnel struct {
	proxyPort int
	dnsPort   int
	client    *proc
	upstream  func() int
}

func startDNSTunnel(t *testing.T, upstreamAddr string, upstreamQueries func() int) *dnsTunnel {
	t.Helper()
	serverBin, clientBin := binaries(t)

	dir := t.TempDir()
	udpPort := freePort(t, "udp")
	proxyPort := freePort(t, "tcp")
	dnsPort := freePort(t, "udp")

	if err := os.WriteFile(filepath.Join(dir, "encrypt_key.txt"), []byte(testKey+"\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}

	serverCfg := `
PROTOCOL_TYPE = "SOCKS5"
UDP_HOST = "127.0.0.1"
UDP_PORT = ` + itoa(udpPort) + `
DOMAIN = ["v.e2e.test"]
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = "` + testKey + `"
UDP_READERS = 1
DNS_REQUEST_WORKERS = 2
DEFERRED_SESSION_WORKERS = 2
DNS_UPSTREAM_SERVERS = ["` + upstreamAddr + `"]
DNS_UPSTREAM_TIMEOUT = 2.0
LOG_LEVEL = "INFO"
`
	serverCfgPath := filepath.Join(dir, "server.toml")
	if err := os.WriteFile(serverCfgPath, []byte(serverCfg), 0o600); err != nil {
		t.Fatalf("write server config: %v", err)
	}

	clientCfg := `
PROTOCOL_TYPE = "SOCKS5"
DOMAINS = ["v.e2e.test"]
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = ` + itoa(proxyPort) + `
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = "` + testKey + `"
AUTO_REMOVE_LOW_MTU_SERVERS = false
MTU_TEST_RETRIES = 1
MTU_TEST_TIMEOUT = 2.0
RESOLVER_BALANCING_STRATEGY = 1
RX_TX_WORKERS = 1
LOCAL_DNS_ENABLED = true
LOCAL_DNS_IP = "127.0.0.1"
LOCAL_DNS_PORT = ` + itoa(dnsPort) + `
LOCAL_DNS_CACHE_PERSIST_TO_FILE = false
LOCAL_DNS_CACHE_TTL_SECONDS = 60.0
LOCAL_DNS_PENDING_TIMEOUT_SECONDS = 10.0
`
	clientCfgPath := filepath.Join(dir, "client.toml")
	if err := os.WriteFile(clientCfgPath, []byte(clientCfg), 0o600); err != nil {
		t.Fatalf("write client config: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "client_resolvers.txt"),
		[]byte("127.0.0.1:"+itoa(udpPort)+"\n"), 0o600); err != nil {
		t.Fatalf("write resolvers: %v", err)
	}

	_ = startProc(t, serverBin, serverCfgPath, dir, "dns-server")
	time.Sleep(300 * time.Millisecond)
	client := startProc(t, clientBin, clientCfgPath, dir, "dns-client")

	if !waitForLog(t, client, "DNS server is listening on", 60*time.Second) {
		t.Fatalf("client never started its local DNS listener on 127.0.0.1:%d", dnsPort)
	}
	// Give the client time to finish its MTU probe and mark the session ready;
	// ProcessDNSQuery refuses to dispatch before that.
	time.Sleep(3 * time.Second)

	return &dnsTunnel{proxyPort: proxyPort, dnsPort: dnsPort, client: client, upstream: upstreamQueries}
}

// TestLocalDNSProxyAnswersColdQuery proves the DNS-proxy data path end to end:
// a stub resolver asks the client's local DNS listener for a name that is not
// cached, the query travels through the tunnel, the server forwards it to the
// configured upstream resolver, and the answer comes back to the stub on the
// same UDP socket.
func TestLocalDNSProxyAnswersColdQuery(t *testing.T) {
	upstreamAddr, upstreamQueries := startDNSResponder(t, net.IPv4(203, 0, 113, 7))
	tn := startDNSTunnel(t, upstreamAddr, upstreamQueries)

	conn, err := net.Dial("udp", "127.0.0.1:"+itoa(tn.dnsPort))
	if err != nil {
		t.Fatalf("dial local DNS listener: %v", err)
	}
	defer conn.Close()

	// A single cold query: no cache entry exists for this name yet.
	query := buildDNSQuery(0x4d45, "cold-query.e2e.test", 1)
	start := time.Now()
	if _, err := conn.Write(query); err != nil {
		t.Fatalf("send DNS query: %v", err)
	}

	_ = conn.SetReadDeadline(time.Now().Add(8 * time.Second))
	reply := make([]byte, 1500)
	n, err := conn.Read(reply)
	if err != nil {
		t.Fatalf("no DNS reply for a cold query after 8s (upstream saw %d queries): %v",
			tn.upstream(), err)
	}
	reply = reply[:n]
	t.Logf("cold DNS query answered in %s (single round trip through the tunnel)", time.Since(start).Round(time.Millisecond))
	assertDNSAnswer(t, reply, 0x4d45, net.IPv4(203, 0, 113, 7))
	if got := tn.upstream(); got != 1 {
		t.Fatalf("upstream resolver saw %d queries, want exactly 1", got)
	}

	// The answer must also be cached: a second query is served locally and must
	// not reach the upstream again.
	second := buildDNSQuery(0x4d46, "cold-query.e2e.test", 1)
	if _, err := conn.Write(second); err != nil {
		t.Fatalf("send second DNS query: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err = conn.Read(reply)
	if err != nil {
		t.Fatalf("cached DNS reply not served: %v", err)
	}
	assertDNSAnswer(t, reply[:n], 0x4d46, net.IPv4(203, 0, 113, 7))
	if got := tn.upstream(); got != 1 {
		t.Fatalf("cached query re-reached the upstream (%d queries), cache is not effective", got)
	}
}

func itoa(v int) string { return strconv.Itoa(v) }

func assertDNSAnswer(t *testing.T, reply []byte, wantID uint16, wantIP net.IP) {
	t.Helper()
	if len(reply) < 12 {
		t.Fatalf("short DNS reply: %d bytes", len(reply))
	}
	if got := binary.BigEndian.Uint16(reply[0:2]); got != wantID {
		t.Fatalf("reply transaction ID = %#x, want %#x", got, wantID)
	}
	if reply[2]&0x80 == 0 {
		t.Fatalf("reply is not marked as a response: flags %#x", binary.BigEndian.Uint16(reply[2:4]))
	}
	if ancount := binary.BigEndian.Uint16(reply[6:8]); ancount != 1 {
		t.Fatalf("reply ANCOUNT = %d, want 1 (raw: %x)", ancount, reply)
	}
	if !strings.HasSuffix(string(reply), string(wantIP.To4())) {
		t.Fatalf("reply does not carry the expected A record %s: %x", wantIP, reply)
	}
}

// TestSOCKSUDPAssociateCarriesColdDNSQuery drives the same cold query through
// the SOCKS5 UDP ASSOCIATE relay instead of the local DNS listener. Before the
// waiter registry existed the relay closed the association on the first miss,
// so a stub had to reconnect and retry before it could ever see an answer.
func TestSOCKSUDPAssociateCarriesColdDNSQuery(t *testing.T) {
	upstreamAddr, upstreamQueries := startDNSResponder(t, net.IPv4(203, 0, 113, 9))
	tn := startDNSTunnel(t, upstreamAddr, upstreamQueries)

	ctrl, err := net.Dial("tcp", "127.0.0.1:"+itoa(tn.proxyPort))
	if err != nil {
		t.Fatalf("dial SOCKS proxy: %v", err)
	}
	defer ctrl.Close()

	if _, err := ctrl.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("greeting: %v", err)
	}
	var greeting [2]byte
	if _, err := io.ReadFull(ctrl, greeting[:]); err != nil {
		t.Fatalf("greeting reply: %v", err)
	}
	if greeting[0] != 0x05 || greeting[1] != 0x00 {
		t.Fatalf("unexpected greeting reply: %v", greeting)
	}

	// UDP ASSOCIATE for 0.0.0.0:0 — the client picks the relay endpoint.
	if _, err := ctrl.Write([]byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatalf("udp associate request: %v", err)
	}
	var head [4]byte
	if _, err := io.ReadFull(ctrl, head[:]); err != nil {
		t.Fatalf("udp associate reply: %v", err)
	}
	if head[0] != 0x05 || head[1] != 0x00 {
		t.Fatalf("udp associate failed: rep=%#x atyp=%#x", head[1], head[3])
	}
	var relayHost string
	switch head[3] {
	case 0x01:
		var addr [4]byte
		if _, err := io.ReadFull(ctrl, addr[:]); err != nil {
			t.Fatalf("read relay ipv4: %v", err)
		}
		relayHost = net.IP(addr[:]).String()
	case 0x04:
		var addr [16]byte
		if _, err := io.ReadFull(ctrl, addr[:]); err != nil {
			t.Fatalf("read relay ipv6: %v", err)
		}
		relayHost = net.IP(addr[:]).String()
	default:
		t.Fatalf("unsupported relay ATYP %#x", head[3])
	}
	var portBuf [2]byte
	if _, err := io.ReadFull(ctrl, portBuf[:]); err != nil {
		t.Fatalf("read relay port: %v", err)
	}
	relayPort := int(binary.BigEndian.Uint16(portBuf[:]))
	if relayHost == "" || relayHost == "0.0.0.0" || relayHost == "::" {
		t.Fatalf("relay advertised an unusable address %q:%d", relayHost, relayPort)
	}

	udp, err := net.Dial("udp", net.JoinHostPort(relayHost, itoa(relayPort)))
	if err != nil {
		t.Fatalf("dial relay %s:%d: %v", relayHost, relayPort, err)
	}
	defer udp.Close()

	query := buildDNSQuery(0x4d47, "udp-assoc.e2e.test", 1)
	packet := []byte{0x00, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x35}
	packet = append(packet, query...)
	if _, err := udp.Write(packet); err != nil {
		t.Fatalf("send UDP associate datagram: %v", err)
	}

	_ = udp.SetReadDeadline(time.Now().Add(8 * time.Second))
	buf := make([]byte, 1600)
	n, err := udp.Read(buf)
	if err != nil {
		t.Fatalf("no UDP associate reply after 8s (upstream saw %d queries): %v", tn.upstream(), err)
	}
	if n < 11 {
		t.Fatalf("short UDP associate reply: %d bytes", n)
	}
	if buf[3] != 0x01 {
		t.Fatalf("unexpected reply ATYP %#x", buf[3])
	}
	assertDNSAnswer(t, buf[10:n], 0x4d47, net.IPv4(203, 0, 113, 9))
	if got := tn.upstream(); got != 1 {
		t.Fatalf("upstream resolver saw %d queries, want exactly 1", got)
	}
}
