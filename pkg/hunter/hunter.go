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

	// Sort by RTT
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
	return nil
}

// probeOnce measures a single resolver with a DNS query
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
	conn, err := net.DialTimeout("udp4", net.JoinHostPort(r.IP.String(), "53"), 1500*time.Millisecond)
	if err != nil {
		h.mu.Lock()
		r.Loss = 1.0
		r.RTT = 0
		h.mu.Unlock()
		return
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(1500 * time.Millisecond))
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
