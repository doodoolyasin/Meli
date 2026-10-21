package client

import (
	"fmt"
	"testing"
	"time"
)

func noopRespond([]byte) {}

func TestDNSWaitersTakeReturnsEachWaiterOnce(t *testing.T) {
	w := newDNSWaiters()
	now := time.Now()

	if !w.add("key", []byte{0x12, 0x34}, noopRespond, now, 5*time.Second) {
		t.Fatal("add rejected a valid waiter")
	}
	if got := w.len(); got != 1 {
		t.Fatalf("len = %d, want 1", got)
	}

	got := w.take("key")
	if len(got) != 1 {
		t.Fatalf("take returned %d waiters, want 1", len(got))
	}
	if string(got[0].query) != string([]byte{0x12, 0x34}) {
		t.Fatalf("waiter query = %x, want 1234", got[0].query)
	}
	if w.len() != 0 {
		t.Fatalf("registry still holds %d waiters after take", w.len())
	}
	if again := w.take("key"); again != nil {
		t.Fatalf("second take returned %d waiters", len(again))
	}
}

func TestDNSWaitersPerKeyBound(t *testing.T) {
	w := newDNSWaiters()
	now := time.Now()

	total := maxDNSWaitersPerKey + 8
	for i := 0; i < total; i++ {
		query := []byte{byte(i)}
		if !w.add("same", query, noopRespond, now, 5*time.Second) {
			t.Fatalf("add %d rejected", i)
		}
	}

	got := w.take("same")
	if len(got) != maxDNSWaitersPerKey {
		t.Fatalf("kept %d waiters, want the %d newest", len(got), maxDNSWaitersPerKey)
	}
	// The oldest entries must be the ones dropped.
	if got[0].query[0] != byte(total-maxDNSWaitersPerKey) {
		t.Fatalf("oldest kept waiter = %d, want %d", got[0].query[0], total-maxDNSWaitersPerKey)
	}
	if got[len(got)-1].query[0] != byte(total-1) {
		t.Fatalf("newest kept waiter = %d, want %d", got[len(got)-1].query[0], total-1)
	}
}

func TestDNSWaitersGlobalBound(t *testing.T) {
	w := newDNSWaiters()
	now := time.Now()

	for i := 0; i < maxDNSWaitersTotal+50; i++ {
		w.add(fmt.Sprintf("key-%d", i), []byte{byte(i)}, noopRespond, now, 5*time.Second)
	}
	if got := w.len(); got > maxDNSWaitersTotal {
		t.Fatalf("registry holds %d waiters, want <= %d", got, maxDNSWaitersTotal)
	}
}

func TestDNSWaitersPurgeDropsExpired(t *testing.T) {
	w := newDNSWaiters()
	now := time.Now()

	w.add("short", []byte{1}, noopRespond, now, 2*time.Second)
	w.add("long", []byte{2}, noopRespond, now, maxDNSWaiterTTL)

	w.mu.Lock()
	w.purgeLocked(now.Add(3 * time.Second))
	w.mu.Unlock()

	if got := w.take("short"); got != nil {
		t.Fatalf("expired waiter survived the purge: %d entries", len(got))
	}
	if got := w.take("long"); len(got) != 1 {
		t.Fatalf("live waiter was purged: %d entries", len(got))
	}
}

func TestDNSWaitersTTLClamping(t *testing.T) {
	w := newDNSWaiters()
	now := time.Now()

	w.add("default", []byte{1}, noopRespond, now, 0)
	w.add("huge", []byte{2}, noopRespond, now, time.Hour)
	w.add("tiny", []byte{3}, noopRespond, now, time.Millisecond)

	w.mu.Lock()
	defer w.mu.Unlock()
	for _, tc := range []struct {
		key  string
		want time.Duration
	}{
		{"default", defaultDNSWaiterTTL},
		{"huge", maxDNSWaiterTTL},
		{"tiny", minDNSWaiterTTL},
	} {
		list := w.byKey[tc.key]
		if len(list) != 1 {
			t.Fatalf("%s: %d waiters", tc.key, len(list))
		}
		if got := list[0].expires.Sub(now); got != tc.want {
			t.Fatalf("%s: ttl = %s, want %s", tc.key, got, tc.want)
		}
	}
}

func TestDNSWaitersRejectsInvalidInput(t *testing.T) {
	w := newDNSWaiters()
	if w.add("", []byte{1}, noopRespond, time.Now(), time.Second) {
		t.Fatal("add accepted an empty key")
	}
	if w.add("k", []byte{1}, nil, time.Now(), time.Second) {
		t.Fatal("add accepted a nil callback")
	}
	if got := w.take(""); got != nil {
		t.Fatal("take returned waiters for an empty key")
	}
}
