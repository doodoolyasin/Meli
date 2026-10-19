package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type DemuxConfig struct {
	ListenAddr    string
	MeliBackend   string
	DnsttBackend  string
	MeliDomain    string
	DnsttDomain   string
}

func main() {
	listenAddr := flag.String("listen", "0.0.0.0:53", "Public UDP listen address")
	meliBackend := flag.String("meli-backend", "127.0.0.1:5301", "Meli / MasterDNS server backend")
	dnsttBackend := flag.String("dnstt-backend", "127.0.0.1:5302", "DNSTT server backend")
	meliDomain := flag.String("meli-domain", "m.dolphin.work.gd", "Subdomain for Meli / MasterDNS")
	dnsttDomain := flag.String("dnstt-domain", "t.dolphin.work.gd", "Subdomain for DNSTT")
	flag.Parse()

	uaddr, err := net.ResolveUDPAddr("udp4", *listenAddr)
	if err != nil {
		log.Fatalf("Invalid listen address: %v", err)
	}

	meliUAddr, err := net.ResolveUDPAddr("udp4", *meliBackend)
	if err != nil {
		log.Fatalf("Invalid Meli backend address: %v", err)
	}

	dnsttUAddr, err := net.ResolveUDPAddr("udp4", *dnsttBackend)
	if err != nil {
		log.Fatalf("Invalid DNSTT backend address: %v", err)
	}

	listener, err := net.ListenUDP("udp4", uaddr)
	if err != nil {
		log.Fatalf("Failed to bind UDP port 53: %v", err)
	}
	defer listener.Close()

	fmt.Println("========================================================")
	fmt.Println("⚡ Meli Front-Door DNS Demultiplexer (Port 53 Router)")
	fmt.Printf("✓ Listening on:       %s\n", *listenAddr)
	fmt.Printf("✓ Meli Routing (*.%s)  -> %s\n", *meliDomain, *meliBackend)
	fmt.Printf("✓ DNSTT Routing (*.%s) -> %s\n", *dnsttDomain, *dnsttBackend)
	fmt.Println("========================================================")

	// Memory pool for zero-allocation UDP buffers
	bufPool := sync.Pool{
		New: func() interface{} {
			b := make([]byte, 4096)
			return &b
		},
	}

	// Client session tracking map for responses
	type clientEntry struct {
		clientAddr *net.UDPAddr
		timestamp  time.Time
	}
	var sessionMu sync.RWMutex
	sessions := make(map[uint16]clientEntry)

	// Worker to prune stale transaction IDs every 30 seconds
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		for range ticker.C {
			sessionMu.Lock()
			now := time.Now()
			for txID, entry := range sessions {
				if now.Sub(entry.timestamp) > 10*time.Second {
					delete(sessions, txID)
				}
			}
			sessionMu.Unlock()
		}
	}()

	// Outbound socket talking to local backends
	backendConn, err := net.ListenUDP("udp4", nil)
	if err != nil {
		log.Fatalf("Failed to create backend UDP socket: %v", err)
	}
	defer backendConn.Close()

	// Goroutine reading responses from local backends and forwarding back to clients
	go func() {
		for {
			bufPtr := bufPool.Get().(*[]byte)
			buf := *bufPtr
			n, _, err := backendConn.ReadFrom(buf)
			if err != nil {
				bufPool.Put(bufPtr)
				continue
			}

			if n >= 12 {
				txID := uint16(buf[0])<<8 | uint16(buf[1])
				sessionMu.RLock()
				entry, ok := sessions[txID]
				sessionMu.RUnlock()

				if ok && entry.clientAddr != nil {
					_, _ = listener.WriteToUDP(buf[:n], entry.clientAddr)
				}
			}
			bufPool.Put(bufPtr)
		}
	}()

	// Ingress loop: read from public clients and route to respective backend
	for {
		bufPtr := bufPool.Get().(*[]byte)
		buf := *bufPtr
		n, clientAddr, err := listener.ReadFromUDP(buf)
		if err != nil {
			bufPool.Put(bufPtr)
			continue
		}

		if n < 12 {
			bufPool.Put(bufPtr)
			continue
		}

		txID := uint16(buf[0])<<8 | uint16(buf[1])
		sessionMu.Lock()
		sessions[txID] = clientEntry{
			clientAddr: clientAddr,
			timestamp:  time.Now(),
		}
		sessionMu.Unlock()

		// Inspect QNAME
		targetBackend := meliUAddr
		var p dnsmessage.Parser
		if _, err := p.Start(buf[:n]); err == nil {
			_ = p.SkipAllQuestions()
			if q, err := p.Question(); err == nil {
				qname := strings.ToLower(q.Name.String())
				if strings.Contains(qname, strings.ToLower(*dnsttDomain)) {
					targetBackend = dnsttUAddr
				}
			}
		}

		_, _ = backendConn.WriteToUDP(buf[:n], targetBackend)
		bufPool.Put(bufPtr)
	}
}
