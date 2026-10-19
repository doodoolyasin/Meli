package fcm

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

// Standard Google Cloud Messaging / FCM TCP ports left open by mobile carriers
// to allow domestic banking 2FA notifications and Android push alerts.
var FCMPorts = []int{5228, 5229, 5230}

// Magic header identifying Meli FCM frames
var FCMMagic = []byte{0x46, 0x43, 0x4D, 0x01} // "FCM\x01"

// TunnelClient connects to server on FCM ports (5228-5230) with Protobuf-like handshake
type TunnelClient struct {
	RemoteAddr string
	Key        []byte
	conn       net.Conn
	mu         sync.Mutex
}

// NewTunnelClient connects to remote server using FCM ports (5228-5230)
func NewTunnelClient(host string, port int, key []byte) (*TunnelClient, error) {
	if len(key) < 16 {
		return nil, fmt.Errorf("encryption key must be at least 16 bytes")
	}
	target := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	conn, err := net.DialTimeout("tcp", target, 5*time.Second)
	if err != nil {
		return nil, err
	}

	return &TunnelClient{
		RemoteAddr: target,
		Key:        key[:16],
		conn:       conn,
	}, nil
}

// Send writes an encrypted payload camouflaged with Google Play / FCM push framing
func (c *TunnelClient) Send(ctx context.Context, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	block, err := aes.NewCipher(c.Key)
	if err != nil {
		return err
	}
	iv := make([]byte, aes.BlockSize)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return err
	}
	stream := cipher.NewCTR(block, iv)
	ciphertext := make([]byte, len(data))
	stream.XORKeyStream(ciphertext, data)

	// Google FCM packet framing: [Protobuf Tag 0x0A (DataTag)] + [Varint Length] + [Payload]
	payload := append(iv, ciphertext...)
	buf := make([]byte, 0, len(payload)+10)
	buf = append(buf, 0x0A) // FCM Data Tag
	buf = append(buf, FCMMagic...)

	// 4-byte length
	lenBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(lenBytes, uint32(len(payload)))
	buf = append(buf, lenBytes...)
	buf = append(buf, payload...)

	_, err = c.conn.Write(buf)
	return err
}

// Recv reads an encrypted response frame from the FCM tunnel
func (c *TunnelClient) Recv(ctx context.Context) ([]byte, error) {
	header := make([]byte, 9) // 0x0A (1) + FCMMagic (4) + Length (4)
	if _, err := io.ReadFull(c.conn, header); err != nil {
		return nil, err
	}

	if header[0] != 0x0A || string(header[1:5]) != string(FCMMagic) {
		return nil, fmt.Errorf("invalid FCM push frame signature")
	}

	length := binary.BigEndian.Uint32(header[5:9])
	if length < aes.BlockSize || length > 1048576 {
		return nil, fmt.Errorf("invalid FCM frame length: %d", length)
	}

	raw := make([]byte, length)
	if _, err := io.ReadFull(c.conn, raw); err != nil {
		return nil, err
	}

	iv := raw[:aes.BlockSize]
	ciphertext := raw[aes.BlockSize:]

	block, err := aes.NewCipher(c.Key)
	if err != nil {
		return nil, err
	}
	stream := cipher.NewCTR(block, iv)
	plaintext := make([]byte, len(ciphertext))
	stream.XORKeyStream(plaintext, ciphertext)

	return plaintext, nil
}

// Close closes the TCP connection
func (c *TunnelClient) Close() error {
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}
