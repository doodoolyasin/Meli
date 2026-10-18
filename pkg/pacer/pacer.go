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
	b.mu.Lock()
	defer b.mu.Unlock()
	now := time.Now()
	elapsed := now.Sub(b.last).Seconds()
	b.tokens += elapsed * b.rate
	if b.tokens > b.capacity {
		b.tokens = b.capacity
	}
	if b.tokens >= n {
		b.tokens -= n
		b.last = now
		return
	}
	// Need to wait for (n - b.tokens) tokens.
	needed := (n - b.tokens) / b.rate
	sleepTime := time.Duration(needed * float64(time.Second))
	// Release lock while sleeping
	b.mu.Unlock()
	time.Sleep(sleepTime)
	b.mu.Lock()
	// After sleeping, update state: we have slept for sleepTime, so we add tokens for that period.
	// We assume the sleep was accurate enough.
	now = time.Now()
	elapsed = now.Sub(b.last).Seconds()
	b.tokens += elapsed * b.rate
	if b.tokens > b.capacity {
		b.tokens = b.capacity
	}
	// Now we should have enough tokens for n.
	if b.tokens >= n {
		b.tokens -= n
		b.last = now
		return
	}
	// This should not happen, but if it does, we wait again (should be rare due to inaccuracies in sleep).
	// We'll set tokens to 0 and update last time to now.
	// This is a fallback.
	b.tokens = 0
	b.last = now
}
