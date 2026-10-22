package fec

import (
	"bytes"
	"testing"
)

func TestEncoderNonMultipleSize(t *testing.T) {
	enc, err := NewEncoder(4, 2)
	if err != nil {
		t.Fatalf("NewEncoder failed: %v", err)
	}

	// 101 bytes is not divisible by 4 (shardSize = (101+3)/4 = 26)
	data := make([]byte, 101)
	for i := range data {
		data[i] = byte(i)
	}

	shards, err := enc.Encode(data)
	if err != nil {
		t.Fatalf("Encode failed on non-multiple size: %v", err)
	}

	// Simulate losing 1 data shard
	shards[1] = nil

	decoded, err := enc.Decode(shards)
	if err != nil {
		t.Fatalf("Decode failed: %v", err)
	}

	if !bytes.Equal(decoded[:len(data)], data) {
		t.Fatalf("decoded data mismatch!")
	}
}
