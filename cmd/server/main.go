package main

import (
	"fmt"
	"log"
	"net"
	"os"
)

func main() {
	port := "5053"
	if p := os.Getenv("MELI_DNS_PORT"); p != "" {
		port = p
	}
	log.Printf("Starting Meli DNS server on UDP port %s...", port)
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{Port: mustParsePort(port)})
	if err != nil {
		log.Fatalf("Failed to listen on UDP port %s: %v", port, err)
	}
	defer pc.Close()
	log.Printf("Listening on :%s (UDP)", port)

	buf := make([]byte, 512)
	for {
		n, src, err := pc.ReadFromUDP(buf)
		if err != nil {
			log.Printf("Read error: %v", err)
			continue
		}
		log.Printf("Received %d bytes from %s", n, src)
		_, err = pc.WriteToUDP(buf[:n], src)
		if err != nil {
			log.Printf("Write error: %v", err)
		}
	}
}

func mustParsePort(s string) int {
	var p int
	_, err := fmt.Sscanf(s, "%d", &p)
	if err != nil {
		log.Fatalf("Invalid port %q: %v", s, err)
	}
	return p
}
