package udpserver

import (
	"testing"

	VpnProto "github.com/doodoolyasin/meli/internal/vpnproto"
	Enums "github.com/doodoolyasin/meli/internal/enums"
	"github.com/doodoolyasin/meli/internal/config"
)

func TestRejectProtocolMismatchedSynTruthTable(t *testing.T) {
	cases := []struct {
		name       string
		protocol   string
		packetType uint8
		want       bool
	}{
		{"tcp mode rejects socks5 syn", "TCP", Enums.PACKET_SOCKS5_SYN, true},
		{"tcp mode accepts stream syn", "TCP", Enums.PACKET_STREAM_SYN, false},
		{"socks5 mode rejects stream syn", "SOCKS5", Enums.PACKET_STREAM_SYN, true},
		{"socks5 mode accepts socks5 syn", "SOCKS5", Enums.PACKET_SOCKS5_SYN, false},
		{"unknown protocol accepts everything", "UDP", Enums.PACKET_STREAM_SYN, false},
		{"data is never rejected", "SOCKS5", Enums.PACKET_STREAM_DATA, false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := &Server{cfg: config.ServerConfig{ProtocolType: tc.protocol}}
			if got := s.rejectProtocolMismatchedSyn(tc.packetType); got != tc.want {
				t.Fatalf("protocol=%s packet=%d: got %v, want %v", tc.protocol, tc.packetType, got, tc.want)
			}
		})
	}
}

func TestProtocolMismatchReplyTypeMatchesRequestProtocol(t *testing.T) {
	if got := protocolMismatchReplyType(Enums.PACKET_SOCKS5_SYN); got != Enums.PACKET_SOCKS5_CONNECT_FAIL {
		t.Fatalf("SOCKS5_SYN should fail with SOCKS5_CONNECT_FAIL, got %d", got)
	}
	if got := protocolMismatchReplyType(Enums.PACKET_STREAM_SYN); got != Enums.PACKET_STREAM_CONNECT_FAIL {
		t.Fatalf("STREAM_SYN should fail with STREAM_CONNECT_FAIL, got %d", got)
	}
}

// A SYN that is rejected for protocol mismatch must produce a queued
// CONNECT_FAIL for the requesting stream, so the client fails fast instead of
// retransmitting into a hang.
func TestRejectProtocolMismatchedSynQueuesConnectFailure(t *testing.T) {
	s := &Server{
		cfg:      config.ServerConfig{ProtocolType: "SOCKS5"},
		sessions: newSessionStore(8, 32),
	}
	record := newTestSessionRecord(3)
	s.sessions.byID[record.ID] = record
	s.sessions.activeCount = 1

	packet := VpnProto.Packet{
		SessionID:     record.ID,
		SessionCookie: record.Cookie,
		PacketType:    Enums.PACKET_STREAM_SYN,
		StreamID:      11,
		HasStreamID:   true,
		SequenceNum:   4,
		HasSequenceNum: true,
	}
	if !s.rejectProtocolMismatchedSyn(packet.PacketType) {
		t.Fatal("expected STREAM_SYN to be rejected in SOCKS5 mode")
	}
	s.rejectProtocolMismatchedSynStream(packet, packet.PacketType, packet.StreamID, packet.SequenceNum)

	queued, _, ok := record.OrphanQueue.Pop()
	if !ok {
		t.Fatal("expected a queued CONNECT_FAIL packet")
	}
	if queued.PacketType != Enums.PACKET_STREAM_CONNECT_FAIL {
		t.Fatalf("got packet type %d, want STREAM_CONNECT_FAIL", queued.PacketType)
	}
	if queued.StreamID != 11 {
		t.Fatalf("got stream %d, want 11", queued.StreamID)
	}
}
