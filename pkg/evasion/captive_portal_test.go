package evasion

import (
	"fmt"
	"net/http"
	"testing"
	"time"
)

func TestIsCaptivePortalDomain(t *testing.T) {
	tests := []struct {
		domain   string
		expected bool
	}{
		{"connectivitycheck.gstatic.com", true},
		{"CONNECTIVITYCHECK.ANDROID.COM.", true},
		{"clients3.google.com", true},
		{"captive.apple.com", true},
		{"google.com", false},
		{"example.com", false},
	}

	for _, tt := range tests {
		if got := IsCaptivePortalDomain(tt.domain); got != tt.expected {
			t.Errorf("IsCaptivePortalDomain(%q) = %v; want %v", tt.domain, got, tt.expected)
		}
	}
}

func TestLocal204ServerRespondsWithHTTP204(t *testing.T) {
	srv, err := StartLocal204Server(0)
	if err != nil {
		t.Fatalf("failed to start local 204 server: %v", err)
	}
	defer srv.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	url := fmt.Sprintf("http://127.0.0.1:%d/generate_204", srv.Port())
	resp, err := client.Get(url)
	if err != nil {
		t.Fatalf("GET /generate_204 failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("expected HTTP 204 No Content, got %d", resp.StatusCode)
	}
}
