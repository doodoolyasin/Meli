package ntp

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

// Magic header for Meli NTP covert frames
var NTPTunnelMagic = []byte{0x4D, 0x4E, 0x54, 0x50} // "MNTP"

// Packet represents an encapsulated tunnel frame inside an NTP packet
type Packet struct {
	SessionID uint32
	Seq       uint32
	Payload   []byte
}

// ntpEpochOffset is the number of seconds between the NTP epoch (1900-01-01)
// and the Unix epoch (1970-01-01).
const ntpEpochOffset = 2208988800

// ntpTimestamp converts t into the 64-bit NTP timestamp format of RFC 5905
// section 6: 32 bits of seconds since 1900 followed by 32 bits of fraction.
//
// The fraction is the nanosecond value scaled to 2^32, so it must be computed
// in 64-bit arithmetic: shifting a 32-bit value by 32 yields zero, which would
// make every packet claim to have been generated exactly on a second boundary.
// That pattern is trivially flaggable by any NTP monitor or DPI middlebox that
// this channel is supposed to blend into.
func ntpTimestamp(t time.Time) (sec uint32, frac uint32) {
	sec = uint32(t.Unix() + ntpEpochOffset)
	frac = uint32((uint64(t.Nanosecond()) << 32) / uint64(time.Second))
	return sec, frac
}

// TunnelClient provides emergency communication over UDP port 123 (NTP)
// Unblockable because blocking UDP 123 causes system clock drift, breaking all TLS certificates nationwide.
type TunnelClient struct {
	RemoteAddr string
	Key        []byte
	conn       *net.UDPConn
	mu         sync.Mutex
}

// NewTunnelClient creates an NTP tunnel client targeting remoteAddr (e.g. server_ip:123)
func NewTunnelClient(remoteAddr string, key []byte) (*TunnelClient, error) {
	if len(key) < 16 {
		return nil, fmt.Errorf("encryption key must be at least 16 bytes")
	}
	raddr, err := net.ResolveUDPAddr("udp4", remoteAddr)
	if err != nil {
		return nil, err
	}
	conn, err := net.DialUDP("udp4", nil, raddr)
	if err != nil {
		return nil, err
	}

	return &TunnelClient{
		RemoteAddr: remoteAddr,
		Key:        key[:16],
		conn:       conn,
	}, nil
}

// Send encapsulates data into a valid RFC 5905 NTP packet with covert extension payload
func (c *TunnelClient) Send(ctx context.Context, sessionID uint32, seq uint32, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	// 1. Build plaintext: Magic(4) + Session(4) + Seq(4) + Data
	raw := make([]byte, 12+len(data))
	copy(raw[0:4], NTPTunnelMagic)
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

	// 3. Assemble standard 48-byte NTP Header (RFC 5905)
	// LI=0, VN=4 (NTP v4), Mode=3 (Client) -> 0x23
	ntpHeader := make([]byte, 48)
	ntpHeader[0] = 0x23 // LI:0, VN:4, Mode:Client(3)
	ntpHeader[1] = 1    // Stratum 1
	ntpHeader[2] = 6    // Poll interval (64s)
	ntpHeader[3] = 0xEC // Precision: -20

	// Transmit Timestamp (seconds since 1900)
	sec, frac := ntpTimestamp(time.Now())
	binary.BigEndian.PutUint32(ntpHeader[40:44], sec)
	binary.BigEndian.PutUint32(ntpHeader[44:48], frac)

	// 4. Append Extension Field (RFC 7822) carrying our encrypted payload
	// Field Type: 0xFD4D (Private/Experimental), Length: 4 + len(payload)
	payload := append(iv, ciphertext...)
	extField := make([]byte, 4+len(payload))
	binary.BigEndian.PutUint16(extField[0:2], 0xFD4D)
	binary.BigEndian.PutUint16(extField[2:4], uint16(len(extField)))
	copy(extField[4:], payload)

	fullPacket := append(ntpHeader, extField...)
	_, err = c.conn.Write(fullPacket)
	return err
}

// Recv receives and decodes a server NTP response containing downlink tunnel data
func (c *TunnelClient) Recv(ctx context.Context, timeout time.Duration) (*Packet, error) {
	buf := make([]byte, 4096)
	_ = c.conn.SetReadDeadline(time.Now().Add(timeout))

	n, err := c.conn.Read(buf)
	if err != nil {
		return nil, err
	}

	if n < 48+4+aes.BlockSize+12 {
		return nil, fmt.Errorf("packet too short for NTP covert frame")
	}

	// Extract payload after 48-byte NTP header and 4-byte extension header
	iv := buf[52 : 52+aes.BlockSize]
	ciphertext := buf[52+aes.BlockSize : n]

	block, err := aes.NewCipher(c.Key)
	if err != nil {
		return nil, err
	}
	stream := cipher.NewCTR(block, iv)
	plaintext := make([]byte, len(ciphertext))
	stream.XORKeyStream(plaintext, ciphertext)

	// Verify Magic
	if string(plaintext[0:4]) != string(NTPTunnelMagic) {
		return nil, fmt.Errorf("invalid NTP tunnel magic signature")
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

// Close closes the UDP socket
func (c *TunnelClient) Close() error {
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}
