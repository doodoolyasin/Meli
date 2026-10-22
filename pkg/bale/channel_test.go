package bale

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/doodoolyasin/meli/pkg/webrtc"
)

func mustSockURL(t *testing.T, port int) func(*http.Request) (*url.URL, error) {
	u, err := url.Parse(fmt.Sprintf("socks5://127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	return http.ProxyURL(u)
}

// chanTransport builds a channel-based transport between the two pumps
// (loopback stand-in for an ordered DataChannel).
func chanTransport(myBus <-chan []byte, peerBus chan<- []byte) ChannelTransport {
	return ChannelTransport{
		Send: func(raw []byte) error {
			cp := make([]byte, len(raw))
			copy(cp, raw)
			select {
			case peerBus <- cp:
				return nil
			case <-time.After(10 * time.Second):
				return fmt.Errorf("peer bus full")
			}
		},
		SetOnMessage: func(on func([]byte)) {
			go func() {
				for raw := range myBus {
					cp := make([]byte, len(raw))
					copy(cp, raw)
					on(cp)
				}
			}()
		},
		Close: func() error { return nil },
	}
}

// TestChannelPumpEndToEnd: curl -> SOCKS5 (TunnelSession) -> ClientPump ->
// loopback bus -> ServerPump -> RelayBridge -> local HTTP server,
// byte-exact payload, 3 consecutive iterations (per test-engineer brief).
func TestChannelPumpEndToEnd(t *testing.T) {
	// Loopback HTTP target with a deterministic 8KB body.
	payload := strings.Repeat("MASTERDNS-BALE-", 600) // 9000 bytes
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		fmt.Fprint(w, payload)
	})
	go http.Serve(ln, mux)

	// Buses.
	clientToServer := make(chan []byte, 512)
	serverToClient := make(chan []byte, 512)

	// Server side.
	var bridge *RelayBridge
	srvT := chanTransport(serverToClient /*srv reads*/, clientToServer /*srv sends*/)
	srvPump := NewChannelPump(srvT, 1, func(f *webrtc.Frame) error {
		return bridge.ReceiveFrame(f)
	})
	bridge = NewRelayBridge(srvPump.SendFrame)

	// Client side.
	sess := NewTunnelSession("loop0", 0, true)
	cliT := chanTransport(clientToServer, serverToClient)
	cliPump := NewChannelPump(cliT, 1, sess.ReceiveFrame)
	sess.AttachPeer(cliPump.SendFrame)
	socksPort, err := sess.StartSocksBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()
	defer bridge.Close()
	_ = srvPump
	_ = cliPump

	client := &http.Client{
		Transport: &http.Transport{
			Proxy:                 mustSockURL(t, socksPort),
			MaxIdleConns:          2,
			IdleConnTimeout:       2 * time.Second,
			ResponseHeaderTimeout: 10 * time.Second,
		},
		Timeout: 20 * time.Second,
	}
	for i := 1; i <= 3; i++ {
		resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/", ln.Addr().(*net.TCPAddr).Port))
		if err != nil {
			t.Fatalf("iter %d: %v", i, err)
		}
		got, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			t.Fatalf("iter %d read: %v", i, err)
		}
		if string(got) != payload {
			t.Fatalf("iter %d byte mismatch: got %d bytes want %d", i, len(got), len(payload))
		}
	}
}
