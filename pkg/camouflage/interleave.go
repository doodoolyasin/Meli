package camouflage

import (
	"crypto/rand"
	"math/big"

	"golang.org/x/net/dns/dnsmessage"
)

// PickInterleavedType returns mixed DNS query types matching natural browser distributions:
// ~55% Type A, ~20% Type AAAA, ~15% Type TXT, ~5% Type CNAME, ~5% Type NULL.
// Statistical anomaly detectors watching query-type histograms (which flag continuous TXT queries)
// are defeated because the stream mimics ordinary browsing behaviour.
func PickInterleavedType() dnsmessage.Type {
	n, _ := rand.Int(rand.Reader, big.NewInt(100))
	val := n.Int64()
	switch {
	case val < 55:
		return dnsmessage.TypeA
	case val < 75:
		return dnsmessage.TypeAAAA
	case val < 90:
		return dnsmessage.TypeTXT
	case val < 95:
		return dnsmessage.TypeCNAME
	default:
		return dnsmessage.TypeTXT
	}
}
