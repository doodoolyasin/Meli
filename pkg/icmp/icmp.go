package icmp

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// Magic header to distinguish tunnel ICMP payloads from regular ping diagnostic packets
var ICMPEchoMagic = []byte{0x4D, 0x4C, 0x49, 0x43} // "MLIC"

// Packet represents an encapsulated tunnel frame inside an ICMP Echo packet
type Packet struct {
	SessionID uint32
	Seq       uint32
	Payload   []byte
}

// TunnelClient provides emergency bidirectional communication over ICMP Echo Request/Reply
type TunnelClient struct {
	RemoteIP net.IP
	Key      []byte
	conn     net.PacketConn
	mu       sync.Mutex
}

// NewTunnelClient creates an ICMP tunnel client targeting remoteIP
func NewTunnelClient(remoteIP net.IP, key []byte) (*TunnelClient, error) {
	if len(key) < 16 {
		return nil, fmt.Errorf("encryption key must be at least 16 bytes")
	}
	conn, err := net.ListenPacket("ip4:icmp", "0.0.0.0")
	if err != nil {
		return nil, fmt.Errorf("failed to open raw ICMP socket (requires root/CAP_NET_RAW): %w", err)
	}
	return &TunnelClient{
		RemoteIP: remoteIP,
		Key:      key[:16],
		conn:     conn,
	}, nil
}

// Send encrypts data and sends it as an ICMP Echo Request (Type 8)
func (c *TunnelClient) Send(ctx context.Context, sessionID uint32, seq uint32, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	// 1. Build plaintext: Magic(4) + Session(4) + Seq(4) + Data
	raw := make([]byte, 12+len(data))
	copy(raw[0:4], ICMPEchoMagic)
	binary.BigEndian.PutUint32(raw[4:8], sessionID)
	binary.BigEndian.PutUint32(raw[8:12], seq)
	copy(raw[12:], data)

	// 2. Encrypt with AES-CTR
	block, err := aes.NewCipher(c.Key)
	if err != nil {
		return err
	}
	iv := make([]byte, aes.BlockSize)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return err
	}
	stream := cipher.NewCTR(block, iv)
	ciphertext := make([]byte, len(raw))
	stream.XORKeyStream(ciphertext, raw)

	// 3. Assemble ICMP Echo Request (RFC 792)
	// Type: 8 (Echo), Code: 0, Checksum: 0 (kernel/Go calculates or manual), ID: 0x4D4C, Seq: seq
	payload := append(iv, ciphertext...)
	icmpHeader := make([]byte, 8)
	icmpHeader[0] = 8 // Echo Request
	icmpHeader[1] = 0 // Code
	// Checksum at [2:4]
	binary.BigEndian.PutUint16(icmpHeader[4:6], 0x4D4C)
	binary.BigEndian.PutUint16(icmpHeader[6:8], uint16(seq&0xFFFF))

	fullPacket := append(icmpHeader, payload...)
	cs := checksum(fullPacket)
	binary.BigEndian.PutUint16(fullPacket[2:4], cs)

	dst := &net.IPAddr{IP: c.RemoteIP}
	_, err = c.conn.WriteTo(fullPacket, dst)
	return err
}

// Recv receives an ICMP Echo Reply (Type 0) and decrypts the tunnel payload
func (c *TunnelClient) Recv(ctx context.Context, timeout time.Duration) (*Packet, error) {
	buf := make([]byte, 4096)
	_ = c.conn.SetDeadline(time.Now().Add(timeout))

	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		n, addr, err := c.conn.ReadFrom(buf)
		if err != nil {
			return nil, err
		}

		ipAddr, ok := addr.(*net.IPAddr)
		if !ok || !ipAddr.IP.Equal(c.RemoteIP) {
			continue
		}

		if n < 8+aes.BlockSize+12 {
			continue
		}

		// Check if ICMP Type is 0 (Echo Reply)
		if buf[0] != 0 {
			continue
		}

		iv := buf[8 : 8+aes.BlockSize]
		ciphertext := buf[8+aes.BlockSize : n]

		block, err := aes.NewCipher(c.Key)
		if err != nil {
			continue
		}
		stream := cipher.NewCTR(block, iv)
		plaintext := make([]byte, len(ciphertext))
		stream.XORKeyStream(plaintext, ciphertext)

		// Verify Magic
		if string(plaintext[0:4]) != string(ICMPEchoMagic) {
			continue
		}

		sessionID := binary.BigEndian.Uint32(plaintext[4:8])
		seq := binary.BigEndian.Uint32(plaintext[8:12])
		data := plaintext[12:]

		return &Packet{
			SessionID: sessionID,
			Seq:       seq,
			Payload:   data,
		}, nil
	}
}

// Close closes the ICMP raw socket
func (c *TunnelClient) Close() error {
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

// checksum calculates Internet Checksum (RFC 1071)
func checksum(data []byte) uint16 {
	var sum uint32
	for i := 0; i < len(data)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for (sum >> 16) > 0 {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}
