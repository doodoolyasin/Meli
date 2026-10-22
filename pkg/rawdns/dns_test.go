package rawdns

import (
	"encoding/binary"
	"testing"
)

func TestDNSPacketHeaderFields(t *testing.T) {
	p := NewDNSPacket()
	defer p.Release()

	p.SetID(0x1234)
	p.SetFlags(0x0100)
	p.SetQDCount(1)
	p.SetANCount(2)

	b := p.Bytes()
	if len(b) < 8 {
		t.Fatalf("expected at least 8 bytes, got %d", len(b))
	}

	if id := binary.BigEndian.Uint16(b[0:2]); id != 0x1234 {
		t.Errorf("expected ID 0x1234, got 0x%X", id)
	}
	if flags := binary.BigEndian.Uint16(b[2:4]); flags != 0x0100 {
		t.Errorf("expected flags 0x0100, got 0x%X", flags)
	}
	if qd := binary.BigEndian.Uint16(b[4:6]); qd != 1 {
		t.Errorf("expected QDCount 1, got %d", qd)
	}
	if an := binary.BigEndian.Uint16(b[6:8]); an != 2 {
		t.Errorf("expected ANCount 2, got %d", an)
	}

	// Overwrite ID to verify idempotent offset access
	p.SetID(0xABCD)
	b2 := p.Bytes()
	if len(b2) != len(b) {
		t.Errorf("overwriting ID mutated packet length from %d to %d", len(b), len(b2))
	}
	if id2 := binary.BigEndian.Uint16(b2[0:2]); id2 != 0xABCD {
		t.Errorf("expected updated ID 0xABCD, got 0x%X", id2)
	}
}
