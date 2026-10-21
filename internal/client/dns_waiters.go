package client

import (
	"sync"
	"time"
)

const (
	// defaultDNSWaiterTTL is how long a stub resolver's callback is kept while
	// the tunnel resolves a cold name. Most stubs retry after 2–5s, so keeping
	// waiters much longer only retains memory for clients that already gave up.
	defaultDNSWaiterTTL = 10 * time.Second
	minDNSWaiterTTL     = 2 * time.Second
	maxDNSWaiterTTL     = 30 * time.Second

	// maxDNSWaitersPerKey bounds the retries a single name can queue, and
	// maxDNSWaitersTotal bounds the whole registry. Both exist because queries
	// arrive from any local process: without limits a flood of distinct names
	// would pin one closure per query for the whole TTL.
	maxDNSWaitersPerKey = 16
	maxDNSWaitersTotal  = 2048
)

// dnsWaiter holds one unanswered client query.
type dnsWaiter struct {
	query   []byte
	respond func([]byte)
	expires time.Time
}

// dnsWaiters maps a cache key (qtype|qclass|name) to the clients waiting for
// that answer.
//
// The local DNS proxy used to be fire-and-forget: a cold query was dispatched
// into the tunnel and the callback was thrown away, so the stub never received
// a reply and had to time out before its retry happened to hit the cache. This
// registry keeps the callback alive until the answer is assembled.
type dnsWaiters struct {
	mu    sync.Mutex
	byKey map[string][]dnsWaiter
	total int
	adds  uint64
}

func newDNSWaiters() *dnsWaiters {
	return &dnsWaiters{byKey: make(map[string][]dnsWaiter)}
}

// add registers a client callback for key. It returns false when the
// registration could not be stored (empty key or nil callback).
func (w *dnsWaiters) add(key string, query []byte, respond func([]byte), now time.Time, ttl time.Duration) bool {
	if w == nil || key == "" || respond == nil {
		return false
	}
	if ttl <= 0 {
		ttl = defaultDNSWaiterTTL
	}
	if ttl < minDNSWaiterTTL {
		ttl = minDNSWaiterTTL
	}
	if ttl > maxDNSWaiterTTL {
		ttl = maxDNSWaiterTTL
	}

	w.mu.Lock()
	defer w.mu.Unlock()

	if w.byKey == nil {
		w.byKey = make(map[string][]dnsWaiter)
	}
	w.adds++
	if w.adds%64 == 0 {
		w.purgeLocked(now)
	}

	list := w.byKey[key]
	if len(list) >= maxDNSWaitersPerKey {
		// Drop the oldest waiter: its stub has most likely already retried.
		list = list[1:]
		if w.total > 0 {
			w.total--
		}
	}
	list = append(list, dnsWaiter{
		query:   append([]byte(nil), query...),
		respond: respond,
		expires: now.Add(ttl),
	})
	w.byKey[key] = list
	w.total++

	if w.total > maxDNSWaitersTotal {
		w.evictLocked(now)
	}
	return true
}

// take removes and returns every waiter registered for key.
func (w *dnsWaiters) take(key string) []dnsWaiter {
	if w == nil || key == "" {
		return nil
	}
	w.mu.Lock()
	defer w.mu.Unlock()

	list := w.byKey[key]
	if len(list) == 0 {
		return nil
	}
	delete(w.byKey, key)
	w.total -= len(list)
	if w.total < 0 {
		w.total = 0
	}
	out := make([]dnsWaiter, len(list))
	copy(out, list)
	return out
}

func (w *dnsWaiters) len() int {
	if w == nil {
		return 0
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.total
}

func (w *dnsWaiters) purgeLocked(now time.Time) {
	for key, list := range w.byKey {
		kept := list[:0]
		for _, waiter := range list {
			if now.After(waiter.expires) {
				continue
			}
			kept = append(kept, waiter)
		}
		if len(kept) == 0 {
			delete(w.byKey, key)
			continue
		}
		w.byKey[key] = kept
	}
	w.total = 0
	for _, list := range w.byKey {
		w.total += len(list)
	}
}

// evictLocked frees space once the registry passes its global bound. Expired
// entries go first; if the registry is still full, whole keys are dropped in
// map order. Dropping a waiter only means that stub gets no reply for this
// attempt — it retries, and the answer is in the cache by then.
func (w *dnsWaiters) evictLocked(now time.Time) {
	w.purgeLocked(now)
	for key, list := range w.byKey {
		if w.total <= maxDNSWaitersTotal {
			return
		}
		delete(w.byKey, key)
		w.total -= len(list)
	}
	if w.total < 0 {
		w.total = 0
	}
}
