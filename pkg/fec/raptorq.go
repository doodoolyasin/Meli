package fec

import (
	"github.com/klauspost/reedsolomon"
	"sync"
)

// Encoder wraps Reed-Solomon as a placeholder for RaptorQ
type Encoder struct {
	enc         reedsolomon.Encoder
	dataShards  int
	parityShards int
	totalShards int
	shards      [][]byte
	mu          sync.Mutex
}

// NewEncoder creates an encoder for dataShards data shards and parityShards parity shards
func NewEncoder(dataShards, parityShards int) (*Encoder, error) {
	enc, err := reedsolomon.New(dataShards, parityShards)
	if err != nil {
		return nil, err
	}
	return &Encoder{
		enc:          enc,
		dataShards:   dataShards,
		parityShards: parityShards,
		totalShards:  dataShards + parityShards,
	}, nil
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
	shardSize := (total + e.dataShards - 1) / e.dataShards // ceil
	e.shards = make([][]byte, e.totalShards)
	for i := 0; i < e.dataShards; i++ {
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
	// parity shards will be filled by Encode
	for i := e.dataShards; i < e.totalShards; i++ {
		e.shards[i] = make([]byte, shardSize)
	}
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
	if len(shards) != e.totalShards {
		return nil, reedsolomon.ErrShortData
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
	for i := 0; i < e.dataShards; i++ {
		if shardCopy[i] != nil {
			out = append(out, shardCopy[i]...)
		}
	}
	return out, nil
}