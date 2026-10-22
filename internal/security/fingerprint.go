package security

import (
	"crypto/sha256"
	"encoding/hex"
)

// KeyFingerprint returns a short, non-reversible identifier for a key.
//
// Operators need to confirm *which* key a running process loaded (for example
// after rotating ENCRYPTION_KEY on one side of a tunnel). Logging the key
// itself leaks the tunnel's only secret into journald, syslog, CI output and
// any bug report that includes logs, so only a truncated hash is emitted.
func KeyFingerprint(key string) string {
	if key == "" {
		return "empty"
	}
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:4])
}
