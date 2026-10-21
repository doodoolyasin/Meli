package dnsparser

import (
	"encoding/binary"
	"runtime"
	"testing"
)

// buildHeaderOnlyPacket returns a syntactically complete 12-byte DNS header
// whose section counts are fully attacker-controlled.
func buildHeaderOnlyPacket(qd, an, ns, ar uint16) []byte {
	pkt := make([]byte, dnsHeaderSize)
	binary.BigEndian.PutUint16(pkt[4:6], qd)
	binary.BigEndian.PutUint16(pkt[6:8], an)
	binary.BigEndian.PutUint16(pkt[8:10], ns)
	binary.BigEndian.PutUint16(pkt[10:12], ar)
	return pkt
}

// A twelve-byte datagram claiming 65535 questions must be rejected before the
// parser sizes a slice from that claim. This is the remote memory-amplification
// regression: the old code allocated ~1.5 MiB here, per packet.
func TestParsePacketRejectsHugeQuestionCountWithoutAllocating(t *testing.T) {
	pkt := buildHeaderOnlyPacket(0xFFFF, 0, 0, 0)

	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)

	if _, err := ParsePacket(pkt); err == nil {
		t.Fatal("expected oversized QDCOUNT to be rejected")
	}

	runtime.ReadMemStats(&after)
	if delta := after.TotalAlloc - before.TotalAlloc; delta > 256*1024 {
		t.Fatalf("malicious QDCOUNT caused %d bytes of allocation, want < 256 KiB", delta)
	}
}

func TestParsePacketRejectsImpossibleAnswerCounts(t *testing.T) {
	cases := []struct {
		name   string
		qd, an uint16
		ns, ar uint16
	}{
		{name: "answers", an: 0xFFFF},
		{name: "authorities", ns: 0xFFFF},
		{name: "additional", ar: 0xFFFF},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			pkt := buildHeaderOnlyPacket(tc.qd, tc.an, tc.ns, tc.ar)

			var before, after runtime.MemStats
			runtime.GC()
			runtime.ReadMemStats(&before)

			if _, err := ParsePacket(pkt); err == nil {
				t.Fatal("expected oversized section count to be rejected")
			}

			runtime.ReadMemStats(&after)
			if delta := after.TotalAlloc - before.TotalAlloc; delta > 256*1024 {
				t.Fatalf("malicious section count caused %d bytes of allocation, want < 256 KiB", delta)
			}
		})
	}
}

// The bound must be derived from the wire, not a fixed low cap: a message that
// genuinely contains the maximum number of minimum-size questions must parse.
func TestParsePacketAcceptsDenselyPackedQuestions(t *testing.T) {
	const questionCount = 100 // 12 header + 100*(1+4) = 512 bytes

	pkt := make([]byte, dnsHeaderSize+questionCount*minQuestionWireSize)
	binary.BigEndian.PutUint16(pkt[4:6], questionCount)
	for i := 0; i < questionCount; i++ {
		off := dnsHeaderSize + i*minQuestionWireSize
		pkt[off] = 0 // root label
		binary.BigEndian.PutUint16(pkt[off+1:off+3], 1)
		binary.BigEndian.PutUint16(pkt[off+3:off+5], 1)
	}

	parsed, err := ParsePacket(pkt)
	if err != nil {
		t.Fatalf("valid densely packed packet rejected: %v", err)
	}
	if len(parsed.Questions) != questionCount {
		t.Fatalf("parsed %d questions, want %d", len(parsed.Questions), questionCount)
	}
	if parsed.Questions[0].Name != "." {
		t.Fatalf("unexpected root name: %q", parsed.Questions[0].Name)
	}
}

// Lite parsing is the hot ingress path; it must reject the same input without
// allocating proportional to the declared count.
func TestParsePacketLiteRejectsHugeQuestionCount(t *testing.T) {
	pkt := buildHeaderOnlyPacket(0xFFFF, 0, 0, 0)
	if _, err := ParsePacketLite(pkt); err == nil {
		t.Fatal("expected oversized QDCOUNT to be rejected by lite parser")
	}
}
