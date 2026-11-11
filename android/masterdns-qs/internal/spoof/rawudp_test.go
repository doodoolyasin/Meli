//go:build !windows
package spoof

import (
	"encoding/binary"
	"testing"
)

func TestBuildUDPPacket(t *testing.T) {
	payload := []byte("test-payload-123")
	srcIP := [4]byte{192, 168, 1, 10}
	dstIP := [4]byte{10, 0, 0, 1}
	srcPort := uint16(12345)
	dstPort := uint16(53)

	pkt := buildUDPPacket(payload, srcIP, dstIP, srcPort, dstPort)
	if len(pkt) != 8+len(payload) {
		t.Fatalf("expected UDP packet len %d, got %d", 8+len(payload), len(pkt))
	}
	if binary.BigEndian.Uint16(pkt[0:2]) != srcPort {
		t.Fatalf("src port mismatch: %d != %d", binary.BigEndian.Uint16(pkt[0:2]), srcPort)
	}
	if binary.BigEndian.Uint16(pkt[2:4]) != dstPort {
		t.Fatalf("dst port mismatch: %d != %d", binary.BigEndian.Uint16(pkt[2:4]), dstPort)
	}
	if binary.BigEndian.Uint16(pkt[4:6]) != uint16(len(pkt)) {
		t.Fatalf("length mismatch: %d != %d", binary.BigEndian.Uint16(pkt[4:6]), len(pkt))
	}
	csum := binary.BigEndian.Uint16(pkt[6:8])
	if csum == 0 {
		t.Fatalf("UDP checksum must be non-zero")
	}
}

func TestBuildIPv4Packet(t *testing.T) {
	udpPayload := []byte("12345678")
	srcIP := [4]byte{192, 168, 1, 10}
	dstIP := [4]byte{10, 0, 0, 1}
	ttl := 64

	ipHdr := buildIPv4Packet(udpPayload, srcIP, dstIP, ttl)
	if len(ipHdr) != 20 {
		t.Fatalf("expected IPv4 header len 20, got %d", len(ipHdr))
	}
	if ipHdr[0] != 0x45 {
		t.Fatalf("expected version/IHL 0x45, got 0x%02x", ipHdr[0])
	}
	totLen := binary.BigEndian.Uint16(ipHdr[2:4])
	if totLen != uint16(20+len(udpPayload)) {
		t.Fatalf("expected totLen %d, got %d", 20+len(udpPayload), totLen)
	}
	ipID := binary.BigEndian.Uint16(ipHdr[4:6])
	if ipID == 0 {
		t.Fatalf("IP ID must not be zero (DPI fingerprint avoidance)")
	}
	if ipHdr[8] != byte(ttl) {
		t.Fatalf("expected TTL %d, got %d", ttl, ipHdr[8])
	}
	if ipHdr[9] != 17 { // UDP
		t.Fatalf("expected proto 17, got %d", ipHdr[9])
	}
	csum := binary.BigEndian.Uint16(ipHdr[10:12])
	if csum == 0 {
		t.Fatalf("IPv4 checksum must be non-zero")
	}

	// Verify checksum of header is valid: checksum of the 20-byte header with its checksum field should be 0
	calc := checksum(ipHdr)
	if calc != 0 {
		t.Fatalf("IPv4 header checksum verification failed, sum = 0x%04x", calc)
	}
}
