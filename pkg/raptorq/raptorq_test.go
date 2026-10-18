package raptorq

import (
	"bytes"
	"testing"
)

func TestRaptorQEncodeDecode(t *testing.T) {
	k := 4
	r := 2

	enc, err := NewEncoder(k, r)
	if err != nil {
		t.Fatalf("NewEncoder failed: %v", err)
	}

	src := [][]byte{
		[]byte("MeliTunnelChunk01"),
		[]byte("MeliTunnelChunk02"),
		[]byte("MeliTunnelChunk03"),
		[]byte("MeliTunnelChunk04"),
	}

	shards, err := enc.Encode(src)
	if err != nil {
		t.Fatalf("Encode failed: %v", err)
	}

	if len(shards) != k+r {
		t.Fatalf("Expected %d shards, got %d", k+r, len(shards))
	}

	// Simulate losing 2 shards (burst loss of 33%): drop index 1 and index 3
	shards[1] = nil
	shards[3] = nil

	dec, err := NewDecoder(k, r)
	if err != nil {
		t.Fatalf("NewDecoder failed: %v", err)
	}

	reconstructed, ok := dec.Decode(shards)
	if !ok {
		t.Fatalf("Decode reconstruction failed")
	}

	for i := 0; i < k; i++ {
		if !bytes.Equal(reconstructed[i], src[i]) {
			t.Fatalf("Shard %d mismatch: got %q, want %q", i, reconstructed[i], src[i])
		}
	}
}