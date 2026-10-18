package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/doodoolyasin/meli/pkg/tunnel"
	"github.com/doodoolyasin/meli/pkg/tunnel/arq"
)

func main() {
	var (
		listenAddr = flag.String("listen", "0.0.0.0:5053", "UDP listen address")
		domain     = flag.String("domain", "", "Domain for tunneling (e.g., tunnel.example.com)")
		targetAddr = flag.String("target", "", "Target address to forward traffic to (e.g., 127.0.0.1:8080 or socks5://...)")
		timeout    = flag.Duration("timeout", 5*time.Second, "DNS query timeout")
		help       = flag.Bool("help", false, "Show help")
	)
	flag.Parse()

	if *help || *domain == "" || *targetAddr == "" {
		fmt.Fprintf(os.Stderr, "Meli DNS Tunnel Server\n\n")
		flag.PrintDefaults()
		os.Exit(1)
	}

	// Setup signal handling
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// ARQ configuration
	arqCfg := arq.Config{
		WindowSize:               512,
		RTO:                      0.2,
		MaxRTO:                   2.0,
		InactivityTimeout:        120,
		DataPacketTTL:            120,
		MaxDataRetries:           60,
		TerminalDrainTimeout:     60,
		TerminalAckWaitTimeout:   30,
		IsClient:                 false,
		InboundQueueSize:         2048,
	}

	// Tunnel configuration
	tunCfg := tunnel.Config{
		LocalAddr:  *listenAddr,
		Domain:     *domain,
		TargetAddr: *targetAddr,
		Timeout:    *timeout,
		ARQConfig:  arqCfg,
	}

	// Create and start server
	server, err := tunnel.NewServer(tunCfg)
	if err != nil {
		log.Fatalf("Failed to create server: %v", err)
	}
	defer server.Close()

	log.Printf("Meli server listening on %s for domain %s, forwarding to %s", *listenAddr, *domain, *targetAddr)

	<-ctx.Done()
	log.Println("Shutting down...")
	log.Println("Server stopped")
}