package camouflage

import (
	"strings"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func TestRandomize0x20(t *testing.T) {
	orig := "abcdefghijklmnopqrstuvwxyz"
	randCase := Randomize0x20(orig)
	if strings.ToLower(randCase) != orig {
		t.Fatalf("Randomize0x20 corrupted letters: %s vs %s", randCase, orig)
	}
}

func TestCDNCamouflageRoundtrip(t *testing.T) {
	base := "example.com"
	payload := "session123-chunk456.example.com"
	wrapped := WrapWithCDNCamouflage(payload, base)

	stripped := StripCDNCamouflage(wrapped, base)
	if stripped != payload {
		t.Fatalf("expected stripped %q, got %q", payload, stripped)
	}
}

func TestPickInterleavedTypeDistribution(t *testing.T) {
	counts := make(map[dnsmessage.Type]int)
	for i := 0; i < 1000; i++ {
		tp := PickInterleavedType()
		counts[tp]++
	}

	if counts[dnsmessage.TypeA] == 0 {
		t.Errorf("expected non-zero TypeA queries, got 0")
	}
	if counts[dnsmessage.TypeTXT] == 0 {
		t.Errorf("expected non-zero TypeTXT queries, got 0")
	}
}
