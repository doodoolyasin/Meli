package raptorq

import (
	"bytes"
	"errors"
	"fmt"
	"sync"

	"github.com/klauspost/reedsolomon"
)

var (
	ErrTooFewSymbols = errors.New("fewer than K symbols available for reconstruction")
	ErrInvalidParams = errors.New("invalid K or repair symbol count")
)

// Encoder implements systematic fountain/erasure coding for DNS tunneling loss resilience.
type Encoder struct {
	k   int // number of source symbols
	r   int // number of repair symbols
	enc reedsolomon.Encoder
	mu  sync.Mutex
}

// NewEncoder creates a new systematic encoder expecting k source symbols and producing r repair symbols.
func NewEncoder(k int, r int) (*Encoder, error) {
	if k <= 0 || r < 0 {
		return nil, ErrInvalidParams
	}
	if r == 0 {
		r = 1 // at least 1 parity shard
	}
	enc, err := reedsolomon.New(k, r)
	if err != nil {
		return nil, fmt.Errorf("failed to init erasure encoder: %w", err)
	}
	return &Encoder{
		k:   k,
		r:   r,
		enc: enc,
	}, nil
}

// Encode takes k source symbols (padded to equal length) and generates r repair symbols.
// Returns a slice of (k + r) symbols where indices [0..k-1] are source and [k..k+r-1] are repair symbols.
func (e *Encoder) Encode(src [][]byte) ([][]byte, error) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if len(src) != e.k {
		return nil, fmt.Errorf("expected %d source symbols, got %d", e.k, len(src))
	}

	// Find max symbol size
	maxLen := 0
	for _, s := range src {
		if len(s) > maxLen {
			maxLen = len(s)
		}
	}
	if maxLen == 0 {
		maxLen = 1
	}

	// Prepare shards with uniform length
	shards := make([][]byte, e.k+e.r)
	for i := 0; i < e.k; i++ {
		shards[i] = make([]byte, maxLen)
		if i < len(src) {
			copy(shards[i], src[i])
		}
	}
	for i := e.k; i < e.k+e.r; i++ {
		shards[i] = make([]byte, maxLen)
	}

	if err := e.enc.Encode(shards); err != nil {
		return nil, fmt.Errorf("encoding failed: %w", err)
	}

	return shards, nil
}

// Decoder reconstructs the original k source symbols from any k of the (k + r) received symbols.
type Decoder struct {
	k   int
	r   int
	enc reedsolomon.Encoder
	mu  sync.Mutex
}

// NewDecoder creates a decoder expecting at least k out of (k + r) symbols.
func NewDecoder(k int, r int) (*Decoder, error) {
	if k <= 0 || r < 0 {
		return nil, ErrInvalidParams
	}
	if r == 0 {
		r = 1
	}
	enc, err := reedsolomon.New(k, r)
	if err != nil {
		return nil, fmt.Errorf("failed to init erasure decoder: %w", err)
	}
	return &Decoder{
		k:   k,
		r:   r,
		enc: enc,
	}, nil
}

// Decode attempts to reconstruct original data from received symbols.
// received must be a slice of size (k + r) where missing symbols are set to nil.
func (d *Decoder) Decode(received [][]byte) ([][]byte, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if len(received) != d.k+d.r {
		return nil, false
	}

	// Count present symbols
	present := 0
	for _, s := range received {
		if len(s) > 0 {
			present++
		}
	}
	if present < d.k {
		return nil, false
	}

	// Make working copy
	working := make([][]byte, len(received))
	copy(working, received)

	if err := d.enc.Reconstruct(working); err != nil {
		return nil, false
	}

	// Return only the k source symbols
	out := make([][]byte, d.k)
	for i := 0; i < d.k; i++ {
		out[i] = bytes.TrimRight(working[i], "\x00")
	}

	return out, true
}