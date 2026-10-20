package bale

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/doodoolyasin/meli/pkg/webrtc"
)

// BaleEndpoints lists the official whitelisted endpoints for the Bale Meet platform in Iran.
// NOTE: Bale Meet SFU (meet.bale.ai) provides domestic intranet WebRTC signaling and media relay,
// but does NOT act as an autonomous public internet proxy. A paired external RelayBridge
// running outside the restricted zone is required to egress traffic to arbitrary internet hosts.
var BaleEndpoints = struct {
	MeetBaseURL string
	STUNServer  string
	TURNServer  string
}{
	MeetBaseURL: "https://meet.bale.ai",
	STUNServer:  "stun.bale.ai:3478",
	TURNServer:  "turn.bale.ai:3478",
}

// TunnelSession manages an active SOCKS5-to-WebRTC session over Bale Meet infrastructure.
type TunnelSession struct {
	RoomCode      string
	SocksPort     int
	isCreator     bool
	mux           *webrtc.ChannelMultiplexer
	ctx           context.Context
	cancel        context.CancelFunc
	mu            sync.Mutex
	socksListener net.Listener
	peerSender    func(f *webrtc.Frame) error
	nextConnID    atomic.Uint32
}

// NewTunnelSession creates a Bale Meet tunnel session for whitelisted intranet bypass.
func NewTunnelSession(roomCode string, socksPort int, isCreator bool) *TunnelSession {
	ctx, cancel := context.WithCancel(context.Background())
	return &TunnelSession{
		RoomCode:  strings.TrimSpace(roomCode),
		SocksPort: socksPort,
		isCreator: isCreator,
		mux:       webrtc.NewChannelMultiplexer(ctx),
		ctx:       ctx,
		cancel:    cancel,
	}
}

// AttachPeer connects an active WebRTC DataChannel transport sender to the session.
func (s *TunnelSession) AttachPeer(sender func(f *webrtc.Frame) error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.peerSender = sender
}

// DetachPeer disconnects the active WebRTC DataChannel transport sender.
func (s *TunnelSession) DetachPeer() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.peerSender = nil
}

// IsPeered returns true if an active DataChannel peer sender is attached.
func (s *TunnelSession) IsPeered() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.peerSender != nil
}

// sendToPeer transmits a framed message to the attached peer.
func (s *TunnelSession) sendToPeer(f *webrtc.Frame) error {
	s.mu.Lock()
	sender := s.peerSender
	s.mu.Unlock()
	if sender == nil {
		return errors.New("no active peer attached")
	}
	return sender(f)
}

// ReceiveFrame processes an incoming frame from the remote WebRTC DataChannel.
func (s *TunnelSession) ReceiveFrame(f *webrtc.Frame) error {
	return s.mux.Dispatch(f)
}

// CheckReachability tests whether the domestic Bale SFU is reachable from the current network.
func (s *TunnelSession) CheckReachability(timeout time.Duration) bool {
	client := &http.Client{Timeout: timeout}
	resp, err := client.Get(BaleEndpoints.MeetBaseURL)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode >= 200 && resp.StatusCode < 500
}

// StartSocksBridge starts a local SOCKS5 listener that routes incoming client apps
// through the WebRTC DataChannel stream.
func (s *TunnelSession) StartSocksBridge() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	addr := fmt.Sprintf("127.0.0.1:%d", s.SocksPort)
	l, err := net.Listen("tcp", addr)
	if err != nil {
		l, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return 0, err
		}
	}
	s.socksListener = l
	actualPort := l.Addr().(*net.TCPAddr).Port
	s.SocksPort = actualPort

	go func() {
		for {
			conn, err := l.Accept()
			if err != nil {
				select {
				case <-s.ctx.Done():
					return
				default:
					continue
				}
			}
			go s.handleSocksClient(conn)
		}
	}()

	return actualPort, nil
}

// handleSocksClient processes the SOCKS5 handshake (RFC 1928) and forwards
// payload over the WebRTC DataChannel multiplexer.
func (s *TunnelSession) handleSocksClient(c net.Conn) {
	defer c.Close()

	// 1. Negotiation phase (RFC 1928 Section 3)
	// Read: [VER, NMETHODS, METHODS...]
	header := make([]byte, 2)
	if _, err := io.ReadFull(c, header); err != nil {
		return
	}
	if header[0] != 0x05 {
		// Only SOCKS5 supported
		return
	}
	numMethods := int(header[1])
	methods := make([]byte, numMethods)
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}

	// Reply: [0x05, 0x00] (No authentication required)
	if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. Request phase (RFC 1928 Section 4)
	// Read: [VER, CMD, RSV, ATYP]
	reqHeader := make([]byte, 4)
	if _, err := io.ReadFull(c, reqHeader); err != nil {
		return
	}
	if reqHeader[0] != 0x05 {
		return
	}
	cmd := reqHeader[1]
	atyp := reqHeader[3]

	if cmd != 0x01 { // 0x01 = CONNECT
		// 0x07 = Command not supported
		_, _ = c.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch atyp {
	case 0x01: // IPv4 (4 bytes)
		ipBuf := make([]byte, 4)
		if _, err := io.ReadFull(c, ipBuf); err != nil {
			return
		}
		host = net.IP(ipBuf).String()
	case 0x03: // Domain name (1-byte length prefix + domain string)
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(c, lenBuf); err != nil {
			return
		}
		domainLen := int(lenBuf[0])
		domainBuf := make([]byte, domainLen)
		if _, err := io.ReadFull(c, domainBuf); err != nil {
			return
		}
		host = string(domainBuf)
	case 0x04: // IPv6 (16 bytes)
		ipBuf := make([]byte, 16)
		if _, err := io.ReadFull(c, ipBuf); err != nil {
			return
		}
		host = net.IP(ipBuf).String()
	default:
		// 0x08 = Address type not supported
		_, _ = c.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Read Port (2 bytes BigEndian)
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(c, portBuf); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf)
	targetAddr := net.JoinHostPort(host, strconv.Itoa(int(port)))

	// Check if peer is attached
	if !s.IsPeered() {
		// 0x05 = Connection refused / Network unreachable
		_, _ = c.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	connID := s.nextConnID.Add(1)
	s.mux.Register(connID, c)
	defer s.mux.Unregister(connID)

	waitCh := s.mux.RegisterWaiter(connID)
	defer s.mux.RemoveWaiter(connID)

	// Send MsgConnect frame to peer
	connectFrame := &webrtc.Frame{
		ConnID:  connID,
		MsgType: webrtc.MsgConnect,
		Payload: []byte(targetAddr),
	}
	if err := s.sendToPeer(connectFrame); err != nil {
		_, _ = c.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Wait for peer acknowledgment (MsgConnectOK or MsgConnectErr)
	select {
	case ok := <-waitCh:
		if !ok {
			// 0x04 = Host unreachable
			_, _ = c.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
	case <-time.After(10 * time.Second):
		// 0x04 = Host unreachable (timeout)
		_, _ = c.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	case <-s.ctx.Done():
		return
	}

	// Reply SOCKS5 SUCCESS: [0x05, 0x00 (succeeded), 0x00, 0x01, 0,0,0,0, 0,0]
	if _, err := c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	// Stream client data to peer over DataChannel chunks (<= 16384 bytes)
	buf := make([]byte, 16384)
	for {
		n, err := c.Read(buf)
		if n > 0 {
			payload := make([]byte, n)
			copy(payload, buf[:n])
			dataFrame := &webrtc.Frame{
				ConnID:  connID,
				MsgType: webrtc.MsgData,
				Payload: payload,
			}
			if sendErr := s.sendToPeer(dataFrame); sendErr != nil {
				break
			}
		}
		if err != nil {
			break
		}
	}

	// Notify peer to close stream
	closeFrame := &webrtc.Frame{
		ConnID:  connID,
		MsgType: webrtc.MsgClose,
	}
	_ = s.sendToPeer(closeFrame)
}

// Close gracefully closes the session and active listeners.
func (s *TunnelSession) Close() error {
	s.cancel()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.peerSender = nil
	if s.socksListener != nil {
		return s.socksListener.Close()
	}
	return nil
}

// RelayBridge represents an egress relay server running outside the censorship zone.
// It joins the same WebRTC channel, dials requested target addresses on behalf of
// the client, and bridges bidirectional traffic.
type RelayBridge struct {
	mux        *webrtc.ChannelMultiplexer
	sendToPeer func(f *webrtc.Frame) error
	dialFn     func(network, address string) (net.Conn, error)
	ctx        context.Context
	cancel     context.CancelFunc
}

// NewRelayBridge creates a server-side egress bridge for WebRTC DataChannel tunnels.
func NewRelayBridge(sendToPeer func(f *webrtc.Frame) error) *RelayBridge {
	ctx, cancel := context.WithCancel(context.Background())
	b := &RelayBridge{
		mux:        webrtc.NewChannelMultiplexer(ctx),
		sendToPeer: sendToPeer,
		dialFn: func(network, address string) (net.Conn, error) {
			return net.DialTimeout(network, address, 10*time.Second)
		},
		ctx:    ctx,
		cancel: cancel,
	}
	b.mux.SetConnectHandler(b.handleConnect)
	return b
}

// SetDialer allows overriding the target dialer (e.g., for testing or proxying).
func (b *RelayBridge) SetDialer(fn func(network, address string) (net.Conn, error)) {
	b.dialFn = fn
}

// ReceiveFrame processes an incoming frame from the client over the DataChannel.
func (b *RelayBridge) ReceiveFrame(f *webrtc.Frame) error {
	return b.mux.Dispatch(f)
}

func (b *RelayBridge) handleConnect(f *webrtc.Frame) error {
	target := string(f.Payload)
	conn, err := b.dialFn("tcp", target)
	if err != nil {
		return b.sendToPeer(&webrtc.Frame{
			ConnID:  f.ConnID,
			MsgType: webrtc.MsgConnectErr,
		})
	}

	b.mux.Register(f.ConnID, conn)

	// Send success
	if err := b.sendToPeer(&webrtc.Frame{
		ConnID:  f.ConnID,
		MsgType: webrtc.MsgConnectOK,
	}); err != nil {
		b.mux.Unregister(f.ConnID)
		return err
	}

	// Stream from target conn back to client over DataChannel
	go func(connID uint32, c net.Conn) {
		defer b.mux.Unregister(connID)
		buf := make([]byte, 16384)
		for {
			n, err := c.Read(buf)
			if n > 0 {
				payload := make([]byte, n)
				copy(payload, buf[:n])
				dataFrame := &webrtc.Frame{
					ConnID:  connID,
					MsgType: webrtc.MsgData,
					Payload: payload,
				}
				if sendErr := b.sendToPeer(dataFrame); sendErr != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
		_ = b.sendToPeer(&webrtc.Frame{
			ConnID:  connID,
			MsgType: webrtc.MsgClose,
		})
	}(f.ConnID, conn)

	return nil
}

// Close terminates the relay bridge.
func (b *RelayBridge) Close() error {
	b.cancel()
	return nil
}
