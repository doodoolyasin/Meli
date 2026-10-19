package desync

import (
	"crypto/rand"
	"math/big"
)

// AddRandomPadding appends between minBytes and maxBytes of randomized junk padding
// to defeat statistical traffic analysis and packet length distribution classifiers (e.g. TSPU / GFW ML).
func AddRandomPadding(payload []byte, minBytes int, maxBytes int) []byte {
	if maxBytes <= minBytes || maxBytes <= 0 {
		return payload
	}
	diff := maxBytes - minBytes
	n, _ := rand.Int(rand.Reader, big.NewInt(int64(diff+1)))
	padLen := minBytes + int(n.Int64())

	padding := make([]byte, padLen)
	_, _ = rand.Read(padding)

	// Format: [Payload] + [Padding] + [1-byte Pad Length]
	out := make([]byte, 0, len(payload)+padLen+1)
	out = append(out, payload...)
	out = append(out, padding...)
	out = append(out, byte(padLen))
	return out
}

// StripRandomPadding extracts the original payload by removing the trailing randomized padding bytes.
func StripRandomPadding(data []byte) []byte {
	if len(data) < 2 {
		return data
	}
	padLen := int(data[len(data)-1])
	if padLen < 0 || len(data) < padLen+1 {
		return data
	}
	return data[:len(data)-1-padLen]
}
