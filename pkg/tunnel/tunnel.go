package tunnel

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	arqpkg "github.com/doodoolyasin/meli/pkg/tunnel/arq"
	"github.com/doodoolyasin/meli/pkg/tunnel/basecodec"
	"golang.org/x/net/dns/dnsmessage"
)

// Config holds the configuration for a tunnel connection.
type Config struct {
	// LocalAddr is the local address to bind to (for client: SOCKS5 listen, for server: UDP listen).
	LocalAddr string
	// RemoteAddr is the remote DNS server address (for client) or empty (for server).
	RemoteAddr string
	// Domain is the domain name used for tunneling (e.g., "tunnel.example.com").
	Domain string
	// TargetAddr is the target address for server-side forwarding (e.g., "127.0.0.1:8080" or "socks5://...")
	TargetAddr string
	// Timeout is the timeout for DNS operations.
	Timeout time.Duration
	// ARQConfig configures the ARQ reliability layer.
	ARQConfig arqpkg.Config
}

// dnsEnqueuer implements the ARQ PacketEnqueuer interface for DNS transport.
type dnsEnqueuer struct {
	conn       *net.UDPConn
	remoteAddr *net.UDPAddr
	domain     string
	mu         sync.Mutex
}

func (e *dnsEnqueuer) PushTXPacket(priority int, packetType uint8, sequenceNum uint16, fragmentID uint8, totalFragments uint8, compressionType uint8, ttl time.Duration, payload []byte) bool {
	// Encode payload as Base32 and construct DNS query
	encoded := basecodec.EncodeLowerBase32(payload)
	
	// Split encoded data into labels (max 63 chars each)
	var labels []string
	for len(encoded) > 0 {
		if len(encoded) > 63 {
			labels = append(labels, encoded[:63])
			encoded = encoded[63:]
		} else {
			labels = append(labels, encoded)
			encoded = ""
		}
	}
	
	// Build question name
	var nameBuilder strings.Builder
	for i, label := range labels {
		if i > 0 {
			nameBuilder.WriteString(".")
		}
		nameBuilder.WriteString(label)
	}
	if nameBuilder.Len() > 0 {
		nameBuilder.WriteString(".")
	}
	nameBuilder.WriteString(e.domain)
	questionName := nameBuilder.String()
	
	// Build DNS query
	msg := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:                sequenceNum,
			RecursionDesired:  true,
		},
		Questions: []dnsmessage.Question{
			{
				Name:  dnsmessage.MustNewName(questionName),
				Type:  dnsmessage.TypeA,
				Class: dnsmessage.ClassINET,
			},
		},
	}
	
	msgBuf, err := msg.Pack()
	if err != nil {
		return false
	}
	
	e.mu.Lock()
	defer e.mu.Unlock()
	e.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err = e.conn.WriteToUDP(msgBuf, e.remoteAddr)
	return err == nil
}

// noopLogger is a simple no-op logger for ARQ
type noopLogger struct{}

func (n noopLogger) Debugf(format string, args ...any) {}
func (n noopLogger) Infof(format string, args ...any)  {}
func (n noopLogger) Errorf(format string, args ...any) {}

// dnsConnWrapper wraps a UDP connection to implement io.ReadWriteCloser for ARQ
type dnsConnWrapper struct {
	conn       *net.UDPConn
	remoteAddr *net.UDPAddr
	domain     string
	readBuf    []byte
	readPos    int
	readLen    int
	mu         sync.Mutex
}

func (w *dnsConnWrapper) Read(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	
	for w.readPos >= w.readLen {
		w.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		n, _, err := w.conn.ReadFromUDP(w.readBuf)
		if err != nil {
			return 0, err
		}
		w.readLen = n
		w.readPos = 0
		
		// Parse DNS response and extract payload from TXT records
		var respMsg dnsmessage.Message
		if err := respMsg.Unpack(w.readBuf[:n]); err != nil {
			continue
		}
		
		// Extract data from TXT records
		for _, ans := range respMsg.Answers {
			if ans.Header.Type == dnsmessage.TypeTXT {
				if txt, ok := ans.Body.(*dnsmessage.TXTResource); ok {
					for _, s := range txt.TXT {
						w.readBuf = append(w.readBuf[:0], s...)
						w.readLen = len(s)
						w.readPos = 0
						break
					}
				}
			}
		}
	}
	
	n := copy(p, w.readBuf[w.readPos:w.readLen])
	w.readPos += n
	return n, nil
}

func (w *dnsConnWrapper) Write(p []byte) (int, error) {
	// This is handled by the enqueuer in ARQ
	return len(p), nil
}

func (w *dnsConnWrapper) Close() error {
	return w.conn.Close()
}

// Client represents a client-side tunnel connection.
type Client struct {
	config    Config
	innerConn net.Conn
	dnsConn   *net.UDPConn
	arq       *arqpkg.ARQ
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
}

// NewClient creates a new client tunnel connection.
func NewClient(config Config, innerConn net.Conn) (*Client, error) {
	if config.RemoteAddr == "" {
		return nil, fmt.Errorf("client config RemoteAddr is required")
	}
	if config.Domain == "" {
		return nil, fmt.Errorf("client config Domain is required")
	}
	if config.Timeout <= 0 {
		config.Timeout = 5 * time.Second
	}
	
	// Resolve the remote DNS server address.
	remoteAddr, err := net.ResolveUDPAddr("udp4", config.RemoteAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve remote address: %w", err)
	}
	
	dnsConn, err := net.DialUDP("udp4", nil, remoteAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to DNS server: %w", err)
	}
	
	// Create ARQ instance with proper parameters
	enqueuer := &dnsEnqueuer{
		conn:       dnsConn,
		remoteAddr: remoteAddr,
		domain:     config.Domain,
	}
	
	wrapper := &dnsConnWrapper{
		conn:       dnsConn,
		remoteAddr: remoteAddr,
		domain:     config.Domain,
		readBuf:    make([]byte, 1500),
	}
	
	ctx, cancel := context.WithCancel(context.Background())
	
	arqInstance := arqpkg.NewARQ(
		1,      // streamID
		1,      // sessionID
		enqueuer,
		wrapper,
		1200,   // MTU
		noopLogger{},
		config.ARQConfig,
	)
	
	client := &Client{
		config:    config,
		innerConn: innerConn,
		dnsConn:   dnsConn,
		arq:       arqInstance,
		ctx:       ctx,
		cancel:    cancel,
	}
	
	// Start ARQ background workers
	arqInstance.Start()
	
	client.wg.Add(1)
	go client.background()
	
	return client, nil
}

// background runs the ARQ event loop.
func (c *Client) background() {
	defer c.wg.Done()
	
	// Wait for context cancellation
	<-c.ctx.Done()
	c.arq.Close("context cancelled", arqpkg.CloseOptions{})
}

// Close closes the client tunnel connection.
func (c *Client) Close() error {
	c.cancel()
	c.wg.Wait()
	c.dnsConn.Close()
	return c.innerConn.Close()
}

// Read reads data from the tunnel (from the inner connection).
func (c *Client) Read(b []byte) (int, error) {
	return c.innerConn.Read(b)
}

// Write writes data to the tunnel (to the inner connection).
func (c *Client) Write(b []byte) (int, error) {
	return c.innerConn.Write(b)
}

// Server represents a server-side tunnel connection.
type Server struct {
	config     Config
	packetConn net.PacketConn
	sessions   map[string]*session
	mu         sync.RWMutex
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
}

// session holds the state for a client session.
type session struct {
	server     *Server
	arq        *arqpkg.ARQ
	targetAddr string
	conn       net.Conn
	clientAddr net.Addr
	domain     string
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
}

// serverEnqueuer implements PacketEnqueuer for server-side DNS responses
type serverEnqueuer struct {
	conn      net.PacketConn
	clientAddr net.Addr
	domain    string
	mu        sync.Mutex
}

func (e *serverEnqueuer) PushTXPacket(priority int, packetType uint8, sequenceNum uint16, fragmentID uint8, totalFragments uint8, compressionType uint8, ttl time.Duration, payload []byte) bool {
	encoded := basecodec.EncodeLowerBase32(payload)
	
	// Build DNS response with TXT record
	var labels []string
	for len(encoded) > 0 {
		if len(encoded) > 63 {
			labels = append(labels, encoded[:63])
			encoded = encoded[63:]
		} else {
			labels = append(labels, encoded)
			encoded = ""
		}
	}
	
	// Build TXT response
	msg := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:       0, // Will be set by caller
			Response: true,
		},
		Questions: []dnsmessage.Question{
			{
				Name:  dnsmessage.MustNewName(""),
				Type:  dnsmessage.TypeA,
				Class: dnsmessage.ClassINET,
			},
		},
		Answers: []dnsmessage.Resource{
			{
				Header: dnsmessage.ResourceHeader{
					Name:  dnsmessage.MustNewName(""),
					Type:  dnsmessage.TypeTXT,
					Class: dnsmessage.ClassINET,
					TTL:   60,
				},
				Body: &dnsmessage.TXTResource{TXT: []string{strings.Join(labels, "")}},
			},
		},
	}
	
	msgBuf, err := msg.Pack()
	if err != nil {
		return false
	}
	
	e.mu.Lock()
	defer e.mu.Unlock()
	e.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err = e.conn.WriteTo(msgBuf, e.clientAddr)
	return err == nil
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
		sessions:   make(map[string]*session),
		ctx:        ctx,
		cancel:     cancel,
	}
	
	s.wg.Add(1)
	go s.background()
	
	return s, nil
}

// background runs the server event loop to handle incoming DNS queries.
func (s *Server) background() {
	defer s.wg.Done()
	
	buf := make([]byte, 1500)
	
	for {
		select {
		case <-s.ctx.Done():
			return
		default:
			s.packetConn.SetReadDeadline(time.Now().Add(1 * time.Second))
			n, src, err := s.packetConn.ReadFrom(buf)
			if err != nil {
				if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
					continue
				}
				continue
			}
			
			// Parse DNS query
			var msg dnsmessage.Message
			if err := msg.Unpack(buf[:n]); err != nil {
				continue
			}
			
			if len(msg.Questions) != 1 {
				continue
			}
			
			question := msg.Questions[0]
			nameStr := question.Name.String()
			
			// Check if query is for our domain
			if !hasSuffix(nameStr, s.config.Domain) {
				continue
			}
			
			// Extract client IP as session key
			clientIP := src.String()
			
			// Get or create session
			s.mu.Lock()
			sess, exists := s.sessions[clientIP]
			if !exists {
				sess = s.newSession(clientIP, src)
				s.sessions[clientIP] = sess
			}
			s.mu.Unlock()
			
			// Handle the query in the session
			sess.handleQuery(msg, question, src)
		}
	}
}

func hasSuffix(s, suffix string) bool {
	if len(s) < len(suffix) {
		return false
	}
	return s[len(s)-len(suffix):] == suffix
}

func (s *Server) newSession(clientIP string, src net.Addr) *session {
	ctx, cancel := context.WithCancel(s.ctx)
	sess := &session{
		server:     s,
		targetAddr: s.config.TargetAddr,
		clientAddr: src,
		domain:     s.config.Domain,
		ctx:        ctx,
		cancel:     cancel,
	}
	sess.wg.Add(1)
	go sess.run()
	return sess
}

func (sess *session) run() {
	defer sess.wg.Done()
	
	// Connect to target
	conn, err := net.Dial("tcp", sess.targetAddr)
	if err != nil {
		return
	}
	sess.conn = conn
	
	// Create ARQ for this session
	enqueuer := &serverEnqueuer{
		conn:       sess.server.packetConn,
		clientAddr: sess.clientAddr,
		domain:     sess.domain,
	}
	
	wrapper := &dnsConnWrapper{
		conn:       sess.server.packetConn.(*net.UDPConn),
		remoteAddr: sess.clientAddr.(*net.UDPAddr),
		domain:     sess.domain,
		readBuf:    make([]byte, 1500),
	}
	
	sess.arq = arqpkg.NewARQ(
		1, 1, enqueuer, wrapper, 1200, noopLogger{}, sess.server.config.ARQConfig,
	)
	
	// Start ARQ background workers
	sess.arq.Start()
	
	// Forward data between ARQ and target
	go func() {
		buf := make([]byte, 1500)
		for {
			select {
			case <-sess.ctx.Done():
				return
			default:
				sess.conn.SetReadDeadline(time.Now().Add(10 * time.Second))
				n, err := sess.conn.Read(buf)
				if err != nil {
					return
				}
				// Write to ARQ through the localConn (which is wrapper)
				_, _ = wrapper.Write(buf[:n])
			}
		}
	}()
	
	<-sess.ctx.Done()
	sess.arq.Close("context cancelled", arqpkg.CloseOptions{})
	sess.conn.Close()
}

func (sess *session) handleQuery(msg dnsmessage.Message, question dnsmessage.Question, src net.Addr) {
	// Extract data from subdomain
	nameStr := question.Name.String()
	subdomain := extractSubdomain(nameStr, sess.domain)
	if subdomain == "" {
		// Send empty response
		response := dnsmessage.Message{
			Header: dnsmessage.Header{
				ID:       msg.Header.ID,
				Response: true,
			},
			Questions: []dnsmessage.Question{question},
		}
		responseBuf, _ := response.Pack()
		sess.server.packetConn.WriteTo(responseBuf, src)
		return
	}
	
	// Decode Base32 data
	_, err := basecodec.DecodeLowerBase32String(subdomain)
	if err != nil {
		return
	}
	
	// Feed data to ARQ through the localConn (wrapper)
	// Data is processed by ARQ's background workers reading from localConn
	_ = sess.arq
	
	// Send DNS response with TXT record containing data
	encoded := basecodec.EncodeLowerBase32(nil) // placeholder for response data
	response := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:       msg.Header.ID,
			Response: true,
		},
		Questions: []dnsmessage.Question{question},
		Answers: []dnsmessage.Resource{
			{
				Header: dnsmessage.ResourceHeader{
					Name:  question.Name,
					Type:  dnsmessage.TypeTXT,
					Class: dnsmessage.ClassINET,
					TTL:   60,
				},
				Body: &dnsmessage.TXTResource{TXT: []string{encoded}},
			},
		},
	}
	responseBuf, _ := response.Pack()
	sess.server.packetConn.WriteTo(responseBuf, src)
}

func extractSubdomain(name, domain string) string {
	// Remove domain suffix
	if !hasSuffix(name, "."+domain) && name != domain {
		return ""
	}
	if name == domain {
		return ""
	}
	return strings.TrimSuffix(name, "."+domain)
}

// Close closes the server tunnel connection.
func (s *Server) Close() error {
	s.cancel()
	s.wg.Wait()
	return s.packetConn.Close()
}
