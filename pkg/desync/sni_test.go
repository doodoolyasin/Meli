package desync

import (
	"crypto/tls"
	"net"
	"testing"
	"time"
)

// generateTestClientHello constructs a real TLS 1.2/1.3 ClientHello using crypto/tls
func generateTestClientHello(sni string) ([]byte, error) {
	var captured []byte
	cfg := &tls.Config{
		ServerName:         sni,
		InsecureSkipVerify: true,
	}
	cli := tls.Client(&recorderConn{onWrite: func(b []byte) {
		if captured == nil {
			captured = append([]byte(nil), b...)
		}
	}}, cfg)
	_ = cli.Handshake()
	return captured, nil
}

type recorderConn struct {
	onWrite func([]byte)
}

func (r *recorderConn) Read(b []byte) (n int, err error)   { return 0, net.ErrClosed }
func (r *recorderConn) Write(b []byte) (n int, err error)  {
	if r.onWrite != nil {
		r.onWrite(b)
	}
	return len(b), nil
}
func (r *recorderConn) Close() error                       { return nil }
func (r *recorderConn) LocalAddr() net.Addr                { return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 12345} }
func (r *recorderConn) RemoteAddr() net.Addr               { return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 443} }
func (r *recorderConn) SetDeadline(t time.Time) error      { return nil }
func (r *recorderConn) SetReadDeadline(t time.Time) error  { return nil }
func (r *recorderConn) SetWriteDeadline(t time.Time) error { return nil }

func TestRewriteTLSClientHelloSNI(t *testing.T) {
	origSNI := "blocked-domain.com"
	raw, err := generateTestClientHello(origSNI)
	if err != nil || len(raw) == 0 {
		t.Fatalf("failed to generate authentic ClientHello: %v", err)
	}

	newSNI := "bale.ir"
	rewritten, err := RewriteTLSClientHelloSNI(raw, newSNI)
	if err != nil {
		t.Fatalf("RewriteTLSClientHelloSNI failed: %v", err)
	}

	// Count occurrences of server_name extension (0x0000)
	// Extensions start after SessionID, CipherSuites, CompressionMethods
	sessionIDLen := int(rewritten[43])
	offset := 44 + sessionIDLen
	cipherSuitesLen := int(int(rewritten[offset])<<8 | int(rewritten[offset+1]))
	offset += 2 + cipherSuitesLen
	compressionMethodsLen := int(rewritten[offset])
	offset += 1 + compressionMethodsLen
	extsLen := int(int(rewritten[offset])<<8 | int(rewritten[offset+1]))
	offset += 2

	exts := rewritten[offset : offset+extsLen]
	sniExtensionCount := 0
	p := 0
	for p+4 <= len(exts) {
		extType := int(exts[p])<<8 | int(exts[p+1])
		extLen := int(exts[p+2])<<8 | int(exts[p+3])
		if extType == 0x0000 {
			sniExtensionCount++
		}
		p += 4 + extLen
	}

	if sniExtensionCount != 1 {
		t.Fatalf("expected exactly 1 SNI extension, got %d (duplicate SNI extension bug!)", sniExtensionCount)
	}
}

func TestBadChecksumPacket(t *testing.T) {
	dummyPkt := make([]byte, 40)
	dummyPkt[0] = 0x45 // IPv4, 20 bytes header
	out := BadChecksumPacket(dummyPkt)
	if out[36] != 0xDE || out[37] != 0xAD {
		t.Fatalf("expected bad checksum 0xDEAD at offset 36-37, got %x %x", out[36], out[37])
	}
}

func TestEDNS0Option(t *testing.T) {
	// 12-byte minimal DNS header
	dnsPkt := make([]byte, 12)
	dnsPkt[0] = 0x12 // ID
	dnsPkt[1] = 0x34
	optData := []byte("covert-padding-payload")
	out := EDNS0Option(dnsPkt, 0x000F, optData)
	if len(out) <= len(dnsPkt) {
		t.Fatalf("expected EDNS0 option to expand packet, got len %d", len(out))
	}
}

func TestQUICDCIDStego(t *testing.T) {
	data := []byte("secret-key-123456789")
	quicPkt := QUICDCIDStego(data)
	if len(quicPkt) < 30 {
		t.Fatalf("quic packet too short: %d", len(quicPkt))
	}
	if quicPkt[0] != 0xC0 {
		t.Fatalf("expected QUIC Initial long header 0xC0, got 0x%X", quicPkt[0])
	}
}
