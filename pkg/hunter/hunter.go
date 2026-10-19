package hunter

import (
	"context"
	_ "embed"
	"fmt"
	"math/rand"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

//go:embed data/resolvers.txt
var embeddedResolvers string

// ResolverInfo holds info about a DNS resolver
type ResolverInfo struct {
	IP       net.IP
	RTT      time.Duration
	Loss     float64 // 0.0-1.0
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

// ParseResolvers parses newline-separated IP strings into net.IP slice
func ParseResolvers(raw string) []net.IP {
	var ips []net.IP
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || line[0] == '#' {
			continue
		}
		ip := net.ParseIP(line)
		if ip != nil {
			ips = append(ips, ip)
		}
	}
	return ips
}

// NewResolverHunter creates a hunter using embedded 7,854 Iranian resolvers.
func NewResolverHunter() (*ResolverHunter, error) {
	var ips []net.IP
	if len(embeddedResolvers) > 0 {
		ips = ParseResolvers(embeddedResolvers)
	}
	if len(ips) == 0 {
		var err error
		ips, err = LoadResolversFromFile("pkg/hunter/data/resolvers.txt")
		if err != nil {
			// Fallback to internal/default Iranian DNS nodes
			ips = []net.IP{
				net.ParseIP("10.202.10.202"),
				net.ParseIP("10.202.10.102"),
				net.ParseIP("178.22.122.100"),
				net.ParseIP("185.51.200.2"),
			}
		}
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

// LoadResolversFromFile reads an external file and returns a list of net.IP.
func LoadResolversFromFile(filename string) ([]net.IP, error) {
	data, err := os.ReadFile(filename)
	if err != nil {
		return nil, err
	}
	ips := ParseResolvers(string(data))
	if len(ips) == 0 {
		return nil, fmt.Errorf("no valid IPs found in %s", filename)
	}
	return ips, nil
}

// Start launches bounded background probing goroutines across the resolver list
func (h *ResolverHunter) Start() {
	workerCount := 50
	jobs := make(chan int, len(h.resolvers))
	for i := range h.resolvers {
		jobs <- i
	}
	close(jobs)

	h.wg.Add(workerCount)
	for w := 0; w < workerCount; w++ {
		go func() {
			defer h.wg.Done()
			for idx := range jobs {
				select {
				case <-h.ctx.Done():
					return
				default:
					h.probeOnce(idx)
				}
			}
		}()
	}
}

// Stop gracefully shuts down active workers
func (h *ResolverHunter) Stop() {
	if h.cancel != nil {
		h.cancel()
	}
	h.wg.Wait()
}

// TotalCount returns total resolvers tracked
func (h *ResolverHunter) TotalCount() int {
	return len(h.resolvers)
}

// GetPriorityPool returns up to maxCount responsive resolvers sorted by lowest RTT.
func (h *ResolverHunter) GetPriorityPool(maxCount int) []net.IP {
	h.mu.RLock()
	defer h.mu.RUnlock()

	var responsive []*ResolverInfo
	for _, r := range h.resolvers {
		if r.IP != nil && r.RTT > 0 && r.Loss < 0.8 {
			responsive = append(responsive, r)
		}
	}

	// Sort by RTT (lowest latency first)
	for i := 0; i < len(responsive); i++ {
		for j := i + 1; j < len(responsive); j++ {
			if responsive[j].RTT < responsive[i].RTT {
				responsive[i], responsive[j] = responsive[j], responsive[i]
			}
		}
	}

	if len(responsive) > maxCount {
		responsive = responsive[:maxCount]
	}

	out := make([]net.IP, len(responsive))
	for i, r := range responsive {
		out[i] = r.IP
	}
	return out
}

// Best returns the single best resolver (lowest RTT).
func (h *ResolverHunter) Best() net.IP {
	pool := h.GetPriorityPool(1)
	if len(pool) > 0 {
		return pool[0]
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, r := range h.resolvers {
		if r.IP != nil {
			return r.IP
		}
	}
	return net.ParseIP("10.202.10.202")
}

// probeOnce measures a single resolver with an authentic DNS query
func (h *ResolverHunter) probeOnce(idx int) {
	r := h.resolvers[idx]
	q := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:               uint16(rand.Uint32()),
			RecursionDesired: true,
		},
		Questions: []dnsmessage.Question{
			{
				Name:  dnsmessage.MustNewName("example.com."),
				Type:  dnsmessage.TypeA,
				Class: dnsmessage.ClassINET,
			},
		},
	}
	qbuf, err := q.Pack()
	if err != nil {
		return
	}

	start := time.Now()
	conn, err := net.DialTimeout("udp4", net.JoinHostPort(r.IP.String(), "53"), 1200*time.Millisecond)
	if err != nil {
		h.mu.Lock()
		r.Loss = 1.0
		r.RTT = 0
		h.mu.Unlock()
		return
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(1200 * time.Millisecond))
	if _, err := conn.Write(qbuf); err != nil {
		h.mu.Lock()
		r.Loss = 1.0
		r.RTT = 0
		h.mu.Unlock()
		return
	}

	var resp [512]byte
	n, err := conn.Read(resp[:])
	if err != nil {
		h.mu.Lock()
		r.Loss = 1.0
		r.RTT = 0
		h.mu.Unlock()
		return
	}

	var respMsg dnsmessage.Message
	if err := respMsg.Unpack(resp[:n]); err != nil || respMsg.Header.ID != q.Header.ID {
		h.mu.Lock()
		r.Loss = 1.0
		r.RTT = 0
		h.mu.Unlock()
		return
	}

	rtt := time.Since(start)
	h.mu.Lock()
	r.RTT = rtt
	r.Loss = 0.0
	r.LastSeen = time.Now()
	h.mu.Unlock()
}
