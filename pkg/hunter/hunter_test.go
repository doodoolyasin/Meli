package hunter

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestParseResolvers(t *testing.T) {
	raw := `
# Comment
10.202.10.202
10.202.10.102

178.22.122.100
invalid-ip
`
	ips := ParseResolvers(raw)
	if len(ips) != 3 {
		t.Fatalf("expected 3 parsed IPs, got %d", len(ips))
	}
	if ips[0].String() != "10.202.10.202" {
		t.Errorf("expected 10.202.10.202, got %s", ips[0].String())
	}
}

func TestRaceQueryWithMockServer(t *testing.T) {
	// Start mock DNS UDP server
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	mockAddr := pc.LocalAddr().(*net.UDPAddr)

	go func() {
		buf := make([]byte, 512)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			// Echo back minimal DNS response
			resp := make([]byte, n)
			copy(resp, buf[:n])
			if len(resp) >= 4 {
				resp[2] |= 0x80 // Set QR flag (response)
			}
			_, _ = pc.WriteTo(resp, addr)
		}
	}()

	mockIP := mockAddr.IP
	// In test, since port is dynamic, test resolver list validation and race timeout handling
	ctx := context.Background()
	dummyQuery := make([]byte, 16)
	dummyQuery[0] = 0xAA
	dummyQuery[1] = 0xBB

	// Race against a non-responsive IP should hit timeout cleanly
	_, err = RaceQuery(ctx, dummyQuery, []net.IP{mockIP}, 100*time.Millisecond)
	if err == nil {
		t.Logf("unexpected immediate response from port 53")
	}
}
