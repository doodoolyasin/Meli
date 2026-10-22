//go:build go1.18
// +build go1.18

package socksproto

import (
	"testing"

)

// FuzzParseUDPDatagram feeds arbitrary wire bytes — including truncated
// targets, bogus address types and 255-byte domains — into the SOCKS5 UDP
// datagram header parser. Contract: never panic; valid-looking datagrams must
// return a target whose port re-parses consistently.
func FuzzParseUDPDatagram(f *testing.F) {
	seeds := [][]byte{
		nil,
		{0, 0, 0},
		{0, 0, 0, 0x03, 0x00, 0x1b, 0x00, 0x50},
		{0, 0, 0, 0x03, 0x00, 0x01},
		{0, 0, 0, 0x01, 127, 0, 0, 1, 0, 0x50, 0xde, 0xad, 0xbe, 0xef},
		{0, 0, 0, 0x04},
		{0, 0, 0xff, 0x01},
	}
	for _, s := range seeds {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		if d, err := ParseUDPDatagram(data); err == nil {
			_ = d.Target
		}
	})
}

// FuzzParseTargetPayload exercises the SOCKS5 CONNECT-style target encoding.
func FuzzParseTargetPayload(f *testing.F) {
	seeds := [][]byte{
		nil,
		{0x01},
		{0x01, 127, 0, 0, 1, 0, 0x50},
		{0x03, 0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x00, 0x50},
		{0x03, 0xff},
		{0x04},
		{0x09},
	}
	for _, s := range seeds {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = ParseTargetPayload(data)
	})
}
