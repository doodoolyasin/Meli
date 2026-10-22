package pacer

import (
	"sync"
	"testing"
	"time"
)

func TestLeakyBucketRateLimiting(t *testing.T) {
	bucket := NewLeakyBucket(100, 10) // 100 tokens/sec, capacity 10

	// Consume capacity immediately
	for i := 0; i < 10; i++ {
		if !bucket.Allow(1) {
			t.Fatalf("expected immediate capacity allow for token %d", i)
		}
	}

	// Next token should be rejected until refill
	if bucket.Allow(1) {
		t.Fatalf("expected exhaustion rejection after consuming capacity")
	}

	// Sleep 25ms -> ~2.5 tokens refilled
	time.Sleep(25 * time.Millisecond)
	if !bucket.Allow(1) {
		t.Fatalf("expected refilled tokens to allow consumption")
	}
}

func TestLeakyBucketConcurrentSleepUntilSend(t *testing.T) {
	bucket := NewLeakyBucket(1000, 50)
	var wg sync.WaitGroup
	const waiters = 10

	for i := 0; i < waiters; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			bucket.SleepUntilSend(5)
		}()
	}

	wg.Wait()
}
