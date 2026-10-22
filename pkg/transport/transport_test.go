package transport

import (
	"context"
	"net"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func TestDNSTransportSendRecv(t *testing.T) {
	// Start a mock DNS server
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	serverAddr := pc.LocalAddr().String()

	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			var q dnsmessage.Message
			_ = q.Unpack(buf[:n])

			// Build reply with TXT answer
			resp := dnsmessage.Message{
				Header: dnsmessage.Header{
					ID:       q.Header.ID,
					Response: true,
				},
				Questions: q.Questions,
				Answers: []dnsmessage.Resource{
					{
						Header: dnsmessage.ResourceHeader{
							Name:  q.Questions[0].Name,
							Type:  dnsmessage.TypeTXT,
							Class: dnsmessage.ClassINET,
							TTL:   60,
						},
						Body: &dnsmessage.TXTResource{
							TXT: []string{"pong-response-data"},
						},
					},
				},
			}
			packed, _ := resp.Pack()
			_, _ = pc.WriteTo(packed, addr)
		}
	}()

	tr := NewDNSTransport(serverAddr)
	defer tr.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	data := []byte("ping-request-payload")
	n, err := tr.Send(ctx, data)
	if err != nil || n == 0 {
		t.Fatalf("Send failed: %v", err)
	}

	reply, err := tr.Recv(ctx)
	if err != nil {
		t.Fatalf("Recv failed: %v", err)
	}

	if string(reply) != "pong-response-data" {
		t.Fatalf("expected 'pong-response-data', got %q", string(reply))
	}
}
