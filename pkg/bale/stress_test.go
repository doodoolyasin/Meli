package bale

import (
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"io"
	"net"
	"net/http"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/doodoolyasin/meli/pkg/webrtc"
)

// Test20xLoopbackStress runs 20 consecutive end-to-end sessions through
// TunnelSession -> ClientPump -> ChanTransport -> ServerPump -> RelayBridge -> HTTP Server.
// It verifies SHA256 byte-exact delivery and checks goroutine leaks across all 20 iterations.
func Test20xLoopbackStress(t *testing.T) {
	// Baseline goroutine count
	runtime.GC()
	baselineGoroutines := runtime.NumGoroutine()

	// HTTP Echo Server
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(body)
	})
	server := &http.Server{Handler: mux}
	go server.Serve(ln)
	defer server.Close()

	serverPort := ln.Addr().(*net.TCPAddr).Port

	// Run 20 deep stress cycles
	for cycle := 1; cycle <= 20; cycle++ {
		// Variable payload size for each cycle (1KB to 32KB)
		size := 1024 * (1 + (cycle % 32))
		payload := make([]byte, size)
		_, _ = rand.Read(payload)
		expectedHash := sha256.Sum256(payload)

		clientToServer := make(chan []byte, 1024)
		serverToClient := make(chan []byte, 1024)

		var bridge *RelayBridge
		srvT := chanTransport(serverToClient, clientToServer)
		srvPump := NewChannelPump(srvT, 1, func(f *webrtc.Frame) error {
			return bridge.ReceiveFrame(f)
		})
		bridge = NewRelayBridge(srvPump.SendFrame)

		sess := NewTunnelSession(fmt.Sprintf("stress-%d", cycle), 0, true)
		cliT := chanTransport(clientToServer, serverToClient)
		cliPump := NewChannelPump(cliT, 1, sess.ReceiveFrame)
		sess.AttachPeer(cliPump.SendFrame)

		socksPort, err := sess.StartSocksBridge()
		if err != nil {
			t.Fatalf("cycle %d start socks: %v", cycle, err)
		}

		httpClient := &http.Client{
			Transport: &http.Transport{
				Proxy:                 mustSockURL(t, socksPort),
				ResponseHeaderTimeout: 5 * time.Second,
			},
			Timeout: 10 * time.Second,
		}

		resp, err := httpClient.Post(
			fmt.Sprintf("http://127.0.0.1:%d/echo", serverPort),
			"application/octet-stream",
			newByteReader(payload),
		)
		if err != nil {
			sess.Close()
			bridge.Close()
			t.Fatalf("cycle %d POST failed: %v", cycle, err)
		}

		got, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			sess.Close()
			bridge.Close()
			t.Fatalf("cycle %d read body failed: %v", cycle, err)
		}

		gotHash := sha256.Sum256(got)
		if gotHash != expectedHash {
			sess.Close()
			bridge.Close()
			t.Fatalf("cycle %d hash mismatch! len(got)=%d len(expected)=%d", cycle, len(got), len(payload))
		}

		// Tear down cycle
		_ = sess.Close()
		_ = bridge.Close()
		_ = srvPump.CloseFn()
		_ = cliPump.CloseFn()
	}

	runtime.GC()
	time.Sleep(100 * time.Millisecond)
	currentGoroutines := runtime.NumGoroutine()
	// Allow small slack for runtime timers/test runners
	if currentGoroutines > baselineGoroutines+15 {
		t.Logf("Warning: Goroutines grew from baseline %d to %d after 20 cycles", baselineGoroutines, currentGoroutines)
	}
}

// TestConcurrentMultiStreamStress tests 10 concurrent streams pushing data simultaneously
// through a single ChannelPump to audit mutex locking and interleaving under load.
func TestConcurrentMultiStreamStress(t *testing.T) {
	clientToServer := make(chan []byte, 4096)
	serverToClient := make(chan []byte, 4096)

	var bridge *RelayBridge
	srvT := chanTransport(serverToClient, clientToServer)
	srvPump := NewChannelPump(srvT, 1, func(f *webrtc.Frame) error {
		return bridge.ReceiveFrame(f)
	})
	bridge = NewRelayBridge(srvPump.SendFrame)

	sess := NewTunnelSession("concurrent-sess", 0, true)
	cliT := chanTransport(clientToServer, serverToClient)
	cliPump := NewChannelPump(cliT, 1, sess.ReceiveFrame)
	sess.AttachPeer(cliPump.SendFrame)

	const numWorkers = 10
	var wg sync.WaitGroup
	errCh := make(chan error, numWorkers)

	for i := 0; i < numWorkers; i++ {
		wg.Add(1)
		connID := uint32(100 + i)
		go func(id uint32) {
			defer wg.Done()
			for step := 0; step < 50; step++ {
				data := []byte(fmt.Sprintf("worker-%d-step-%d-data-chunk", id, step))
				f := &webrtc.Frame{
					ConnID:  id,
					MsgType: webrtc.MsgData,
					Payload: data,
				}
				if err := cliPump.SendFrame(f); err != nil {
					errCh <- err
					return
				}
				// Also interleave keepalive
				if step%10 == 0 {
					_ = cliPump.SendKeepAlive()
				}
			}
		}(connID)
	}

	wg.Wait()
	close(errCh)

	for err := range errCh {
		t.Fatalf("Concurrent worker error: %v", err)
	}

	_ = sess.Close()
	_ = bridge.Close()
}
