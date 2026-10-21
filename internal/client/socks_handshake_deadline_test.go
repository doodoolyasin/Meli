package client

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

// A local process can open a SOCKS connection and never send a byte. Before the
// handshake deadline existed that pinned a goroutine and a file descriptor for
// the lifetime of the client process; enough connections exhausted the process.
func TestSilentSOCKSClientIsDisconnectedAfterHandshakeTimeout(t *testing.T) {
	c := &Client{socksHandshakeTimeout: 150 * time.Millisecond}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()

	serverConnCh := make(chan net.Conn, 1)
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr == nil {
			serverConnCh <- conn
		}
	}()

	clientConn, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer clientConn.Close()

	var serverConn net.Conn
	select {
	case serverConn = <-serverConnCh:
	case <-time.After(2 * time.Second):
		t.Fatal("accept timed out")
	}

	go c.HandleSOCKS5(context.Background(), serverConn)

	started := time.Now()
	_ = clientConn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := clientConn.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected the idle SOCKS connection to be closed")
	} else if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
		t.Fatalf("silent SOCKS client still connected after %s; handshake deadline not enforced", time.Since(started))
	}

	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("idle connection closed too late: %s", elapsed)
	}
}

// The same bound must apply once a client has sent only a version byte and then
// stalls: a partial greeting is just as cheap to hold open as an empty one.
func TestStalledSOCKSPartialGreetingIsDisconnected(t *testing.T) {
	c := &Client{socksHandshakeTimeout: 150 * time.Millisecond}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()

	serverConnCh := make(chan net.Conn, 1)
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr == nil {
			serverConnCh <- conn
		}
	}()

	clientConn, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer clientConn.Close()

	select {
	case serverConn := <-serverConnCh:
		go c.HandleSOCKS5(context.Background(), serverConn)
	case <-time.After(2 * time.Second):
		t.Fatal("accept timed out")
	}

	if _, err := clientConn.Write([]byte{SOCKS5_VERSION}); err != nil {
		t.Fatalf("write version: %v", err)
	}

	_ = clientConn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := clientConn.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected stalled greeting to be closed")
	} else if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
		t.Fatal("stalled greeting still connected; handshake deadline not enforced")
	}
}

// Once the request is complete the deadline must be removed, otherwise a legit
// long-lived idle tunnel would be torn down by the handshake timer.
func TestClearedHandshakeDeadlineDoesNotFireDuringRelay(t *testing.T) {
	c := &Client{socksHandshakeTimeout: 100 * time.Millisecond}

	serverConn, peerConn := net.Pipe()
	defer serverConn.Close()
	defer peerConn.Close()

	c.beginSocksHandshake(serverConn)
	clearSocksHandshakeDeadline(serverConn)

	go func() {
		time.Sleep(350 * time.Millisecond)
		_, _ = peerConn.Write([]byte("x"))
	}()

	_ = serverConn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1)
	if _, err := io.ReadFull(serverConn, buf); err != nil {
		t.Fatalf("relay read after deadline clear failed: %v", err)
	}
}
