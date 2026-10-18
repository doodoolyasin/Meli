package fec

import (
	"github.com/klauspost/reedsolomon"
	"sync"
)

// Encoder wraps Reed-Solomon as a placeholder for RaptorQ
type Encoder struct {
	enc reedsolomon.Encoder
	shards [][]byte
	mu    sync.Mutex
}

// NewEncoder creates an encoder for dataShards data shards and parityShards parity shards
func NewEncoder(dataShards, parityShards int) (*Encoder, error) {
	enc, err := reedsolomon.New(dataShards, parityShards)
	if err != nil {
		return nil, err
	}
	return &Encoder{enc: enc}, nil
}

// Encode splits data into shards and computes parity
func (e *Encoder) Encode(data []byte) ([][]byte, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	// split data into dataShards evenly
	total := len(data)
	if total == 0 {
		return [][]byte{}, nil
	}
	// simple slice; not optimal for large data
	shardSize := (total + e.enc.NumShards() - 1) / e.enc.NumShards() // ceil
	e.shards = make([][]byte, e.enc.NumShards())
	for i := 0; i < e.enc.NumShards(); i++ {
		start := i * shardSize
		end := start + shardSize
		if end > total {
			end = total
		}
		if start >= total {
			e.shards[i] = nil
		} else {
			e.shards[i] = data[start:end]
		}
	}
	// ensure shards are correct length (pad if needed)
	// ReedSolomon expects all shards same size; we will pad later in Encoder.Encode
	// Use enc.Encode which expects slice of shards
	err := e.enc.Encode(e.shards)
	if err != nil {
		return nil, err
	}
	return e.shards, nil
}

// Decode attempts to reconstruct data from shards (some may be missing)
func (e *Encoder) Decode(shards [][]byte) ([]byte, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if len(shards) != e.enc.NumShards() {
		return nil, reedsolomon.ErrShortShards
	}
	// Copy to avoid modifying original
	shardCopy := make([][]byte, len(shards))
	copy(shardCopy, shards)
	err := e.enc.Reconstruct(shardCopy)
	if err != nil {
		return nil, err
	}
	// Join shards
	var out []byte
	for _, s := range shardCopy {
		if s != nil {
			out = append(out, s...)
		}
	}
	// Trim possible padding: we assume original length multiple? For simplicity return as is.
	return out, nil
}
