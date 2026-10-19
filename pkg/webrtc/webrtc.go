package webrtc

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// MessageType flags for the multiplexed WebRTC DataChannel stream
const (
	MsgConnect    byte = 0x01
	MsgConnectOK  byte = 0x02
	MsgConnectErr byte = 0x03
	MsgData       byte = 0x04
	MsgClose      byte = 0x05
	MsgKeepalive  byte = 0x06
)

// Frame represents a framed packet transmitted across a WebRTC DataChannel or VP8 media stream
// Wire format: [4-byte TotalLength | 4-byte ConnID | 1-byte MsgType | Payload]
type Frame struct {
	ConnID  uint32
	MsgType byte
	Payload []byte
}

// EncodeFrame encodes a frame into a byte slice
func EncodeFrame(connID uint32, msgType byte, payload []byte) []byte {
	totalLen := 4 + 1 + len(payload)
	buf := make([]byte, 4+totalLen)
	binary.BigEndian.PutUint32(buf[0:4], uint32(totalLen))
	binary.BigEndian.PutUint32(buf[4:8], connID)
	buf[8] = msgType
	copy(buf[9:], payload)
	return buf
}

// DecodeFrame reads and decodes a single framed packet from a stream reader
func DecodeFrame(r io.Reader) (*Frame, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return nil, err
	}
	totalLen := binary.BigEndian.Uint32(lenBuf[:])
	if totalLen < 5 || totalLen > 65536 {
		return nil, fmt.Errorf("invalid frame length: %d", totalLen)
	}

	body := make([]byte, totalLen)
	if _, err := io.ReadFull(r, body); err != nil {
		return nil, err
	}

	connID := binary.BigEndian.Uint32(body[0:4])
	msgType := body[4]
	payload := body[5:]

	return &Frame{
		ConnID:  connID,
		MsgType: msgType,
		Payload: payload,
	}, nil
}

// ChannelMultiplexer manages multiple concurrent TCP/UDP streams over a single covert channel
type ChannelMultiplexer struct {
	conns map[uint32]net.Conn
	mu    sync.RWMutex
	ctx   context.Context
	done  chan struct{}
}

// NewChannelMultiplexer creates a multiplexer for whitelisted video calling / WebRTC SFU tunnels
func NewChannelMultiplexer(ctx context.Context) *ChannelMultiplexer {
	return &ChannelMultiplexer{
		conns: make(map[uint32]net.Conn),
		ctx:   ctx,
		done:  make(chan struct{}),
	}
}

// Register adds a connection to the multiplexer
func (m *ChannelMultiplexer) Register(connID uint32, c net.Conn) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.conns[connID] = c
}

// Unregister closes and removes a connection
func (m *ChannelMultiplexer) Unregister(connID uint32) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if c, ok := m.conns[connID]; ok {
		_ = c.Close()
		delete(m.conns, connID)
	}
}

// Dispatch routes incoming framed data to the target socket
func (m *ChannelMultiplexer) Dispatch(f *Frame) error {
	m.mu.RLock()
	c, ok := m.conns[f.ConnID]
	m.mu.RUnlock()

	if !ok {
		return nil // connection closed or expired
	}

	switch f.MsgType {
	case MsgData:
		_, err := c.Write(f.Payload)
		return err
	case MsgClose:
		m.Unregister(f.ConnID)
		return nil
	default:
		return nil
	}
}

// ActiveCount returns current active multiplexed streams
func (m *ChannelMultiplexer) ActiveCount() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.conns)
}

// KeepaliveTicker generates background keepalive pulses to prevent SFU / TURN relay timeouts
func (m *ChannelMultiplexer) KeepaliveTicker(interval time.Duration, sendFn func([]byte) error) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			frame := EncodeFrame(0, MsgKeepalive, []byte("PING"))
			_ = sendFn(frame)
		case <-m.ctx.Done():
			return
		}
	}
}
