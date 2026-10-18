package proto

import (
	"bytes"
	"testing"
)

func TestProtoPackUnpack(t *testing.T) {
	orig := &Frame{
		SessionID: 42,
		Seq:       1024,
		Frag:      0,
		Tot:       2,
		Flags:     0x01,
		Payload:   []byte("hello dns tunnel world"),
	}

	packed := orig.Pack()
	unpacked, err := Unpack(packed)
	if err != nil {
		t.Fatalf("Unpack failed: %v", err)
	}

	if unpacked.SessionID != orig.SessionID || unpacked.Seq != orig.Seq || unpacked.Frag != orig.Frag || unpacked.Tot != orig.Tot {
		t.Fatalf("Mismatch: %+v != %+v", unpacked, orig)
	}

	if !bytes.Equal(unpacked.Payload, orig.Payload) {
		t.Fatalf("Payload mismatch")
	}
}

func TestProtoCompressionAndEncryption(t *testing.T) {
	key := []byte("137e43fd04a5b9dfdaf29e103fddcce2")
	original := bytes.Repeat([]byte("super secret data payload that should compress well "), 10)

	compressed := Compress(original)
	if len(compressed) >= len(original) {
		t.Fatalf("Compression failed to reduce size: %d vs %d", len(compressed), len(original))
	}

	iv := DeriveIV(1, 100, 0)
	encrypted, err := CryptAESCTR(key, iv, compressed)
	if err != nil {
		t.Fatalf("Crypt failed: %v", err)
	}

	// Decrypt
	decrypted, err := CryptAESCTR(key, iv, encrypted)
	if err != nil {
		t.Fatalf("Decrypt failed: %v", err)
	}

	decompressed, err := Decompress(decrypted)
	if err != nil {
		t.Fatalf("Decompress failed: %v", err)
	}

	if !bytes.Equal(original, decompressed) {
		t.Fatalf("Round-trip failed")
	}
}

func TestReassemblyBuffer(t *testing.T) {
	rb := NewReassemblyBuffer()
	payload1 := []byte("Part1-")
	payload2 := []byte("Part2-")
	payload3 := []byte("Part3")

	f1 := &Frame{SessionID: 1, Seq: 50, Frag: 0, Tot: 3, Payload: payload1}
	f2 := &Frame{SessionID: 1, Seq: 50, Frag: 1, Tot: 3, Payload: payload2}
	f3 := &Frame{SessionID: 1, Seq: 50, Frag: 2, Tot: 3, Payload: payload3}

	if res := rb.Add(f2); res != nil {
		t.Fatalf("Premature reassembly")
	}
	if res := rb.Add(f1); res != nil {
		t.Fatalf("Premature reassembly")
	}
	res := rb.Add(f3)
	if res == nil {
		t.Fatalf("Failed to reassemble")
	}

	expected := "Part1-Part2-Part3"
	if string(res) != expected {
		t.Fatalf("Expected %q, got %q", expected, string(res))
	}
}