package tunnel

import (
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/doodoolyasin/meli/pkg/tunnel/basecodec"
	"github.com/doodoolyasin/meli/pkg/tunnel/proto"
	"golang.org/x/net/dns/dnsmessage"
)

// Config holds the configuration for a tunnel connection.
type Config struct {
	// LocalAddr is the local address to bind to (e.g. "0.0.0.0:5053").
	LocalAddr string
	// RemoteAddr is the remote DNS server address (e.g. "8.8.8.8:53").
	RemoteAddr string
	// Domain is the domain name used for tunneling (e.g. "m.dolphin.work.gd").
	Domain string
	// TargetAddr is the target address for server-side forwarding (e.g. "127.0.0.1:8080").
	TargetAddr string
	// Key is the pre-shared encryption key.
	Key []byte
	// Timeout is the timeout for DNS operations.
	Timeout time.Duration
}

// Client represents an encrypted, compressed DNS tunnel connection implementing io.ReadWriteCloser.
type Client struct {
	config      Config
	remoteUDP   *net.UDPAddr
	conn        *net.UDPConn
	reassembler *proto.ReassemblyBuffer
	sessionID   uint8
	seqCounter  uint32
	readBuf     []byte
	readPos     int
	readLen     int
	mu          sync.Mutex
	ctx         context.Context
	cancel      context.CancelFunc
	closed      bool
}

// NewClient creates a new client tunnel connection.
func NewClient(config Config, _ net.Conn) (*Client, error) {
	if config.RemoteAddr == "" {
		return nil, fmt.Errorf("client config RemoteAddr is required")
	}
	if config.Domain == "" {
		return nil, fmt.Errorf("client config Domain is required")
	}
	if len(config.Key) == 0 {
		config.Key = []byte("137e43fd04a5b9dfdaf29e103fddcce2")
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
		config:      config,
		remoteUDP:   remoteAddr,
		conn:        udpConn,
		reassembler: proto.NewReassemblyBuffer(),
		sessionID:   uint8(time.Now().UnixNano() & 0xFF),
		readBuf:     make([]byte, 65536),
		ctx:         ctx,
		cancel:      cancel,
	}, nil
}

// Write compresses, fragments, encrypts, and sends payload over DNS queries.
func (c *Client) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.closed {
		return 0, io.ErrClosedPipe
	}

	// Step 1: Compress payload
	compressed := proto.Compress(p)

	// Step 2: Fragment into chunks of max 90 bytes so that after header (7B) = 97B -> Base32 = 156 chars (well within 253 limit)
	chunkSize := 90
	totalLen := len(compressed)
	totFrags := uint8((totalLen + chunkSize - 1) / chunkSize)
	if totFrags == 0 {
		totFrags = 1
	}

	seq := uint16(atomic.AddUint32(&c.seqCounter, 1))

	for frag := uint8(0); frag < totFrags; frag++ {
		start := int(frag) * chunkSize
		end := start + chunkSize
		if end > totalLen {
			end = totalLen
		}

		rawChunk := compressed[start:end]

		// Step 3: Encrypt chunk with AES-CTR
		iv := proto.DeriveIV(c.sessionID, seq, frag)
		encChunk, err := proto.CryptAESCTR(c.config.Key, iv, rawChunk)
		if err != nil {
			return 0, err
		}

		// Step 4: Pack into Meli Frame
		frame := &proto.Frame{
			SessionID: c.sessionID,
			Seq:       seq,
			Frag:      frag,
			Tot:       totFrags,
			Flags:     0x00,
			Payload:   encChunk,
		}
		packedFrame := frame.Pack()

		// Step 5: Encode in LowerBase32
		encoded := basecodec.EncodeLowerBase32(packedFrame)

		// Step 6: Format into DNS Labels (max 63 chars each)
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
				ID:               seq,
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

		packedDNS, err := msg.Pack()
		if err != nil {
			return 0, err
		}

		c.conn.SetWriteDeadline(time.Now().Add(c.config.Timeout))
		if _, err := c.conn.Write(packedDNS); err != nil {
			return 0, err
		}
	}

	return len(p), nil
}

// Read reads, decodes Base32, decrypts, reassembles, and decompresses server responses.
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
						if err != nil || len(decoded) < proto.HeaderLen {
							continue
						}

						frame, err := proto.Unpack(decoded)
						if err != nil {
							continue
						}

						iv := proto.DeriveIV(frame.SessionID, frame.Seq, frame.Frag)
						decChunk, err := proto.CryptAESCTR(c.config.Key, iv, frame.Payload)
						if err != nil {
							continue
						}

						frame.Payload = decChunk
						fullMsg := c.reassembler.Add(frame)
						if fullMsg != nil {
							decompressed, err := proto.Decompress(fullMsg)
							if err == nil && len(decompressed) > 0 {
								c.readBuf = append(c.readBuf[:0], decompressed...)
								c.readLen = len(decompressed)
								c.readPos = 0
								break
							}
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

// Server handles incoming DNS queries with AES-CTR decryption, decompressing, forwarding, and responding with TXT.
type Server struct {
	config      Config
	packetConn  net.PacketConn
	reassembler *proto.ReassemblyBuffer
	sessions    map[string]net.Conn
	mu          sync.Mutex
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
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
	if len(config.Key) == 0 {
		config.Key = []byte("137e43fd04a5b9dfdaf29e103fddcce2")
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
		config:      config,
		packetConn:  packetConn,
		reassembler: proto.NewReassemblyBuffer(),
		sessions:    make(map[string]net.Conn),
		ctx:         ctx,
		cancel:      cancel,
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

			decoded, err := basecodec.DecodeLowerBase32String(sub)
			if err != nil || len(decoded) < proto.HeaderLen {
				continue
			}

			frame, err := proto.Unpack(decoded)
			if err != nil {
				continue
			}

			// Decrypt
			iv := proto.DeriveIV(frame.SessionID, frame.Seq, frame.Frag)
			decChunk, err := proto.CryptAESCTR(s.config.Key, iv, frame.Payload)
			if err != nil {
				continue
			}
			frame.Payload = decChunk

			// Reassemble if multi-fragment
			fullPayload := s.reassembler.Add(frame)
			if fullPayload == nil {
				// Waiting for remaining fragments; acknowledge receipt
				ackMsg := dnsmessage.Message{
					Header: dnsmessage.Header{
						ID:       msg.Header.ID,
						Response: true,
					},
					Questions: []dnsmessage.Question{q},
				}
				if packed, err := ackMsg.Pack(); err == nil {
					s.packetConn.WriteTo(packed, clientAddr)
				}
				continue
			}

			// Decompress payload
			decompressed, err := proto.Decompress(fullPayload)
			if err != nil || len(decompressed) == 0 {
				continue
			}

			// Forward to TargetAddr (TCP)
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
				_, _ = conn.Write(decompressed)

				respBuf := make([]byte, 1024)
				rn, rerr := conn.Read(respBuf)
				if rerr == nil && rn > 0 {
					replyData = respBuf[:rn]
				}
			}

			// Prepare response
			respMsg := dnsmessage.Message{
				Header: dnsmessage.Header{
					ID:       msg.Header.ID,
					Response: true,
				},
				Questions: []dnsmessage.Question{q},
			}

			if len(replyData) > 0 {
				// Compress and encrypt reply
				compressedReply := proto.Compress(replyData)
				repIV := proto.DeriveIV(frame.SessionID, frame.Seq, 0)
				encReply, err := proto.CryptAESCTR(s.config.Key, repIV, compressedReply)
				if err == nil {
					repFrame := &proto.Frame{
						SessionID: frame.SessionID,
						Seq:       frame.Seq,
						Frag:      0,
						Tot:       1,
						Flags:     0x00,
						Payload:   encReply,
					}
					packedRep := repFrame.Pack()
					encodedReply := basecodec.EncodeLowerBase32(packedRep)

					respMsg.Answers = append(respMsg.Answers, dnsmessage.Resource{
						Header: dnsmessage.ResourceHeader{
							Name:  q.Name,
							Type:  dnsmessage.TypeTXT,
							Class: dnsmessage.ClassINET,
							TTL:   10,
						},
						Body: &dnsmessage.TXTResource{
							TXT: []string{encodedReply},
						},
					})
				}
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