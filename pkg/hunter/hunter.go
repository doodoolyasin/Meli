package hunter

import (
	"context"
	"net"
	"sort"
	"sync"
	"time"
)

// ResolverInfo holds info about a DNS resolver
type ResolverInfo struct {
	IP      net.IP
	RTT     time.Duration
	Loss    float64 // 0.0-1.0
	LastSeen time.Time
}

// ResolverHunter continuously probes a list of resolvers and keeps the best ones
type ResolverHunter struct {
	resolvers []*ResolverInfo
	mu        sync.RWMutex
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
}

// NewResolverHunter creates a hunter for the given IPs
func NewResolverHunter(ips []net.IP) *ResolverHunter {
	ctx, cancel := context.WithCancel(context.Background())
	h := &ResolverHunter{
		resolvers: make([]*ResolverInfo, len(ips)),
		ctx:       ctx,
		cancel:    cancel,
	}
	for i, ip := range ips {
		h.resolvers[i] = &ResolverInfo{IP: ip}
	}
	return h
}

// Start launches background probing goroutines
func (h *ResolverHunter) Start() {
	h.wg.Add(len(h.resolvers))
	for i := range h.resolvers {
		go h.probe(i)
	}
}

// Stop stops the hunter
func (h *ResolverHunter) Stop() {
	h.cancel()
	h.wg.Wait()
}

// Best returns the resolver with lowest RTT (and lowest loss as tie-breaker)
func (h *ResolverHunter) Best() net.IP {
	h.mu.RLock()
	defer h.mu.RUnlock()
	var best *ResolverInfo
	for _, r := range h.resolvers {
		if r.IP == nil {
			continue
		}
		if best == nil || r.RTT < best.RTT || (r.RTT == best.RTT && r.Loss < best.Loss) {
			best = r
		}
	}
	if best == nil {
		return nil
	}
	return best.IP
}

// probe continuously measures a single resolver
func (h *ResolverHunter) probe(idx int) {
	defer h.wg.Done()
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-h.ctx.Done():
			return
		case <-ticker.C:
			r := h.resolvers[idx]
			start := time.Now()
			// Try to send a DNS query (dummy) and measure RTT
			conn, err := net.DialTimeout("udp4", net.JoinHostPort(r.IP.String(), "53"), 2*time.Second)
			if err != nil {
				// treat as loss
				h.mu.Lock()
				r.Loss = 1.0
				r.RTT = 0
				h.mu.Unlock()
				continue
			}
			conn.Close()
			rtt := time.Since(start)
			h.mu.Lock()
			r.RTT = rtt
			// simple loss estimator: if we succeed, decrease loss
			if r.Loss > 0 {
				r.Loss *= 0.9
			} else {
				r.Loss = 0
			}
			r.LastSeen = time.Now()
			h.mu.Unlock()
		}
	}
}
