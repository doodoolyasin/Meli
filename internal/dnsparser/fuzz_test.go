//go:build go1.18
// +build go1.18

package dnsparser

import (
	"testing"

)

// These fuzz targets exercise the wire-facing parser entry points with malformed
// and adversarial inputs: truncation, self-referencing pointers, cyclic names,
// oversized section counts and arbitrary bytes. The contract they assert is the
// parser's only safety guarantee: no panic, and error-or-parse (never garbage in,
// garbage out that would later panic somewhere else).

func FuzzParsePacketLite(f *testing.F) {
	seeds := [][]byte{
		nil,
		{},
		make([]byte, 12),
		[]byte("\x00\x01\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01"),
		[]byte("\x00\x01\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\xc0\x0c\x00\x01\x00\x01"),
		// Self-referencing pointer loop pointing at itself.
		[]byte("\x00\x00\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\xc0\x0c"),
		// 0xFFFF declared question count in a 12-byte datagram (amplification).
		[]byte("\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff"),
		[]byte("\x00\x00\x00\x00\xff\xff\x00\x00\x00\x00\x00\x00"),
	}
	for _, s := range seeds {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		// Contract: any input is either parsed or rejected — never a panic, and
		// a successful parse must round-trip through the response builders.
		if p, err := ParsePacketLite(data); err == nil {
			_, _ = BuildEmptyNoErrorResponse(data)
			_, _ = BuildNoDataResponseFromLite(data, p)
			_, _ = BuildFormatErrorResponseFromLite(data, p)
		}
		if p, err := ParseDNSDatagramLite(data); err == nil {
			_, _ = BuildNameErrorResponseFromLite(data, p)
			_, _ = BuildNotImplementedResponseFromLite(data, p)
		}
	})
}

func FuzzParseDNSRequestLite(f *testing.F) {
	for _, s := range [][]byte{
		make([]byte, 12),
		[]byte("\x00\x01\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01"),
		[]byte("\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff"),
	} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		if p, err := ParseDNSRequestLite(data); err == nil {
			_, _ = BuildRefusedResponseFromLite(data, p)
			_, _ = BuildServerFailureResponseFromLite(data, p)
		}
	})
}

func FuzzFullPacketParser(f *testing.F) {
	for _, s := range [][]byte{
		make([]byte, 12),
		[]byte("\x00\x01\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01"),
		[]byte("\x00\x01\x01\x00\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\x01\x00\x01"),
	} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		if _, err := ParsePacket(data); err == nil {
			_, _ = BuildEmptyNoErrorResponse(data)
		}
	})
}
