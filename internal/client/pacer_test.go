package client

import (
	"testing"
	"time"
)

// TestPacerDisabledByDefault guards the hot path: with no pacer configured the
// writer must not pay any per-datagram delay.
func TestPacerDisabledByDefault(t *testing.T) {
	c := &Client{}

	start := time.Now()
	for i := 0; i < 2000; i++ {
		c.paceOutbound(1500)
	}
	if elapsed := time.Since(start); elapsed > 100*time.Millisecond {
		t.Fatalf("unpaced egress took %s for 2000 datagrams", elapsed)
	}
}

// TestPacerLimitsAggregateRate verifies that the bucket is shared and actually
// throttles: 20 KB/s with a 40 KB burst admits the burst immediately and then
// makes the next 20 KB wait about a second.
func TestPacerLimitsAggregateRate(t *testing.T) {
	c := &Client{}
	c.SetPacerBytesPerSecond(20000)

	start := time.Now()
	for i := 0; i < 3; i++ {
		c.paceOutbound(20000)
	}
	elapsed := time.Since(start)

	if elapsed < 800*time.Millisecond {
		t.Fatalf("pacer admitted 60 KB in %s at 20 KB/s", elapsed)
	}
	if elapsed > 3*time.Second {
		t.Fatalf("pacer overslept: %s", elapsed)
	}

	c.SetPacerBytesPerSecond(0)
	start = time.Now()
	c.paceOutbound(1 << 20)
	if elapsed := time.Since(start); elapsed > 50*time.Millisecond {
		t.Fatalf("pacer still throttles after being disabled: %s", elapsed)
	}
}

// TestPacerSharedAcrossWorkers models multiple writer workers drawing from the
// same bucket: the configured rate is an aggregate limit, not per worker.
func TestPacerSharedAcrossWorkers(t *testing.T) {
	c := &Client{}
	c.SetPacerBytesPerSecond(10000)

	done := make(chan time.Duration, 2)
	start := time.Now()
	for i := 0; i < 2; i++ {
		go func() {
			for j := 0; j < 2; j++ {
				c.paceOutbound(10000)
			}
			done <- time.Since(start)
		}()
	}
	first, second := <-done, <-done

	// 40 KB at 10 KB/s: 20 KB burst plus ~2s of waiting.
	slowest := first
	if second > slowest {
		slowest = second
	}
	if slowest < 1500*time.Millisecond {
		t.Fatalf("two workers drained 40 KB in %s at a 10 KB/s aggregate limit", slowest)
	}
}
