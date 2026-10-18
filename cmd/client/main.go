package main

import (
	"log"
	"net"
	"os"
	"strconv"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		log.Fatalf("Usage: %s <server-ip> [port]", os.Args[0])
	}
	serverIP := os.Args[1]
	port := "5053" // default for testing
	if len(os.Args) >= 3 {
		port = os.Args[2]
	} else if p := os.Getenv("MELI_DNS_PORT"); p != "" {
		port = p
	}
	serverPort, err := strconv.Atoi(port)
	if err != nil {
		log.Fatalf("Invalid port %q: %v", port, err)
	}
	log.Printf("Starting Meli client, sending query to %s:%d", serverIP, serverPort)
	conn, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.ParseIP(serverIP), Port: serverPort})
	if err != nil {
		log.Fatalf("Dial failed: %v", err)
	}
	defer conn.Close()
	// Build a simple DNS query for example.com. type A
	msg := make([]byte, 0, 512)
	// ID 0x1234
	msg = append(msg, 0x12, 0x34)
	// Flags: standard query 0x0100 (recursion desired)
	msg = append(msg, 0x01, 0x00)
	// QDCOUNT = 1
	msg = append(msg, 0x00, 0x01)
	// ANCOUNT = 0
	msg = append(msg, 0x00, 0x00)
	// NSCOUNT = 0
	msg = append(msg, 0x00, 0x00)
	// ARCOUNT = 0
	msg = append(msg, 0x00, 0x00)
	// Question: example.com
	msg = append(msg, 0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e') // length 7 + 'example'
	msg = append(msg, 0x03, 'c', 'o', 'm') // 'com'
	msg = append(msg, 0x00) // null terminator
	// QTYPE = A (1)
	msg = append(msg, 0x00, 0x01)
	// QCLASS = IN (1)
	msg = append(msg, 0x00, 0x01)
	_, err = conn.Write(msg)
	if err != nil {
		log.Fatalf("Write failed: %v", err)
	}
	log.Print("Query sent")
	// Set deadline for response
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 512)
	n, _, err := conn.ReadFromUDP(buf)
	if err != nil {
		log.Fatalf("Read failed: %v", err)
	}
	log.Printf("Received %d bytes: %x", n, buf[:n])
}
