package camouflage

import (
	"crypto/rand"
	"math/big"
	"strings"
	"unicode"
)

// Randomize0x20 flips the case of ASCII letters randomly (RFC 1035 0x20 bit randomization).
// Recursive DNS resolvers preserve case when querying authoritative nameservers,
// but Deep Packet Inspection (DPI) engines matching lowercase Base32 signatures fail.
func Randomize0x20(s string) string {
	var sb strings.Builder
	sb.Grow(len(s))
	for _, r := range s {
		if unicode.IsLetter(r) && r < 128 {
			n, _ := rand.Int(rand.Reader, big.NewInt(2))
			if n.Int64() == 1 {
				sb.WriteRune(unicode.ToUpper(r))
			} else {
				sb.WriteRune(unicode.ToLower(r))
			}
		} else {
			sb.WriteRune(r)
		}
	}
	return sb.String()
}

// CDNPrefixes contains common realistic CDN and cloud prefixes used to disguise tunnel queries.
var CDNPrefixes = []string{
	"edge-cache",
	"static-asset",
	"cdn-pop",
	"api-gw",
	"live-stream",
	"auth-node",
	"telemetry",
	"push-notif",
}

// WrapWithCDNCamouflage prepends a realistic CDN service label to the tunnel query.
// Example: payload "ab12cd34" -> "edge-cache-9f4a.ab12cd34.domain.com"
func WrapWithCDNCamouflage(payloadDomain string, baseDomain string) string {
	idx, _ := rand.Int(rand.Reader, big.NewInt(int64(len(CDNPrefixes))))
	nonce, _ := rand.Int(rand.Reader, big.NewInt(0xFFFF))
	prefix := CDNPrefixes[idx.Int64()]
	return strings.ToLower(prefix) + "-" + strings.ToLower(big.NewInt(nonce.Int64()).Text(16)) + "." + payloadDomain
}

// StripCDNCamouflage strips any simulated CDN prefixes from incoming queries on the server.
func StripCDNCamouflage(qname string, baseDomain string) string {
	qname = strings.TrimSuffix(qname, ".")
	baseDomain = strings.TrimSuffix(baseDomain, ".")

	if !strings.HasSuffix(qname, baseDomain) {
		return qname
	}

	sub := strings.TrimSuffix(qname, "."+baseDomain)
	parts := strings.Split(sub, ".")
	if len(parts) > 1 {
		for _, p := range CDNPrefixes {
			if strings.HasPrefix(parts[0], p+"-") {
				return strings.Join(parts[1:], ".") + "." + baseDomain
			}
		}
	}
	return qname
}
