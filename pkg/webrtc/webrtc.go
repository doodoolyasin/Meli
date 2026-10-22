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
	conns          map[uint32]net.Conn
	waiters        map[uint32]chan bool
	connectHandler func(f *Frame) error
	mu             sync.RWMutex
	ctx            context.Context
	done           chan struct{}
}

// NewChannelMultiplexer creates a multiplexer for whitelisted video calling / WebRTC SFU tunnels
func NewChannelMultiplexer(ctx context.Context) *ChannelMultiplexer {
	return &ChannelMultiplexer{
		conns:   make(map[uint32]net.Conn),
		waiters: make(map[uint32]chan bool),
		ctx:     ctx,
		done:    make(chan struct{}),
	}
}

// SetConnectHandler sets the handler for incoming MsgConnect frames (used by relay server)
func (m *ChannelMultiplexer) SetConnectHandler(h func(f *Frame) error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.connectHandler = h
}

// RegisterWaiter registers a channel to wait for connection acknowledgment (MsgConnectOK / MsgConnectErr)
func (m *ChannelMultiplexer) RegisterWaiter(connID uint32) <-chan bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	ch := make(chan bool, 1)
	m.waiters[connID] = ch
	return ch
}

// RemoveWaiter cleans up any pending waiter for connID
func (m *ChannelMultiplexer) RemoveWaiter(connID uint32) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.waiters, connID)
}

func (m *ChannelMultiplexer) notifyWaiter(connID uint32, ok bool) {
	m.mu.Lock()
	ch, exists := m.waiters[connID]
	if exists {
		delete(m.waiters, connID)
	}
	m.mu.Unlock()
	if exists {
		select {
		case ch <- ok:
		default:
		}
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
	delete(m.waiters, connID)
	if c, ok := m.conns[connID]; ok {
		_ = c.Close()
		delete(m.conns, connID)
	}
}

// Dispatch routes incoming framed data to the target socket
func (m *ChannelMultiplexer) Dispatch(f *Frame) error {
	switch f.MsgType {
	case MsgConnect:
		m.mu.RLock()
		h := m.connectHandler
		m.mu.RUnlock()
		if h != nil {
			return h(f)
		}
		return nil
	case MsgConnectOK:
		m.notifyWaiter(f.ConnID, true)
		return nil
	case MsgConnectErr:
		m.notifyWaiter(f.ConnID, false)
		return nil
	case MsgData:
		m.mu.RLock()
		c, ok := m.conns[f.ConnID]
		m.mu.RUnlock()
		if !ok {
			return nil // connection closed or expired
		}
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

// Close closes all active connections registered in the multiplexer and cancels waiters.
func (m *ChannelMultiplexer) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, c := range m.conns {
		_ = c.Close()
		delete(m.conns, id)
	}
	for id := range m.waiters {
		delete(m.waiters, id)
	}
	return nil
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
