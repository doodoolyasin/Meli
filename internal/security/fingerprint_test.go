package security

import (
	"strings"
	"testing"
)

func TestKeyFingerprintDoesNotRevealTheKey(t *testing.T) {
	const key = "0123456789abcdef0123456789abcdef"
	fp := KeyFingerprint(key)

	if fp == "" || fp == "empty" {
		t.Fatalf("fingerprint for a non-empty key = %q", fp)
	}
	if strings.Contains(fp, key) || strings.Contains(key, fp) {
		t.Fatalf("fingerprint %q overlaps the key", fp)
	}
	if len(fp) != 8 {
		t.Fatalf("fingerprint length = %d, want 8 hex characters", len(fp))
	}
	if again := KeyFingerprint(key); again != fp {
		t.Fatalf("fingerprint is not stable: %q vs %q", fp, again)
	}
	if other := KeyFingerprint(key + "x"); other == fp {
		t.Fatalf("different keys produced the same fingerprint %q", fp)
	}
}

func TestKeyFingerprintEmptyKey(t *testing.T) {
	if got := KeyFingerprint(""); got != "empty" {
		t.Fatalf("empty key fingerprint = %q, want \"empty\"", got)
	}
}
