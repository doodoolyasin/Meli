package proto

import (
	"bytes"
	"compress/flate"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"io"
	"sync"
	"time"
)

const (
	MagicByte = 0x4D // 'M' for Meli
	HeaderLen = 7    // Magic(1) + SessionID(1) + Seq(2) + Frag(1) + Tot(1) + Flags(1)
)

var (
	ErrInvalidMagic = errors.New("invalid magic byte")
	ErrShortPacket  = errors.New("packet too short")
)

// Frame represents a single DNS tunnel fragment.
type Frame struct {
	SessionID uint8
	Seq       uint16
	Frag      uint8
	Tot       uint8
	Flags     uint8
	Payload   []byte
}

// Pack serializes a Frame into bytes.
func (f *Frame) Pack() []byte {
	buf := make([]byte, HeaderLen+len(f.Payload))
	buf[0] = MagicByte
	buf[1] = f.SessionID
	binary.BigEndian.PutUint16(buf[2:4], f.Seq)
	buf[4] = f.Frag
	buf[5] = f.Tot
	buf[6] = f.Flags
	copy(buf[HeaderLen:], f.Payload)
	return buf
}

// Unpack deserializes bytes into a Frame.
func Unpack(data []byte) (*Frame, error) {
	if len(data) < HeaderLen {
		return nil, ErrShortPacket
	}
	if data[0] != MagicByte {
		return nil, ErrInvalidMagic
	}
	f := &Frame{
		SessionID: data[1],
		Seq:       binary.BigEndian.Uint16(data[2:4]),
		Frag:      data[4],
		Tot:       data[5],
		Flags:     data[6],
		Payload:   data[HeaderLen:],
	}
	return f, nil
}

// Compress deflates data using raw deflate (no headers, zero overhead).
func Compress(data []byte) []byte {
	if len(data) == 0 {
		return data
	}
	var b bytes.Buffer
	w, err := flate.NewWriter(&b, flate.BestSpeed)
	if err != nil {
		return data
	}
	_, _ = w.Write(data)
	_ = w.Close()
	// Only return compressed if it actually saved space
	if b.Len() < len(data) {
		return b.Bytes()
	}
	return data
}

// Decompress inflates raw deflate data.
func Decompress(data []byte) ([]byte, error) {
	if len(data) == 0 {
		return data, nil
	}
	r := flate.NewReader(bytes.NewReader(data))
	defer r.Close()
	var b bytes.Buffer
	_, err := io.Copy(&b, r)
	if err != nil {
		// If decompression fails, it might be uncompressed raw data
		return data, nil
	}
	return b.Bytes(), nil
}

// DeriveIV generates a deterministic 16-byte IV from session and sequence info.
func DeriveIV(sessionID uint8, seq uint16, frag uint8) []byte {
	iv := make([]byte, 16)
	iv[0] = MagicByte
	iv[1] = sessionID
	binary.BigEndian.PutUint16(iv[2:4], seq)
	iv[4] = frag
	for i := 5; i < 16; i++ {
		iv[i] = byte(i * 17)
	}
	return iv
}

// CryptAESCTR encrypts or decrypts in-place with AES-CTR using key and derived IV.
func CryptAESCTR(key []byte, iv []byte, src []byte) ([]byte, error) {
	// Pad or truncate key to 16, 24, or 32 bytes
	var aesKey []byte
	if len(key) >= 32 {
		aesKey = key[:32]
	} else if len(key) >= 16 {
		aesKey = key[:16]
	} else {
		aesKey = make([]byte, 16)
		copy(aesKey, key)
	}

	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}

	stream := cipher.NewCTR(block, iv)
	dst := make([]byte, len(src))
	stream.XORKeyStream(dst, src)
	return dst, nil
}

// ReassemblyBuffer handles fragment collection and packet reassembly.
type ReassemblyBuffer struct {
	mu       sync.Mutex
	sessions map[uint32]*messageAssembler
}

type messageAssembler struct {
	tot       uint8
	frags     map[uint8][]byte
	createdAt time.Time
}

func NewReassemblyBuffer() *ReassemblyBuffer {
	rb := &ReassemblyBuffer{
		sessions: make(map[uint32]*messageAssembler),
	}
	go rb.gc()
	return rb
}

func (rb *ReassemblyBuffer) gc() {
	ticker := time.NewTicker(5 * time.Second)
	for range ticker.C {
		rb.mu.Lock()
		now := time.Now()
		for k, v := range rb.sessions {
			if now.Sub(v.createdAt) > 10*time.Second {
				delete(rb.sessions, k)
			}
		}
		rb.mu.Unlock()
	}
}

// Add adds a fragment and returns the complete reassembled message if all fragments arrived.
func (rb *ReassemblyBuffer) Add(f *Frame) []byte {
	if f.Tot <= 1 {
		return f.Payload
	}

	key := (uint32(f.SessionID) << 16) | uint32(f.Seq)

	rb.mu.Lock()
	defer rb.mu.Unlock()

	asm, exists := rb.sessions[key]
	if !exists {
		asm = &messageAssembler{
			tot:       f.Tot,
			frags:     make(map[uint8][]byte),
			createdAt: time.Now(),
		}
		rb.sessions[key] = asm
	}

	asm.frags[f.Frag] = append([]byte(nil), f.Payload...)

	if len(asm.frags) == int(asm.tot) {
		delete(rb.sessions, key)
		var full []byte
		for i := uint8(0); i < asm.tot; i++ {
			full = append(full, asm.frags[i]...)
		}
		return full
	}

	return nil
}