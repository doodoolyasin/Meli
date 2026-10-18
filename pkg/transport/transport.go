package transport

import (
	"context"
	"fmt"
	"net"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// Transport defines the interface for sending and receiving tunnelled data
type Transport interface {
	Send(ctx context.Context, data []byte) (int, error)
	Recv(ctx context.Context) ([]byte, error)
	Close() error
}

// PacketListener is a function called when a DNS packet is received on server side
type PacketListener func([]byte) ([]byte, error)

// DNSTransport implements Transport using raw DNS queries (client) and responses (server)
type DNSTransport struct {
	ServerAddr string
	Domain     string
	conn       *net.UDPConn
	listener   PacketListener
	closed     bool
}

// NewDNSTransport creates a client-side DNS transport targeting serverAddr
func NewDNSTransport(serverAddr string) *DNSTransport {
	return &DNSTransport{
		ServerAddr: serverAddr,
		Domain:     "tunnel.local",
	}
}

// SetListener sets the listener function for server-side operation
func (t *DNSTransport) SetListener(l PacketListener) {
	t.listener = l
}

// Send encapsulates data in a DNS query and transmits it to ServerAddr
func (t *DNSTransport) Send(ctx context.Context, data []byte) (int, error) {
	if t.conn == nil {
		raddr, err := net.ResolveUDPAddr("udp4", t.ServerAddr)
		if err != nil {
			return 0, fmt.Errorf("failed to resolve server address: %w", err)
		}
		c, err := net.DialUDP("udp4", nil, raddr)
		if err != nil {
			return 0, fmt.Errorf("failed to dial server: %w", err)
		}
		t.conn = c
	}

	// Craft DNS query with payload in question or OPT RR
	msg := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:               0x4D4C, // 'ML'
			RecursionDesired: true,
		},
		Questions: []dnsmessage.Question{
			{
				Name:  dnsmessage.MustNewName(t.Domain + "."),
				Type:  dnsmessage.TypeTXT,
				Class: dnsmessage.ClassINET,
			},
		},
	}

	packed, err := msg.Pack()
	if err != nil {
		return 0, err
	}

	// Append EDNS0 OPT RR carrying the raw payload (RFC 6891 option code 65001)
	optHeader := []byte{
		0x00,       // root name
		0x00, 0x29, // OPT type (41)
		0x10, 0x00, // payload size 4096
		0x00, 0x00, // ext RCODE
		byte((len(data) + 4) >> 8), byte(len(data) + 4), // RDLENGTH
		0xFD, 0xE9, // Option Code 65001
		byte(len(data) >> 8), byte(len(data)), // Option Length
	}
	packed = append(packed, optHeader...)
	packed = append(packed, data...)

	// Update ARCOUNT to 1 in header (bytes 10-11)
	packed[10] = 0x00
	packed[11] = 0x01

	if deadline, ok := ctx.Deadline(); ok {
		t.conn.SetWriteDeadline(deadline)
	} else {
		t.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	}

	return t.conn.Write(packed)
}

// Recv receives a DNS response packet and extracts payload
func (t *DNSTransport) Recv(ctx context.Context) ([]byte, error) {
	if t.conn == nil {
		return nil, fmt.Errorf("transport not connected")
	}

	buf := make([]byte, 4096)
	if deadline, ok := ctx.Deadline(); ok {
		t.conn.SetReadDeadline(deadline)
	} else {
		t.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	}

	n, err := t.conn.Read(buf)
	if err != nil {
		return nil, err
	}

	var resp dnsmessage.Message
	if err := resp.Unpack(buf[:n]); err != nil {
		return buf[:n], nil
	}

	for _, ans := range resp.Answers {
		if ans.Header.Type == dnsmessage.TypeTXT {
			if txt, ok := ans.Body.(*dnsmessage.TXTResource); ok && len(txt.TXT) > 0 {
				return []byte(txt.TXT[0]), nil
			}
		}
	}

	return buf[:n], nil
}

// Serve starts a UDP DNS server listener on addr and passes packets to listener callback
func (t *DNSTransport) Serve(ctx context.Context, addr string) error {
	uaddr, err := net.ResolveUDPAddr("udp4", addr)
	if err != nil {
		return err
	}
	pc, err := net.ListenUDP("udp4", uaddr)
	if err != nil {
		return err
	}
	defer pc.Close()

	buf := make([]byte, 4096)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			pc.SetReadDeadline(time.Now().Add(1 * time.Second))
			n, src, err := pc.ReadFrom(buf)
			if err != nil {
				continue
			}

			if t.listener != nil {
				respData, err := t.listener(buf[:n])
				if err == nil && len(respData) > 0 {
					pc.WriteTo(respData, src)
				}
			}
		}
	}
}

// Close closes the transport connection
func (t *DNSTransport) Close() error {
	t.closed = true
	if t.conn != nil {
		return t.conn.Close()
	}
	return nil
}