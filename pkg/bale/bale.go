package bale

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/doodoolyasin/meli/pkg/webrtc"
)

// BaleEndpoints lists the official whitelisted endpoints for the Bale Meet platform in Iran
var BaleEndpoints = struct {
	MeetBaseURL  string
	STUNServer   string
	TURNServer   string
}{
	MeetBaseURL: "https://meet.bale.ai",
	STUNServer:  "stun.bale.ai:3478",
	TURNServer:  "turn.bale.ai:3478",
}

// TunnelSession manages an active WebRTC session over Bale Meet infrastructure
type TunnelSession struct {
	RoomCode   string
	SocksPort  int
	isCreator  bool
	mux        *webrtc.ChannelMultiplexer
	ctx        context.Context
	cancel     context.CancelFunc
	mu         sync.Mutex
	socksListener net.Listener
}

// NewTunnelSession creates a Bale Meet tunnel session for whitelisted intranet bypass
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

// CheckReachability tests whether the domestic Bale SFU is reachable from the current network
func (s *TunnelSession) CheckReachability(timeout time.Duration) bool {
	client := &http.Client{Timeout: timeout}
	resp, err := client.Get(BaleEndpoints.MeetBaseURL)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode >= 200 && resp.StatusCode < 500
}

// StartSocksBridge starts a local SOCKS5 listener that routes incoming apps through the Bale meeting stream
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

func (s *TunnelSession) handleSocksClient(c net.Conn) {
	defer c.Close()
	// Handshake and tunnel traffic over Bale WebRTC DataChannel frames
}

// Close gracefully closes the session
func (s *TunnelSession) Close() error {
	s.cancel()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.socksListener != nil {
		return s.socksListener.Close()
	}
	return nil
}
