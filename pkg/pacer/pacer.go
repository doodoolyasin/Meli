package pacer

import (
	"sync"
	"time"
)

// LeakyBucket implements a simple leaky bucket rate limiter with microsecond precision
type LeakyBucket struct {
	rate     float64 // tokens per second
	capacity float64 // max tokens
	tokens   float64
	last     time.Time
	mu       sync.Mutex
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

// SleepUntilSend blocks until the bucket admits n tokens, then deducts them.
//
// It only returns once the tokens were actually taken. The previous version
// slept once and, if the wake-up was still short (which happens as soon as two
// waiters share a bucket), fell through without deducting anything — so a
// concurrent caller was admitted for free and the aggregate rate exceeded the
// configured one.
func (b *LeakyBucket) SleepUntilSend(n float64) {
	if b == nil || n <= 0 || b.rate <= 0 || b.capacity <= 0 {
		return
	}
	if n > b.capacity {
		// A single write larger than the whole bucket could never be admitted;
		// charge the bucket's worth instead of blocking forever.
		n = b.capacity
	}

	for {
		b.mu.Lock()
		now := time.Now()
		elapsed := now.Sub(b.last).Seconds()
		if elapsed > 0 {
			b.tokens = min(b.capacity, b.tokens+elapsed*b.rate)
			b.last = now
		}
		if b.tokens >= n {
			b.tokens -= n
			b.mu.Unlock()
			return
		}
		needed := (n - b.tokens) / b.rate
		b.mu.Unlock()

		// Never hold the mutex while sleeping: other writers must be able to
		// observe the bucket and queue behind the same budget.
		time.Sleep(time.Duration(needed * float64(time.Second)))
	}
}
