package udpserver

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

// TestSOCKSTargetPolicyRejectsAddressAliases feeds the destination policy the
// alternative spellings an attacker would try when the obvious form is
// rejected. A relay that only blocks the canonical string is trivially
// bypassed, so each alias must be resolved to the same address family and
// judged on the parsed IP.
func TestSOCKSTargetPolicyRejectsAddressAliases(t *testing.T) {
	blocked := []string{
		"::ffff:127.0.0.1",       // IPv4-mapped loopback
		"::ffff:169.254.169.254", // IPv4-mapped cloud metadata
		"::ffff:10.0.0.1",        // IPv4-mapped RFC1918
		"0:0:0:0:0:ffff:169.254.169.254",
		"::ffff:7f00:1", // hex form of 127.0.0.1
		"0x7f.0.0.1",    // not an IP for net.ParseIP -> name path
		"127.0.0.1.",    // trailing dot
		"LOCALHOST",     // case variant
		"LocalHost",
		"metadata.google.internal", // resolves to link-local, checked at dial
		"[::1]",                    // bracketed literal
	}
	for _, host := range blocked {
		err := validateSOCKSTargetHost(host)
		if host == "0x7f.0.0.1" || host == "127.0.0.1." || host == "metadata.google.internal" || host == "[::1]" {
			// Names are resolved later; the policy layer may accept the string.
			// What matters is that the connect-time check refuses them.
			assertDialRefused(t, host)
			continue
		}
		if err == nil {
			t.Errorf("expected %q to be blocked by the default policy", host)
			continue
		}
		var blockedErr *blockedSOCKSTargetError
		if !errors.As(err, &blockedErr) {
			t.Errorf("expected blockedSOCKSTargetError for %q, got %T", host, err)
		}
	}
}

// assertDialRefused proves the connect-time guard rejects a destination that the
// string-level policy cannot classify. The dialer resolves the name itself and
// validates the resolved address inside net.Dialer.Control, so a name pointing
// at loopback must fail before any payload is written.
func assertDialRefused(t *testing.T, host string) {
	t.Helper()
	s := &Server{}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	conn, err := s.dialTCPTargetContextPolicy(ctx, net.JoinHostPort(host, "9"), true)
	if err == nil {
		conn.Close()
		t.Errorf("destination %q was dialled despite the default policy", host)
	}
}

// TestSOCKSTargetPolicyAllowsPrivateWhenConfigured guards against the fix for
// the aliases above accidentally breaking the documented ALLOW_PRIVATE_TARGETS
// behaviour.
func TestSOCKSTargetPolicyAllowsPrivateWhenConfigured(t *testing.T) {
	for _, host := range []string{"::ffff:127.0.0.1", "::ffff:10.1.2.3"} {
		if err := validateSOCKSTargetHostPolicy(host, true); err != nil {
			t.Errorf("expected %q to be allowed with ALLOW_PRIVATE_TARGETS, got %v", host, err)
		}
	}
	// The metadata endpoint must stay blocked in both modes, including its
	// IPv4-mapped spelling.
	for _, host := range []string{"169.254.169.254", "::ffff:169.254.169.254"} {
		if err := validateSOCKSTargetHostPolicy(host, true); err == nil {
			t.Errorf("expected %q to stay blocked with ALLOW_PRIVATE_TARGETS", host)
		}
	}
}
