package client

import (
	"bytes"
	"testing"
	"time"
)

// TestFragmentPayloadNonPositiveMTUTerminates guards the fragmentation step
// against an unnegotiated MTU. A zero step turns the slice loop into an
// infinite append, which hangs the SOCKS connect path and grows memory without
// bound.
func TestFragmentPayloadNonPositiveMTUTerminates(t *testing.T) {
	payload := []byte("socks-target-header")

	for _, mtu := range []int{0, -1, -4096} {
		done := make(chan [][]byte, 1)
		go func(m int) {
			done <- fragmentPayload(payload, m)
		}(mtu)

		select {
		case frags := <-done:
			if len(frags) != 1 {
				t.Fatalf("mtu=%d produced %d fragments, want 1", mtu, len(frags))
			}
			if !bytes.Equal(frags[0], payload) {
				t.Fatalf("mtu=%d altered the payload: %q", mtu, frags[0])
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("mtu=%d did not terminate: fragmentation looped forever", mtu)
		}
	}
}

func TestFragmentPayloadSplitsExactly(t *testing.T) {
	payload := make([]byte, 10)
	for i := range payload {
		payload[i] = byte(i)
	}

	frags := fragmentPayload(payload, 4)
	if len(frags) != 3 {
		t.Fatalf("got %d fragments, want 3", len(frags))
	}
	if len(frags[0]) != 4 || len(frags[1]) != 4 || len(frags[2]) != 2 {
		t.Fatalf("unexpected fragment sizes: %d %d %d", len(frags[0]), len(frags[1]), len(frags[2]))
	}

	var rebuilt []byte
	for _, f := range frags {
		rebuilt = append(rebuilt, f...)
	}
	if !bytes.Equal(rebuilt, payload) {
		t.Fatalf("reassembled payload differs: %v", rebuilt)
	}
}
