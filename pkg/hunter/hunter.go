package hunter

import (
	"context"
	"fmt"
	"math/rand"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
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

// init seeds the random number generator.
func init() {
	rand.Seed(time.Now().UnixNano())
}

// NewResolverHunter creates a hunter that loads resolvers from the default file.
func NewResolverHunter() (*ResolverHunter, error) {
	ips, err := LoadResolversFromFile("/root/meli-work/pkg/hunter/data/resolvers.txt")
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	h := &ResolverHunter{
		resolvers: make([]*ResolverInfo, len(ips)),
		ctx:       ctx,
		cancel:    cancel,
	}
	for i, ip := range ips {
		h.resolvers[i] = &ResolverInfo{IP: ip}
	}
	return h, nil
}

// LoadResolversFromFile reads the file and returns a list of net.IP.
func LoadResolversFromFile(filename string) ([]net.IP, error) {
	data, err := os.ReadFile(filename)
	if err != nil {
		return nil, err
	}
	var ips []net.IP
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || line[0] == '#' {
			continue
		}
		ip := net.ParseIP(line)
		if ip == nil {
			continue
		}
		ips = append(ips, ip)
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("no valid IPs found in %s", filename)
	}
	return ips, nil
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
			// Build a simple DNS query for example.com, type A
			q := dnsmessage.Message{
				Header: dnsmessage.Header{
					ID:   uint16(rand.Uint32()), // random ID
					RecursionDesired: true,
				},
				Questions: []dnsmessage.Question{
					{
						Name:  dnsmessage.MustNewName("example.com"),
						Type:  dnsmessage.TypeA,
						Class: dnsmessage.ClassINET,
					},
				},
			}
			qbuf, err := q.Pack()
			if err != nil {
				// log error and continue
				continue
			}
			start := time.Now()
			// Try to send a DNS query and measure RTT
			conn, err := net.DialTimeout("udp4", net.JoinHostPort(r.IP.String(), "53"), 2*time.Second)
			if err != nil {
				// treat as loss
				h.mu.Lock()
				r.Loss = 1.0
				r.RTT = 0
				h.mu.Unlock()
				continue
			}
			// Set a read deadline
			conn.SetReadDeadline(time.Now().Add(2 * time.Second))
			_, err = conn.Write(qbuf)
			if err != nil {
				conn.Close()
				h.mu.Lock()
				r.Loss = 1.0
				r.RTT = 0
				h.mu.Unlock()
				continue
			}
			var resp [512]byte
			n, err := conn.Read(resp[:])
			conn.Close()
			if err != nil {
				h.mu.Lock()
				r.Loss = 1.0
				r.RTT = 0
				h.mu.Unlock()
				continue
			}
			// Parse the response to ensure it's a valid DNS response and matches the ID
			var respMsg dnsmessage.Message
			err = respMsg.Unpack(resp[:n])
			if err != nil {
				h.mu.Lock()
				r.Loss = 1.0
				r.RTT = 0
				h.mu.Unlock()
				continue
			}
			// Check that the response ID matches the query ID
			if respMsg.Header.ID != q.Header.ID {
				h.mu.Lock()
				r.Loss = 1.0
				r.RTT = 0
				h.mu.Unlock()
				continue
			}
			// If we got here, we have a valid response
			rtt := time.Since(start)
			h.mu.Lock()
			r.RTT = rtt
			// Simple loss estimator: if we succeed, decrease loss
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
