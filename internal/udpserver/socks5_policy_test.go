// ==============================================================================
// MasterDnsVPN
// Author: MasterkinG32
// Github: https://github.com/masterking32
// Year: 2026
// ==============================================================================

package udpserver

import (
	"errors"
	"testing"

	"github.com/doodoolyasin/meli/internal/config"
)

func TestValidateSOCKSTargetHostDefaultPolicy(t *testing.T) {
	blocked := []string{
		"",
		"  ",
		"localhost",
		"api.localhost",
		"127.0.0.1",
		"127.10.20.30",
		"::1",
		"10.0.0.1",
		"172.16.5.4",
		"192.168.1.1",
		"fc00::1",
		"fd12:3456::1",
		"100.64.0.1",
		"100.127.255.254",
		"198.18.0.1",
		"198.19.255.255",
		"169.254.169.254", // cloud metadata
		"fe80::1",
		"0.0.0.0",
		"::",
		"224.0.0.1",
		"ff02::1",
	}
	for _, host := range blocked {
		if err := validateSOCKSTargetHost(host); err == nil {
			t.Errorf("expected %q to be blocked by the default policy", host)
		} else {
			var blockedErr *blockedSOCKSTargetError
			if !errors.As(err, &blockedErr) {
				t.Errorf("expected blockedSOCKSTargetError for %q, got %T", host, err)
			}
		}
	}

	allowed := []string{
		"1.1.1.1",
		"8.8.8.8",
		"2606:4700:4700::1111",
		"example.com",
		"api.github.com",
	}
	for _, host := range allowed {
		if err := validateSOCKSTargetHost(host); err != nil {
			t.Errorf("expected %q to be allowed by the default policy, got %v", host, err)
		}
	}
}

func TestValidateSOCKSTargetHostAllowPrivatePolicy(t *testing.T) {
	allowed := []string{
		"127.0.0.1",
		"::1",
		"10.0.0.1",
		"192.168.1.1",
		"172.16.0.1",
		"fc00::1",
		"100.64.0.1",
		"localhost",
		"router.localhost",
	}
	for _, host := range allowed {
		if err := validateSOCKSTargetHostPolicy(host, true); err != nil {
			t.Errorf("expected %q to be allowed with ALLOW_PRIVATE_TARGETS, got %v", host, err)
		}
	}

	// link-local / metadata / unspecified / multicast stay blocked even when
	// private targets are enabled: they are credential-theft and reflection
	// vectors rather than legitimate private services.
	stillBlocked := []string{
		"169.254.169.254",
		"fe80::1",
		"0.0.0.0",
		"::",
		"224.0.0.1",
		"",
	}
	for _, host := range stillBlocked {
		if err := validateSOCKSTargetHostPolicy(host, true); err == nil {
			t.Errorf("expected %q to stay blocked even with ALLOW_PRIVATE_TARGETS", host)
		}
	}
}

func TestServerValidateSOCKSTargetUsesConfig(t *testing.T) {
	deny := &Server{}
	if err := deny.validateSOCKSTarget("127.0.0.1"); err == nil {
		t.Fatal("server without ALLOW_PRIVATE_TARGETS must reject loopback targets")
	}
	if err := deny.validateSOCKSTarget("example.com"); err != nil {
		t.Fatalf("server must keep accepting public names, got %v", err)
	}

	allow := &Server{allowPrivateTargets: true}
	if err := allow.validateSOCKSTarget("127.0.0.1"); err != nil {
		t.Fatalf("server with ALLOW_PRIVATE_TARGETS must accept loopback, got %v", err)
	}
	if err := allow.validateSOCKSTarget("169.254.169.254"); err == nil {
		t.Fatal("server with ALLOW_PRIVATE_TARGETS must still reject the metadata endpoint")
	}
}

func TestAllowPrivateTargetsConfigPropagation(t *testing.T) {
	cfg := config.ServerConfig{AllowPrivateTargets: true}
	srv := New(cfg, nil, nil)
	if !srv.allowPrivateTargets {
		t.Fatal("ALLOW_PRIVATE_TARGETS was not propagated into the server")
	}
	if err := srv.validateSOCKSTarget("127.0.0.1"); err != nil {
		t.Fatalf("expected loopback to be allowed, got %v", err)
	}

	srvDefault := New(config.ServerConfig{}, nil, nil)
	if srvDefault.allowPrivateTargets {
		t.Fatal("ALLOW_PRIVATE_TARGETS must default to false")
	}
	if err := srvDefault.validateSOCKSTarget("127.0.0.1"); err == nil {
		t.Fatal("expected loopback to be blocked by default")
	}
}
