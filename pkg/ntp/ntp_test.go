package ntp

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

// TestNTPTimestampFraction pins the RFC 5905 fraction encoding. The previous
// implementation shifted a uint32 by 32 bits, so this value was always zero and
// every covert packet looked like it had been emitted on an exact second
// boundary.
func TestNTPTimestampFraction(t *testing.T) {
	base := time.Date(2026, 10, 21, 12, 34, 56, 0, time.UTC)

	sec, frac := ntpTimestamp(base)
	if want := uint32(base.Unix() + 2208988800); sec != want {
		t.Fatalf("seconds = %d, want %d", sec, want)
	}
	if frac != 0 {
		t.Fatalf("fraction for an exact second = %d, want 0", frac)
	}

	// Half a second is 2^31 in NTP fixed point.
	_, half := ntpTimestamp(base.Add(500 * time.Millisecond))
	if half < 1<<31-1 || half > 1<<31+1 {
		t.Fatalf("fraction for 0.5s = %d, want ~%d", half, uint32(1)<<31)
	}

	// A millisecond is ~2^32/1000.
	_, milli := ntpTimestamp(base.Add(time.Millisecond))
	wantMilli := uint32((uint64(time.Millisecond) << 32) / uint64(time.Second))
	if milli != wantMilli {
		t.Fatalf("fraction for 1ms = %d, want %d", milli, wantMilli)
	}

	// Never zero for a non-integral timestamp: that is the DPI tell.
	if milli == 0 {
		t.Fatal("fraction collapsed to zero")
	}
}

// TestSendProducesRFC5905Header checks the wire layout produced by Send: a
// 48-byte NTP header followed by one extension field carrying IV+ciphertext.
func TestSendProducesRFC5905Header(t *testing.T) {
	srv, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer srv.Close()

	client, err := NewTunnelClient(srv.LocalAddr().String(), []byte("0123456789abcdef"))
	if err != nil {
		t.Fatalf("new client: %v", err)
	}
	defer client.Close()

	if err := client.Send(context.Background(), 7, 42, []byte("hello")); err != nil {
		t.Fatalf("send: %v", err)
	}

	buf := make([]byte, 2048)
	_ = srv.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := srv.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("read: %v", err)
	}

	if n != 48+4+aes.BlockSize+12+len("hello") {
		t.Fatalf("packet length = %d, want %d", n, 48+4+aes.BlockSize+12+5)
	}
	if buf[0] != 0x23 {
		t.Fatalf("first byte = %#x, want 0x23 (LI=0 VN=4 mode=client)", buf[0])
	}

	sec := binary.BigEndian.Uint32(buf[40:44])
	frac := binary.BigEndian.Uint32(buf[44:48])
	now := time.Now()
	if delta := int64(sec) - (now.Unix() + 2208988800); delta < -5 || delta > 5 {
		t.Fatalf("transmit seconds off by %ds", delta)
	}
	if frac == 0 {
		t.Fatal("transmit fraction is zero: timestamp is not sub-second accurate")
	}

	xType := binary.BigEndian.Uint16(buf[48:50])
	xLen := binary.BigEndian.Uint16(buf[50:52])
	if xType != 0xFD4D {
		t.Fatalf("extension type = %#x, want 0xFD4D", xType)
	}
	if int(xLen) != n-48 {
		t.Fatalf("extension length = %d, want %d", xLen, n-48)
	}
}

// TestRecvRoundTrip exercises the receive path against a frame encrypted with
// the same key, proving that Send and Recv agree on the covert layout.
func TestRecvRoundTrip(t *testing.T) {
	srv, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer srv.Close()

	key := []byte("0123456789abcdef")
	client, err := NewTunnelClient(srv.LocalAddr().String(), key)
	if err != nil {
		t.Fatalf("new client: %v", err)
	}
	defer client.Close()

	payload := []byte("downstream-payload")
	go func() {
		buf := make([]byte, 512)
		_, peer, rerr := srv.ReadFromUDP(buf)
		if rerr != nil {
			return
		}
		frame, ferr := buildFrame(key, 9, 3, payload)
		if ferr != nil {
			return
		}
		_, _ = srv.WriteToUDP(frame, peer)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := client.Send(ctx, 9, 3, []byte("upstream")); err != nil {
		t.Fatalf("send: %v", err)
	}

	pkt, err := client.Recv(ctx, 2*time.Second)
	if err != nil {
		t.Fatalf("recv: %v", err)
	}
	if pkt.SessionID != 9 || pkt.Seq != 3 {
		t.Fatalf("header = (%d,%d), want (9,3)", pkt.SessionID, pkt.Seq)
	}
	if string(pkt.Payload) != string(payload) {
		t.Fatalf("payload = %q, want %q", pkt.Payload, payload)
	}
}

// buildFrame mirrors the server-side encoding: 48-byte NTP header, one
// extension field, AES-CTR over magic|session|seq|payload.
func buildFrame(key []byte, sessionID, seq uint32, data []byte) ([]byte, error) {
	raw := make([]byte, 12+len(data))
	copy(raw[0:4], NTPTunnelMagic)
	binary.BigEndian.PutUint32(raw[4:8], sessionID)
	binary.BigEndian.PutUint32(raw[8:12], seq)
	copy(raw[12:], data)

	block, err := aes.NewCipher(key[:16])
	if err != nil {
		return nil, err
	}
	iv := make([]byte, aes.BlockSize)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return nil, err
	}
	ciphertext := make([]byte, len(raw))
	cipher.NewCTR(block, iv).XORKeyStream(ciphertext, raw)

	header := make([]byte, 48)
	header[0] = 0x24 // server mode
	sec, frac := ntpTimestamp(time.Now())
	binary.BigEndian.PutUint32(header[40:44], sec)
	binary.BigEndian.PutUint32(header[44:48], frac)

	body := append(iv, ciphertext...)
	ext := make([]byte, 4+len(body))
	binary.BigEndian.PutUint16(ext[0:2], 0xFD4D)
	binary.BigEndian.PutUint16(ext[2:4], uint16(len(ext)))
	copy(ext[4:], body)

	return append(header, ext...), nil
}

func TestNewTunnelClientRejectsShortKey(t *testing.T) {
	if _, err := NewTunnelClient("127.0.0.1:123", []byte("short")); err == nil {
		t.Fatal("expected an error for a key shorter than 16 bytes")
	}
}
