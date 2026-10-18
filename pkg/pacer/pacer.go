package pacer

import (
	"sync"
	"time"
)

// LeakyBucket implements a simple leaky bucket rate limiter with microsecond precision
type LeakyBucket struct {
	rate      float64 // tokens per second
	capacity  float64 // max tokens
	tokens    float64
	last      time.Time
	mu        sync.Mutex
}

// NewLeakyBucket creates a bucket that leaks at rate tokens/sec with given capacity
func NewLeakyBucket(rate float64, capacity float64) *LeakyBucket {
	return &LeakyBucket{
		rate:     rate,
		capacity: capacity,
		tokens:   capacity,
		last:     time.Now(),
	}
}

// Allow returns true if n tokens can be consumed (i.e., packet can be sent)
func (b *LeakyBucket) Allow(n float64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	now := time.Now()
	elapsed := now.Sub(b.last).Seconds()
	b.tokens += elapsed * b.rate
	if b.tokens > b.capacity {
		b.tokens = b.capacity
	}
	b.last = now
	if b.tokens >= n {
		b.tokens -= n
		return true
	}
	return false
}

// SleepUntilSend blocks until bucket allows n tokens
func (b *LeakyBucket) SleepUntilSend(n float64) {
	for !b.Allow(n) {
		// compute time needed for one token
		time.Sleep(time.Microsecond * 100)
	}
}
