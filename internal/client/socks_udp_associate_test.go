package client

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"

	"github.com/doodoolyasin/meli/internal/config"
	"github.com/doodoolyasin/meli/internal/logger"
)

func testSocksUDPClient(t *testing.T) *Client {
	t.Helper()
	return &Client{
		cfg: config.ClientConfig{
			SOCKSUDPAssociateReadTimeoutSeconds: 5,
		},
		log: logger.New("socks-udp-test", "ERROR"),
	}
}

// tcpPair returns a connected (server, client) TCP pair on loopback. The server
// half is what the SOCKS handler would receive from Accept.
func tcpPair(t *testing.T) (server net.Conn, client net.Conn) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	accepted := make(chan net.Conn, 1)
	go func() {
		c, aerr := ln.Accept()
		if aerr != nil {
			close(accepted)
			return
		}
		accepted <- c
	}()

	dialed, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	serverConn, ok := <-accepted
	if !ok || serverConn == nil {
		dialed.Close()
		t.Fatalf("accept failed")
	}
	return serverConn, dialed
}

func readSocksReply(t *testing.T, conn net.Conn) (rep byte, atyp byte, addr net.IP, port uint16) {
	t.Helper()
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		t.Fatalf("read reply header: %v", err)
	}
	if head[0] != SOCKS5_VERSION {
		t.Fatalf("reply version = %d, want 5", head[0])
	}
	rep, atyp = head[1], head[3]

	switch atyp {
	case SOCKS5_ATYP_IPV4:
		buf := make([]byte, 6)
		if _, err := io.ReadFull(conn, buf); err != nil {
			t.Fatalf("read ipv4 body: %v", err)
		}
		addr = net.IP(buf[0:4])
		port = binary.BigEndian.Uint16(buf[4:6])
	case SOCKS5_ATYP_IPV6:
		buf := make([]byte, 18)
		if _, err := io.ReadFull(conn, buf); err != nil {
			t.Fatalf("read ipv6 body: %v", err)
		}
		addr = net.IP(buf[0:16])
		port = binary.BigEndian.Uint16(buf[16:18])
	default:
		t.Fatalf("unexpected reply ATYP %d", atyp)
	}
	return rep, atyp, addr, port
}

// TestSocksUDPAssociateRepliesWithReachableAddress verifies that the relay
// reports a concrete address and a real port. The previous implementation bound
// 0.0.0.0 and answered with 0.0.0.0, which tells the client nothing about where
// to send its datagrams.
func TestSocksUDPAssociateRepliesWithReachableAddress(t *testing.T) {
	c := testSocksUDPClient(t)
	serverConn, clientConn := tcpPair(t)
	defer clientConn.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		c.handleSocksUDPAssociate(context.Background(), serverConn, "0.0.0.0", 0, SOCKS5_ATYP_IPV4)
	}()

	rep, atyp, addr, port := readSocksReply(t, clientConn)
	if rep != SOCKS5_REPLY_SUCCESS {
		t.Fatalf("reply = %#x, want success", rep)
	}
	if atyp != SOCKS5_ATYP_IPV4 {
		t.Fatalf("atyp = %d, want ipv4", atyp)
	}
	if addr.IsUnspecified() {
		t.Fatalf("relay replied with unspecified address %s", addr)
	}
	if !addr.Equal(net.IPv4(127, 0, 0, 1)) {
		t.Fatalf("relay replied with %s, want loopback", addr)
	}
	if port == 0 {
		t.Fatal("relay replied with port 0")
	}

	// A datagram from the control connection's peer reaches the handler; with no
	// session the handler closes the association, which is the documented
	// retry-on-miss behaviour.
	udp, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: addr, Port: int(port)})
	if err != nil {
		t.Fatalf("dial udp: %v", err)
	}
	defer udp.Close()
	if _, err := udp.Write([]byte{0, 0, 0, SOCKS5_ATYP_IPV4, 127, 0, 0, 1, 0, 53, 1, 2, 3}); err != nil {
		t.Fatalf("write udp: %v", err)
	}

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("association did not close after a DNS miss")
	}
}

// TestSocksUDPAssociateDropsForeignSources proves the RFC 1928 section 7 rule:
// only the peer of the TCP control connection may use the relay. Without this,
// any host that can reach the bound port can relay DNS through the tunnel.
func TestSocksUDPAssociateDropsForeignSources(t *testing.T) {
	c := testSocksUDPClient(t)
	serverConn, clientConn := tcpPair(t)
	defer clientConn.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		c.handleSocksUDPAssociate(context.Background(), serverConn, "0.0.0.0", 0, SOCKS5_ATYP_IPV4)
	}()

	rep, _, addr, port := readSocksReply(t, clientConn)
	if rep != SOCKS5_REPLY_SUCCESS {
		t.Fatalf("reply = %#x, want success", rep)
	}

	// 127.0.0.2 is a different loopback address: the source check must reject it
	// even though it reaches the same socket.
	foreign, err := net.DialUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 2)}, &net.UDPAddr{IP: addr, Port: int(port)})
	if err != nil {
		t.Skipf("cannot bind a second loopback address on this host: %v", err)
	}
	defer foreign.Close()
	if _, err := foreign.Write([]byte{0, 0, 0, SOCKS5_ATYP_IPV4, 127, 0, 0, 1, 0, 53, 1}); err != nil {
		t.Fatalf("write foreign datagram: %v", err)
	}

	select {
	case <-done:
		t.Fatal("association closed on a foreign datagram; foreign traffic must be dropped, not honoured")
	case <-time.After(300 * time.Millisecond):
	}
}

// TestSocksUDPAssociateEndsWithControlConnection verifies the association is
// torn down when the TCP control connection closes, instead of leaking the
// descriptor and the relay goroutine.
func TestSocksUDPAssociateEndsWithControlConnection(t *testing.T) {
	c := testSocksUDPClient(t)
	serverConn, clientConn := tcpPair(t)

	done := make(chan struct{})
	go func() {
		defer close(done)
		c.handleSocksUDPAssociate(context.Background(), serverConn, "0.0.0.0", 0, SOCKS5_ATYP_IPV4)
	}()

	if rep, _, _, _ := readSocksReply(t, clientConn); rep != SOCKS5_REPLY_SUCCESS {
		t.Fatalf("reply = %#x, want success", rep)
	}

	if err := clientConn.Close(); err != nil {
		t.Fatalf("close control connection: %v", err)
	}

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay outlived its TCP control connection")
	}
}

func TestSocksUDPPeerAllowed(t *testing.T) {
	clientIP := net.IPv4(10, 0, 0, 7)

	tests := []struct {
		name         string
		peer         *net.UDPAddr
		clientIP     net.IP
		expectedPort int
		want         bool
	}{
		{"nil peer", nil, clientIP, 0, false},
		{"matching ip, learned port", &net.UDPAddr{IP: net.IPv4(10, 0, 0, 7), Port: 4000}, clientIP, 0, true},
		{"matching ip, matching port", &net.UDPAddr{IP: net.IPv4(10, 0, 0, 7), Port: 4000}, clientIP, 4000, true},
		{"matching ip, wrong port", &net.UDPAddr{IP: net.IPv4(10, 0, 0, 7), Port: 4001}, clientIP, 4000, false},
		{"foreign ip", &net.UDPAddr{IP: net.IPv4(10, 0, 0, 8), Port: 4000}, clientIP, 0, false},
		{"unknown client ip allows any", &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 4000}, nil, 0, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := socksUDPPeerAllowed(tt.peer, tt.clientIP, tt.expectedPort); got != tt.want {
				t.Fatalf("socksUDPPeerAllowed() = %v, want %v", got, tt.want)
			}
		})
	}
}
