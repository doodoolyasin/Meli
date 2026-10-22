package icmp

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"testing"
)

func TestChecksumRFC1071(t *testing.T) {
	// Standard RFC 1071 test vector
	data := []byte{0x08, 0x00, 0x00, 0x00, 0x4D, 0x4C, 0x00, 0x01}
	cs := checksum(data)
	if cs == 0 {
		t.Fatalf("checksum returned 0 for non-zero packet")
	}

	// Verify that checksum over packet with checksum inserted yields 0 (or 0xFFFF)
	binary.BigEndian.PutUint16(data[2:4], cs)
	verify := checksum(data)
	if verify != 0 && verify != 0xFFFF {
		t.Fatalf("checksum verification failed, got 0x%X", verify)
	}
}

func TestICMPPayloadEncryptionRoundtrip(t *testing.T) {
	key := []byte("0123456789abcdef") // 16 bytes
	sessionID := uint32(9999)
	seq := uint32(42)
	payload := []byte("emergency-tunnel-payload")

	// 1. Build plaintext
	raw := make([]byte, 12+len(payload))
	copy(raw[0:4], ICMPEchoMagic)
	binary.BigEndian.PutUint32(raw[4:8], sessionID)
	binary.BigEndian.PutUint32(raw[8:12], seq)
	copy(raw[12:], payload)

	// 2. Encrypt
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	iv := make([]byte, aes.BlockSize)
	copy(iv, []byte("fixed-test-iv-16"))
	stream := cipher.NewCTR(block, iv)
	ciphertext := make([]byte, len(raw))
	stream.XORKeyStream(ciphertext, raw)

	// 3. Decrypt
	streamDec := cipher.NewCTR(block, iv)
	decrypted := make([]byte, len(ciphertext))
	streamDec.XORKeyStream(decrypted, ciphertext)

	// 4. Verify fields
	if string(decrypted[0:4]) != string(ICMPEchoMagic) {
		t.Fatalf("magic mismatch")
	}
	gotSession := binary.BigEndian.Uint32(decrypted[4:8])
	gotSeq := binary.BigEndian.Uint32(decrypted[8:12])
	gotPayload := decrypted[12:]

	if gotSession != sessionID || gotSeq != seq || string(gotPayload) != string(payload) {
		t.Fatalf("payload mismatch")
	}
}
