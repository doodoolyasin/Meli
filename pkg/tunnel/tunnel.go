package tunnel

import (
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/doodoolyasin/meli/pkg/tunnel/basecodec"
	"golang.org/x/net/dns/dnsmessage"
)

// Config holds the configuration for a tunnel connection.
type Config struct {
	// LocalAddr is the local address to bind to (e.g. "0.0.0.0:5053").
	LocalAddr string
	// RemoteAddr is the remote DNS server address (e.g. "8.8.8.8:53").
	RemoteAddr string
	// Domain is the domain name used for tunneling (e.g. "tunnel.example.com").
	Domain string
	// TargetAddr is the target address for server-side forwarding (e.g. "127.0.0.1:8080").
	TargetAddr string
	// Timeout is the timeout for DNS operations.
	Timeout time.Duration
}

// Client represents a client-side DNS tunnel connection implementing io.ReadWriteCloser.
type Client struct {
	config     Config
	remoteUDP  *net.UDPAddr
	conn       *net.UDPConn
	readBuf    []byte
	readPos    int
	readLen    int
	mu         sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	closed     bool
}

// NewClient creates a new client tunnel connection.
func NewClient(config Config, _ net.Conn) (*Client, error) {
	if config.RemoteAddr == "" {
		return nil, fmt.Errorf("client config RemoteAddr is required")
	}
	if config.Domain == "" {
		return nil, fmt.Errorf("client config Domain is required")
	}
	if config.Timeout <= 0 {
		config.Timeout = 5 * time.Second
	}

	remoteAddr, err := net.ResolveUDPAddr("udp4", config.RemoteAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve remote address: %w", err)
	}

	udpConn, err := net.DialUDP("udp4", nil, remoteAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to DNS server: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Client{
		config:    config,
		remoteUDP: remoteAddr,
		conn:      udpConn,
		readBuf:   make([]byte, 8192),
		ctx:       ctx,
		cancel:    cancel,
	}, nil
}

// Write takes payload bytes, encodes them in Base32 chunks, and sends them via DNS queries.
func (c *Client) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.closed {
		return 0, io.ErrClosedPipe
	}

	totalWritten := len(p)
	// Chunk size 100 bytes -> 160 chars Base32, safe for DNS labels
	chunkSize := 100
	offset := 0

	for offset < len(p) {
		end := offset + chunkSize
		if end > len(p) {
			end = len(p)
		}

		chunk := p[offset:end]
		encoded := basecodec.EncodeLowerBase32(chunk)

		// Split into 63-char DNS labels
		var labels []string
		for len(encoded) > 0 {
			take := 63
			if len(encoded) < take {
				take = len(encoded)
			}
			labels = append(labels, encoded[:take])
			encoded = encoded[take:]
		}

		var qnameBuilder strings.Builder
		for i, label := range labels {
			if i > 0 {
				qnameBuilder.WriteString(".")
			}
			qnameBuilder.WriteString(label)
		}
		if qnameBuilder.Len() > 0 {
			qnameBuilder.WriteString(".")
		}
		qnameBuilder.WriteString(c.config.Domain)
		qname := qnameBuilder.String()

		msg := dnsmessage.Message{
			Header: dnsmessage.Header{
				ID:               0x1337,
				RecursionDesired: true,
			},
			Questions: []dnsmessage.Question{
				{
					Name:  dnsmessage.MustNewName(qname + "."),
					Type:  dnsmessage.TypeTXT,
					Class: dnsmessage.ClassINET,
				},
			},
		}

		packed, err := msg.Pack()
		if err != nil {
			return offset, err
		}

		c.conn.SetWriteDeadline(time.Now().Add(c.config.Timeout))
		if _, err := c.conn.Write(packed); err != nil {
			return offset, err
		}

		offset = end
	}

	return totalWritten, nil
}

// Read reads and parses incoming DNS responses and decodes their TXT payloads.
func (c *Client) Read(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.closed {
		return 0, io.EOF
	}

	for c.readPos >= c.readLen {
		c.conn.SetReadDeadline(time.Now().Add(c.config.Timeout))
		rawBuf := make([]byte, 4096)
		n, err := c.conn.Read(rawBuf)
		if err != nil {
			return 0, err
		}

		var resp dnsmessage.Message
		if err := resp.Unpack(rawBuf[:n]); err != nil {
			continue
		}

		for _, ans := range resp.Answers {
			if ans.Header.Type == dnsmessage.TypeTXT {
				if txt, ok := ans.Body.(*dnsmessage.TXTResource); ok {
					for _, item := range txt.TXT {
						decoded, err := basecodec.DecodeLowerBase32String(item)
						if err == nil && len(decoded) > 0 {
							c.readBuf = append(c.readBuf[:0], decoded...)
							c.readLen = len(decoded)
							c.readPos = 0
							break
						}
					}
				}
			}
		}
	}

	n := copy(p, c.readBuf[c.readPos:c.readLen])
	c.readPos += n
	return n, nil
}

// Close terminates the client connection.
func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	c.cancel()
	return c.conn.Close()
}

// Server handles incoming DNS queries, forwards payload to TargetAddr, and responds with TXT.
type Server struct {
	config     Config
	packetConn net.PacketConn
	sessions   map[string]net.Conn
	mu         sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
}

// NewServer creates a new server tunnel connection.
func NewServer(config Config) (*Server, error) {
	if config.LocalAddr == "" {
		return nil, fmt.Errorf("server config LocalAddr is required")
	}
	if config.Domain == "" {
		return nil, fmt.Errorf("server config Domain is required")
	}
	if config.TargetAddr == "" {
		return nil, fmt.Errorf("server config TargetAddr is required")
	}
	if config.Timeout <= 0 {
		config.Timeout = 5 * time.Second
	}

	addr, err := net.ResolveUDPAddr("udp4", config.LocalAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve local address: %w", err)
	}

	packetConn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		return nil, fmt.Errorf("failed to listen on UDP: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	s := &Server{
		config:     config,
		packetConn: packetConn,
		sessions:   make(map[string]net.Conn),
		ctx:        ctx,
		cancel:     cancel,
	}

	s.wg.Add(1)
	go s.serve()

	return s, nil
}

func (s *Server) serve() {
	defer s.wg.Done()
	buf := make([]byte, 4096)

	for {
		select {
		case <-s.ctx.Done():
			return
		default:
			s.packetConn.SetReadDeadline(time.Now().Add(1 * time.Second))
			n, clientAddr, err := s.packetConn.ReadFrom(buf)
			if err != nil {
				continue
			}

			var msg dnsmessage.Message
			if err := msg.Unpack(buf[:n]); err != nil || len(msg.Questions) == 0 {
				continue
			}

			q := msg.Questions[0]
			qname := q.Name.String()
			trimmed := strings.TrimSuffix(qname, ".")

			if !strings.HasSuffix(trimmed, s.config.Domain) {
				continue
			}

			sub := strings.TrimSuffix(trimmed, "."+s.config.Domain)
			sub = strings.ReplaceAll(sub, ".", "")

			payload, err := basecodec.DecodeLowerBase32String(sub)
			if err != nil || len(payload) == 0 {
				continue
			}

			// Forward payload to TargetAddr
			clientKey := clientAddr.String()
			s.mu.Lock()
			conn, exists := s.sessions[clientKey]
			if !exists || conn == nil {
				c, dialErr := net.DialTimeout("tcp", s.config.TargetAddr, 3*time.Second)
				if dialErr == nil {
					s.sessions[clientKey] = c
					conn = c
				}
			}
			s.mu.Unlock()

			var replyData []byte
			if conn != nil {
				conn.SetDeadline(time.Now().Add(2 * time.Second))
				_, _ = conn.Write(payload)

				respBuf := make([]byte, 512)
				rn, rerr := conn.Read(respBuf)
				if rerr == nil && rn > 0 {
					replyData = respBuf[:rn]
				}
			}

			// Send DNS response with TXT answer
			respMsg := dnsmessage.Message{
				Header: dnsmessage.Header{
					ID:       msg.Header.ID,
					Response: true,
				},
				Questions: []dnsmessage.Question{q},
			}

			if len(replyData) > 0 {
				encodedReply := basecodec.EncodeLowerBase32(replyData)
				respMsg.Answers = append(respMsg.Answers, dnsmessage.Resource{
					Header: dnsmessage.ResourceHeader{
						Name:  q.Name,
						Type:  dnsmessage.TypeTXT,
						Class: dnsmessage.ClassINET,
						TTL:   30,
					},
					Body: &dnsmessage.TXTResource{
						TXT: []string{encodedReply},
					},
				})
			}

			packed, err := respMsg.Pack()
			if err == nil {
				s.packetConn.WriteTo(packed, clientAddr)
			}
		}
	}
}

// Close terminates the server.
func (s *Server) Close() error {
	s.cancel()
	s.wg.Wait()
	s.mu.Lock()
	for _, c := range s.sessions {
		if c != nil {
			c.Close()
		}
	}
	s.mu.Unlock()
	return s.packetConn.Close()
}