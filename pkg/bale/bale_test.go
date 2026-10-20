package bale

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/doodoolyasin/meli/pkg/webrtc"
)

// TestBaleSocks5UnpeeredReturnsRefusal verifies that when no DataChannel peer is attached,
// the SOCKS5 server returns RFC 1928 REP=0x05 (Connection Refused) rather than crashing or hanging.
func TestBaleSocks5UnpeeredReturnsRefusal(t *testing.T) {
	session := NewTunnelSession("test-room-1", 0, false)
	defer session.Close()

	port, err := session.StartSocksBridge()
	if err != nil {
		t.Fatalf("Failed to start SOCKS bridge: %v", err)
	}

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to dial SOCKS port: %v", err)
	}
	defer conn.Close()

	// 1. Send SOCKS5 greeting: VER=5, NMETHODS=1, METHOD=0 (No auth)
	_, err = conn.Write([]byte{0x05, 0x01, 0x00})
	if err != nil {
		t.Fatalf("Failed to send greeting: %v", err)
	}

	// 2. Read greeting response
	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		t.Fatalf("Failed to read greeting response: %v", err)
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		t.Fatalf("Expected SOCKS5 no-auth reply [0x05, 0x00], got %v", resp)
	}

	// 3. Send CONNECT request to IPv4 target 1.1.1.1:80
	req := []byte{0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0x00, 0x50}
	if _, err := conn.Write(req); err != nil {
		t.Fatalf("Failed to send CONNECT request: %v", err)
	}

	// 4. Read reply: should be REP=0x05 (Connection Refused) because session is unpeered
	reply := make([]byte, 10)
	if _, err := io.ReadFull(conn, reply); err != nil {
		t.Fatalf("Failed to read connect reply: %v", err)
	}
	if reply[0] != 0x05 {
		t.Errorf("Expected SOCKS version 5, got %d", reply[0])
	}
	if reply[1] != 0x05 {
		t.Errorf("Expected SOCKS reply 0x05 (Connection Refused), got 0x%02x", reply[1])
	}
}

// TestBaleSocks5UnsupportedCommand verifies RFC 1928 command validation.
func TestBaleSocks5UnsupportedCommand(t *testing.T) {
	session := NewTunnelSession("test-room-2", 0, false)
	defer session.Close()

	port, err := session.StartSocksBridge()
	if err != nil {
		t.Fatalf("Failed to start SOCKS bridge: %v", err)
	}

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to dial SOCKS port: %v", err)
	}
	defer conn.Close()

	// Greeting
	_, _ = conn.Write([]byte{0x05, 0x01, 0x00})
	resp := make([]byte, 2)
	_, _ = io.ReadFull(conn, resp)

	// Send CMD=0x02 (BIND) which is not supported
	req := []byte{0x05, 0x02, 0x00, 0x01, 1, 1, 1, 1, 0x00, 0x50}
	_, _ = conn.Write(req)

	reply := make([]byte, 10)
	_, err = io.ReadFull(conn, reply)
	if err != nil {
		t.Fatalf("Failed to read reply: %v", err)
	}
	if reply[1] != 0x07 { // 0x07 = Command not supported
		t.Errorf("Expected REP=0x07 (Command not supported), got 0x%02x", reply[1])
	}
}

// TestBaleTunnelSessionAndRelayBridgeE2E tests full end-to-end data transfer:
// SOCKS5 client -> TunnelSession -> Simulated WebRTC DataChannel -> RelayBridge -> Echo Server
func TestBaleTunnelSessionAndRelayBridgeE2E(t *testing.T) {
	// 1. Start a local TCP Echo server
	echoListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to start echo listener: %v", err)
	}
	defer echoListener.Close()
	echoAddr := echoListener.Addr().String()

	go func() {
		for {
			conn, err := echoListener.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 4096)
				for {
					n, err := c.Read(buf)
					if n > 0 {
						_, _ = c.Write(buf[:n])
					}
					if err != nil {
						return
					}
				}
			}(conn)
		}
	}()

	// 2. Set up simulated DataChannel pipe between Client and Relay
	var clientSession *TunnelSession
	var relayBridge *RelayBridge

	relayBridge = NewRelayBridge(func(f *webrtc.Frame) error {
		// Frame from Relay -> Client
		return clientSession.ReceiveFrame(f)
	})
	defer relayBridge.Close()

	clientSession = NewTunnelSession("e2e-room", 0, true)
	defer clientSession.Close()

	clientSession.AttachPeer(func(f *webrtc.Frame) error {
		// Frame from Client -> Relay
		return relayBridge.ReceiveFrame(f)
	})

	socksPort, err := clientSession.StartSocksBridge()
	if err != nil {
		t.Fatalf("Failed to start SOCKS bridge: %v", err)
	}

	// 3. Connect a SOCKS5 client
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to dial SOCKS5: %v", err)
	}
	defer conn.Close()

	// Greeting
	_, err = conn.Write([]byte{0x05, 0x01, 0x00})
	if err != nil {
		t.Fatalf("Failed to send greeting: %v", err)
	}
	gResp := make([]byte, 2)
	if _, err := io.ReadFull(conn, gResp); err != nil {
		t.Fatalf("Failed to read greeting response: %v", err)
	}
	if gResp[0] != 0x05 || gResp[1] != 0x00 {
		t.Fatalf("Invalid greeting response: %v", gResp)
	}

	// Send CONNECT to echo server using Domain name ATYP (0x03)
	host, portStr, _ := net.SplitHostPort(echoAddr)
	var portNum uint16
	fmt.Sscanf(portStr, "%d", &portNum)

	var reqBuf bytes.Buffer
	reqBuf.Write([]byte{0x05, 0x01, 0x00, 0x03, byte(len(host))})
	reqBuf.WriteString(host)
	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, portNum)
	reqBuf.Write(portBytes)

	if _, err := conn.Write(reqBuf.Bytes()); err != nil {
		t.Fatalf("Failed to write connect request: %v", err)
	}

	reply := make([]byte, 10)
	if _, err := io.ReadFull(conn, reply); err != nil {
		t.Fatalf("Failed to read connect reply: %v", err)
	}
	if reply[1] != 0x00 {
		t.Fatalf("Connect failed with REP=0x%02x", reply[1])
	}

	// 4. Send test payload and verify echo
	testPayload := []byte("HELLO_BALE_WEBRTC_DATACHANNEL_E2E_VERIFIED")
	if _, err := conn.Write(testPayload); err != nil {
		t.Fatalf("Failed to write payload: %v", err)
	}

	recvBuf := make([]byte, len(testPayload))
	if _, err := io.ReadFull(conn, recvBuf); err != nil {
		t.Fatalf("Failed to read echo response: %v", err)
	}

	if !bytes.Equal(recvBuf, testPayload) {
		t.Fatalf("Echo mismatch! Expected %s, got %s", string(testPayload), string(recvBuf))
	}
}

// TestConcurrentStreams verifies the multiplexer handles concurrent connections cleanly.
func TestConcurrentStreams(t *testing.T) {
	echoListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to start echo listener: %v", err)
	}
	defer echoListener.Close()
	echoAddr := echoListener.Addr().String()

	go func() {
		for {
			conn, err := echoListener.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()

	var clientSession *TunnelSession
	var relayBridge *RelayBridge

	relayBridge = NewRelayBridge(func(f *webrtc.Frame) error {
		return clientSession.ReceiveFrame(f)
	})
	defer relayBridge.Close()

	clientSession = NewTunnelSession("concurrent-room", 0, true)
	defer clientSession.Close()

	clientSession.AttachPeer(func(f *webrtc.Frame) error {
		return relayBridge.ReceiveFrame(f)
	})

	socksPort, err := clientSession.StartSocksBridge()
	if err != nil {
		t.Fatalf("Failed to start SOCKS bridge: %v", err)
	}

	var wg sync.WaitGroup
	numClients := 5
	for i := 0; i < numClients; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), 3*time.Second)
			if err != nil {
				t.Errorf("Client %d failed to dial SOCKS5: %v", id, err)
				return
			}
			defer conn.Close()

			// Greeting
			_, _ = conn.Write([]byte{0x05, 0x01, 0x00})
			gResp := make([]byte, 2)
			_, _ = io.ReadFull(conn, gResp)

			// CONNECT to echoAddr (IPv4)
			host, portStr, _ := net.SplitHostPort(echoAddr)
			var portNum uint16
			fmt.Sscanf(portStr, "%d", &portNum)

			ip := net.ParseIP(host).To4()
			req := []byte{0x05, 0x01, 0x00, 0x01, ip[0], ip[1], ip[2], ip[3], byte(portNum >> 8), byte(portNum & 0xff)}
			_, _ = conn.Write(req)

			reply := make([]byte, 10)
			if _, err := io.ReadFull(conn, reply); err != nil || reply[1] != 0x00 {
				t.Errorf("Client %d connect failed: %v, rep: %x", id, err, reply[1])
				return
			}

			msg := fmt.Sprintf("STREAM_%d_DATA", id)
			_, _ = conn.Write([]byte(msg))
			recv := make([]byte, len(msg))
			_, err = io.ReadFull(conn, recv)
			if err != nil || string(recv) != msg {
				t.Errorf("Client %d mismatch: got %s, err %v", id, string(recv), err)
			}
		}(i)
	}
	wg.Wait()
}
