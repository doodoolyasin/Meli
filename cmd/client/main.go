package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/doodoolyasin/meli/pkg/tunnel"
)

func main() {
	var (
		remoteAddr = flag.String("remote", "", "Remote DNS server address (e.g., 8.8.8.8:53)")
		domain     = flag.String("domain", "", "Domain for tunneling (e.g., tunnel.example.com)")
		socksAddr  = flag.String("socks", "127.0.0.1:1080", "SOCKS5 listen address")
		key        = flag.String("key", "137e43fd04a5b9dfdaf29e103fddcce2", "Pre-shared encryption key")
		timeout    = flag.Duration("timeout", 5*time.Second, "DNS query timeout")
		help       = flag.Bool("help", false, "Show help")
	)
	flag.Parse()

	if *help || *remoteAddr == "" || *domain == "" {
		fmt.Fprintf(os.Stderr, "Meli DNS Tunnel Client\n\n")
		flag.PrintDefaults()
		os.Exit(1)
	}

	// Setup signal handling
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// Tunnel configuration
	tunCfg := tunnel.Config{
		RemoteAddr: *remoteAddr,
		Domain:     *domain,
		Key:        []byte(*key),
		Timeout:    *timeout,
	}

	// Listen for SOCKS5 connections
	listener, err := net.Listen("tcp", *socksAddr)
	if err != nil {
		log.Fatalf("Failed to listen on SOCKS5 %s: %v", *socksAddr, err)
	}
	defer listener.Close()

	log.Printf("Meli client listening on %s, tunneling to %s via %s", *socksAddr, *remoteAddr, *domain)

	var wg sync.WaitGroup
	sem := make(chan struct{}, 100) // Limit concurrent connections

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				log.Printf("Accept error: %v", err)
				continue
			}

			sem <- struct{}{}
			wg.Add(1)
			go func(c net.Conn) {
				defer func() {
					<-sem
					wg.Done()
				}()
				handleSOCKS5Connection(ctx, c, tunCfg)
			}(conn)
		}
	}()

	<-ctx.Done()
	log.Println("Shutting down...")
	listener.Close()
	wg.Wait()
	log.Println("Client stopped")
}

func handleSOCKS5Connection(ctx context.Context, conn net.Conn, cfg tunnel.Config) {
	defer conn.Close()

	// SOCKS5 handshake
	if err := socks5Handshake(conn); err != nil {
		log.Printf("SOCKS5 handshake failed: %v", err)
		return
	}

	// Parse target address
	targetAddr, err := parseSOCKS5Request(conn)
	if err != nil {
		log.Printf("SOCKS5 request parse failed: %v", err)
		return
	}

	log.Printf("Tunneling connection to %s", targetAddr)

	// Connect to target through tunnel
	tunConn, err := tunnel.NewClient(cfg, conn)
	if err != nil {
		log.Printf("Failed to create tunnel: %v", err)
		return
	}
	defer tunConn.Close()

	// The tunnel wraps the connection - data written to conn goes through DNS
	// We just need to copy data between the SOCKS5 connection and the tunnel
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		io.Copy(conn, tunConn)
		conn.(*net.TCPConn).CloseRead()
	}()

	go func() {
		defer wg.Done()
		io.Copy(tunConn, conn)
		tunConn.Close()
	}()

	wg.Wait()
}

func socks5Handshake(conn net.Conn) error {
	// Read version and nmethods
	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return err
	}
	if buf[0] != 0x05 {
		return fmt.Errorf("unsupported SOCKS version: %d", buf[0])
	}
	nMethods := int(buf[1])
	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}

	// We support no authentication (method 0x00)
	conn.Write([]byte{0x05, 0x00})
	return nil
}

func parseSOCKS5Request(conn net.Conn) (string, error) {
	buf := make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return "", err
	}
	if buf[0] != 0x05 || buf[1] != 0x01 {
		return "", fmt.Errorf("invalid SOCKS5 request: ver=%d cmd=%d", buf[0], buf[1])
	}

	// Read address type
	atypBuf := make([]byte, 1)
	if _, err := io.ReadFull(conn, atypBuf); err != nil {
		return "", err
	}

	switch atypBuf[0] {
	case 0x01: // IPv4
		addrBuf := make([]byte, 4)
		if _, err := io.ReadFull(conn, addrBuf); err != nil {
			return "", err
		}
		portBuf := make([]byte, 2)
		if _, err := io.ReadFull(conn, portBuf); err != nil {
			return "", err
		}
		port := (uint16(portBuf[0]) << 8) | uint16(portBuf[1])
		return fmt.Sprintf("%d.%d.%d.%d:%d", addrBuf[0], addrBuf[1], addrBuf[2], addrBuf[3], port), nil

	case 0x03: // Domain name
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return "", err
		}
		domainBuf := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(conn, domainBuf); err != nil {
			return "", err
		}
		portBuf := make([]byte, 2)
		if _, err := io.ReadFull(conn, portBuf); err != nil {
			return "", err
		}
		port := (uint16(portBuf[0]) << 8) | uint16(portBuf[1])
		return fmt.Sprintf("%s:%d", string(domainBuf), port), nil

	case 0x04: // IPv6
		addrBuf := make([]byte, 16)
		if _, err := io.ReadFull(conn, addrBuf); err != nil {
			return "", err
		}
		portBuf := make([]byte, 2)
		if _, err := io.ReadFull(conn, portBuf); err != nil {
			return "", err
		}
		port := (uint16(portBuf[0]) << 8) | uint16(portBuf[1])
		return fmt.Sprintf("[%s]:%d", net.IP(addrBuf).String(), port), nil

	default:
		return "", fmt.Errorf("unsupported address type: %d", atypBuf[0])
	}
}