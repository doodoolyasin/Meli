//go:build go1.18
// +build go1.18

package dnsparser

import (
	"runtime"
	"testing"

)

// malformedBat produces the adversarial corpus observed in the Phase 3
// malformed-UDP investigation: impossible section counts, self-referencing
// compression pointers, truncated names, zero-length messages and random
// noise. Each datagram is small (<= 64 bytes) so amplification is measured
// against a known, tiny input volume.
func malformedBat(n int) [][]byte {
	corpus := [][]byte{
		// 12 bytes, 0xFFFF question count (allocation-amplification attempt).
		[]byte("\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff"),
		// Self-referencing pointer at offset 0x0C pointing to itself.
		[]byte("\x00\x00\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\xc0\x0c"),
		// Truncated name (label runs past end of datagram).
		[]byte("\x00\x01\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x40\x7f"),
		// Empty payload and tiny garbage.
		{0x00},
		{0xde, 0xad, 0xbe, 0xef, 0x01, 0x02, 0x03},
	}
	out := make([][]byte, 0, n)
	for i := 0; i < n; i++ {
		out = append(out, corpus[i%len(corpus)])
	}
	return out
}

// TestMalformedParsesHaveBoundedAllocations is the parser-level regression for
// the Phase 3 "500 malformed packets -> growing RSS" observation. It asserts
// the property that actually holds at the parser API: per malformed datagram,
// rejection allocates a small, fixed number of objects independent of the
// declared counts in the header. It deliberately avoids asserting about RSS —
// runtime heap growth reflects GC scheduling, not the parser.
func TestMalformedParsesHaveBoundedAllocations(t *testing.T) {
	for i, datagram := range malformedBat(6) {
		allocs := testing.AllocsPerRun(100, func() {
			_, _ = ParsePacketLite(datagram)
			_, _ = ParseDNSRequestLite(datagram)
			_, _ = ParseDNSDatagramLite(datagram)
		})
		// Rejections take the error paths; the only allocations allowed are
		// the interface boxing of errors and, for parsed datagrams, small
		// slices. 20 objects per run ≈ well under 1 KiB of garbage per
		// adversarial datagram.
		if allocs > 20 {
			t.Fatalf("malformed datagram %d: %v allocs per parse triple, want <= 20", i, allocs)
		}
	}
}

// TestMalformedTrafficDoesNotRetainHeap sends a larger malformed volume, then
// demands that the heap returns to baseline after GC. A leak in the parser
// (retained slices/buffers per rejected datagram) shows up as unrecoverable
// growth; legitimate GC jitter shows up only under the slack margin.
func TestMalformedTrafficDoesNotRetainHeap(t *testing.T) {
	gc := func() {
		runtime.GC()
		runtime.GC()
	}

	gc()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)

	datagrams := malformedBat(2000)
	for i := 0; i < 20; i++ {
		for _, d := range datagrams {
			_, _ = ParsePacketLite(d)
			_, _ = ParseDNSDatagramLite(d)
			if p, err := ParsePacketLite(d); err == nil {
				_, _ = BuildEmptyNoErrorResponseFromLite(d, p)
			}
		}
	}

	gc()
	var after runtime.MemStats
	runtime.ReadMemStats(&after)

	// 40,000 parse operations on ≤64-byte inputs; retention above 512 KiB
	// after full GC would mean the parser kept something per datagram.
	grew := int64(after.HeapAlloc) - int64(before.HeapAlloc)
	if grew > 512*1024 {
		t.Fatalf("heap grew %d bytes across 40k malformed parses, want <= 512KiB retained", grew)
	}
}
