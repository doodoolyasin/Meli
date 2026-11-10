package server

import (
	"context"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/blackestwhite/masterdns-qs-tunnel/internal/config"
	"github.com/blackestwhite/masterdns-qs-tunnel/internal/dnswire"
	"github.com/blackestwhite/masterdns-qs-tunnel/internal/protocol"
)

func TestServerHandleQueryDataAndInfo(t *testing.T) {
	cfg := config.ServerConfig{
		Listen:            "127.0.0.1:0",
		Upstream:          "127.0.0.1:19999",
		AllowedDomains:    []string{"tunnel.example.com"},
		InfoSecret:        "secret-test-key",
		ClientIDLength:    4,
		OffsetWidth:       4,
		ReassemblyTimeout: config.Duration(5 * time.Second),
		SessionIdle:       config.Duration(10 * time.Second),
	}

	upstreamAddr, err := net.ResolveUDPAddr("udp", cfg.Upstream)
	if err != nil {
		t.Fatalf("resolve upstream addr: %v", err)
	}
	upstreamConn, err := net.ListenUDP("udp", upstreamAddr)
	if err != nil {
		t.Fatalf("listen upstream: %v", err)
	}
	defer upstreamConn.Close()

	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("create server: %v", err)
	}
	// Bind test dnsConn so handleDNSPacket can reply to DNS queries
	dnsConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatalf("listen dns: %v", err)
	}
	srv.dnsConn = dnsConn
	defer srv.dnsConn.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// 1. Prepare client side frames
	plans, err := protocol.PrepareDomainPlans(cfg.AllowedDomains, 253, 63, cfg.OffsetWidth, cfg.ClientIDLength)
	if err != nil {
		t.Fatalf("prepare domain plans: %v", err)
	}

	clientID := "c123"
	testPayload := []byte("hello world uplink data through masterdns tunnel!")
	dataFrame := protocol.BuildDataFrame(testPayload)

	qnames, _, err := protocol.BuildQNames(dataFrame, 0, clientID, plans, 0, 63, cfg.OffsetWidth)
	if err != nil {
		t.Fatalf("build qnames: %v", err)
	}

	fakeClientAddr := &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 54321}
	for i, qname := range qnames {
		pkt := dnswire.BuildQuery(qname, uint16(i+1), 16) // TXT query
		if err := srv.handleDNSPacket(ctx, pkt, fakeClientAddr); err != nil {
			t.Fatalf("handleDNSPacket failed: %v", err)
		}
	}

	// Verify upstream received the exact payload
	buf := make([]byte, 1024)
	_ = upstreamConn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	n, _, err := upstreamConn.ReadFrom(buf)
	if err != nil {
		t.Fatalf("upstream failed to receive payload: %v", err)
	}
	if string(buf[:n]) != string(testPayload) {
		t.Fatalf("upstream received %q, expected %q", string(buf[:n]), string(testPayload))
	}

	// 2. Test InfoFrame
	info := protocol.InfoFrame{
		ClientIP:   netip.MustParseAddr("1.2.3.4"),
		ClientPort: 8080,
		SpoofIP:    netip.MustParseAddr("5.6.7.8"),
		SpoofPort:  9090,
	}
	infoFrame, err := protocol.BuildInfoFrame(info, cfg.InfoSecret)
	if err != nil {
		t.Fatalf("build info frame: %v", err)
	}
	infoQnames, _, err := protocol.BuildQNames(infoFrame, 1, clientID, plans, 0, 63, cfg.OffsetWidth)
	if err != nil {
		t.Fatalf("build info qnames: %v", err)
	}
	for i, qname := range infoQnames {
		pkt := dnswire.BuildQuery(qname, uint16(i+100), 16)
		if err := srv.handleDNSPacket(ctx, pkt, fakeClientAddr); err != nil {
			t.Fatalf("handleDNSPacket info failed: %v", err)
		}
	}

	srv.sessionMu.Lock()
	sess := srv.sessions[clientID]
	srv.sessionMu.Unlock()
	if sess == nil {
		t.Fatalf("session not created for clientID %s", clientID)
	}
	sessInfo, hasInfo := sess.snapshotInfo()
	if !hasInfo {
		t.Fatalf("hasInfo is false, info frame was not parsed!")
	}
	if sessInfo.ClientIP != info.ClientIP || sessInfo.ClientPort != info.ClientPort {
		t.Fatalf("info mismatch: got %+v, expected %+v", sessInfo, info)
	}
}
