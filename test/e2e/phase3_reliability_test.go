//go:build e2e

// Phase 3 reliability tests. These run the real binaries over a loopback DNS
// path and measure the processes from the outside (/proc), so the assertions
// are about observable behaviour rather than internal mocks.
package e2e

import (
	"encoding/binary"
	"fmt"
	"math/rand"
	"net"
	"testing"
	"time"
)

// TestRepeatedConnectDisconnectResourceStability drives many full tunnel
// lifecycles through one long-lived client and checks that file descriptors and
// RSS return to a sane baseline. It is deliberately not a "long-duration" test:
// 25 cycles on a 1-vCPU host is a bounded proxy for churn, and the report says
// exactly that.
func TestRepeatedConnectDisconnectResourceStability(t *testing.T) {
	echo := startEcho(t, "L:")
	tn := startTunnel(t, true, 0)

	clientPID := tn.client.cmd.Process.Pid
	serverPID := tn.server.cmd.Process.Pid

	// Warm-up: one cycle so lazily created descriptors and heap arenas exist
	// before the baseline is taken.
	warm := mustConnect(t, tn.proxyPort, "127.0.0.1", echo.port)
	echoRoundTrip(t, warm, "L:", []byte("warm-up"), 45*time.Second)
	_ = warm.Close()
	time.Sleep(2 * time.Second)

	baseClientFD := processFDCount(t, clientPID)
	baseClientRSS := processRSSKB(t, clientPID)
	baseServerFD := processFDCount(t, serverPID)
	baseServerRSS := processRSSKB(t, serverPID)
	t.Logf("baseline fd client=%d server=%d; rss client=%dKB server=%dKB",
		baseClientFD, baseServerFD, baseClientRSS, baseServerRSS)

	const cycles = 25
	started := time.Now()
	for i := 0; i < cycles; i++ {
		conn := mustConnect(t, tn.proxyPort, "127.0.0.1", echo.port)
		echoRoundTrip(t, conn, "L:", []byte(fmt.Sprintf("lifecycle-%02d", i)), 45*time.Second)
		if err := conn.Close(); err != nil {
			t.Fatalf("cycle %d close: %v", i, err)
		}
	}
	elapsed := time.Since(started)
	time.Sleep(3 * time.Second)

	afterClientFD := processFDCount(t, clientPID)
	afterClientRSS := processRSSKB(t, clientPID)
	afterServerFD := processFDCount(t, serverPID)
	afterServerRSS := processRSSKB(t, serverPID)

	t.Logf("%d cycles in %s (%.0f ms/cycle); fd client=%d server=%d; rss client=%dKB server=%dKB",
		cycles, elapsed.Round(time.Millisecond), float64(elapsed.Milliseconds())/float64(cycles),
		afterClientFD, afterServerFD, afterClientRSS, afterServerRSS)

	if !tn.client.alive() || !tn.server.alive() {
		t.Fatal("a process died during repeated lifecycle churn")
	}
	if grew := afterClientFD - baseClientFD; grew > 10 {
		t.Fatalf("client leaked file descriptors: %d -> %d (+%d)", baseClientFD, afterClientFD, grew)
	}
	if grew := afterServerFD - baseServerFD; grew > 10 {
		t.Fatalf("server leaked file descriptors: %d -> %d (+%d)", baseServerFD, afterServerFD, grew)
	}
	if grewKB := afterClientRSS - baseClientRSS; grewKB > 32*1024 {
		t.Fatalf("client RSS grew by %dKB over %d cycles", grewKB, cycles)
	}
	if grewKB := afterServerRSS - baseServerRSS; grewKB > 32*1024 {
		t.Fatalf("server RSS grew by %dKB over %d cycles", grewKB, cycles)
	}
}

// TestServerRestartRecovery kills the server while a session is established and
// requires the already-running client to notice, re-initialise, and carry new
// streams again without being restarted itself.
func TestServerRestartRecovery(t *testing.T) {
	echo := startEcho(t, "R:")
	tn := startTunnel(t, true, 0)

	conn := mustConnect(t, tn.proxyPort, "127.0.0.1", echo.port)
	echoRoundTrip(t, conn, "R:", []byte("before-restart"), 45*time.Second)
	_ = conn.Close()

	tn.restartServer(t)

	deadline := time.Now().Add(150 * time.Second)
	attempt := 0
	for time.Now().Before(deadline) {
		attempt++
		probe, rep, err := trySOCKS5Connect(tn.proxyPort, "127.0.0.1", echo.port, 5*time.Second)
		if err == nil && rep == 0x00 {
			echoRoundTrip(t, probe, "R:", []byte(fmt.Sprintf("after-restart-%d", attempt)), 45*time.Second)
			_ = probe.Close()
			t.Logf("client recovered after server restart on attempt %d (%.1fs)",
				attempt, time.Since(deadline.Add(-150*time.Second)).Seconds())
			if !tn.client.alive() {
				t.Fatal("client process died during recovery")
			}
			return
		}
		if probe != nil {
			_ = probe.Close()
		}
		time.Sleep(2 * time.Second)
	}

	t.Fatalf("client did not recover after server restart within 150s (%d attempts); stale-session recovery is broken", attempt)
}

// TestMalformedUDPTrafficDoesNotDisruptActiveSession injects structurally
// hostile DNS datagrams (the exact shapes that used to amplify allocations)
// into the live server socket while a tunnel session is carrying data.
func TestMalformedUDPTrafficDoesNotDisruptActiveSession(t *testing.T) {
	echo := startEcho(t, "M:")
	tn := startTunnel(t, true, 0)

	conn := mustConnect(t, tn.proxyPort, "127.0.0.1", echo.port)
	echoRoundTrip(t, conn, "M:", []byte("before-flood"), 45*time.Second)

	serverPID := tn.server.cmd.Process.Pid
	baseRSS := processRSSKB(t, serverPID)

	hugeQuestions := make([]byte, 12)
	binary.BigEndian.PutUint16(hugeQuestions[4:6], 0xFFFF)
	hugeAnswers := make([]byte, 12)
	binary.BigEndian.PutUint16(hugeAnswers[6:8], 0xFFFF)
	selfPointerLoop := []byte{0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 0x0C}
	truncatedName := []byte{0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x3F, 'a', 'b'}

	rng := rand.New(rand.NewSource(0x5EED))
	random := make([]byte, 600)
	if _, err := rng.Read(random); err != nil {
		t.Fatalf("rand: %v", err)
	}

	payloads := [][]byte{hugeQuestions, hugeAnswers, selfPointerLoop, truncatedName, random, {}, {0x00}}
	target := fmt.Sprintf("127.0.0.1:%d", tn.udpPort)
	udpConn, err := net.Dial("udp", target)
	if err != nil {
		t.Fatalf("dial server udp: %v", err)
	}
	defer udpConn.Close()

	const packets = 500
	for i := 0; i < packets; i++ {
		if _, err := udpConn.Write(payloads[i%len(payloads)]); err != nil {
			t.Fatalf("inject malformed packet %d: %v", i, err)
		}
	}
	time.Sleep(1 * time.Second)

	if !tn.server.alive() {
		t.Fatal("server died under malformed UDP input")
	}

	// The established session must still carry data byte-exactly.
	echoRoundTrip(t, conn, "", []byte("after-flood"), 45*time.Second)

	afterRSS := processRSSKB(t, serverPID)
	t.Logf("server rss before=%dKB after=%dKB following %d malformed datagrams", baseRSS, afterRSS, packets)
	if grewKB := afterRSS - baseRSS; grewKB > 64*1024 {
		t.Fatalf("server RSS grew by %dKB after %d malformed datagrams; parser amplification suspected", grewKB, packets)
	}
}

// TestThroughputAndTailLatency measures the integrated path rather than a
// component: 256 KiB of random bytes through SOCKS5 over the DNS tunnel, plus
// 30 small round trips for a latency distribution.
func TestThroughputAndTailLatency(t *testing.T) {
	echo := startEcho(t, "P:")
	tn := startTunnel(t, true, 0)

	conn := mustConnect(t, tn.proxyPort, "127.0.0.1", echo.port)
	payload := randomPayload(t, 256*1024)
	started := time.Now()
	echoRoundTrip(t, conn, "P:", payload, 300*time.Second)
	elapsed := time.Since(started)
	_ = conn.Close()

	throughput := float64(len(payload)+len("P:")) / elapsed.Seconds() / 1024
	t.Logf("256KiB round trip in %s (%.1f KiB/s effective, both directions counted once)", elapsed.Round(time.Millisecond), throughput)

	small := mustConnect(t, tn.proxyPort, "127.0.0.1", echo.port)
	defer small.Close()
	const samples = 30
	var worst time.Duration
	var total time.Duration
	for i := 0; i < samples; i++ {
		one := time.Now()
		tag := "P:"
		if i > 0 {
			tag = ""
		}
		echoRoundTrip(t, small, tag, []byte(fmt.Sprintf("ping-%02d", i)), 30*time.Second)
		rtt := time.Since(one)
		total += rtt
		if rtt > worst {
			worst = rtt
		}
	}
	t.Logf("small RTT n=%d mean=%s worst=%s", samples, (total / samples).Round(time.Millisecond), worst.Round(time.Millisecond))
}
