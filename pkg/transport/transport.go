package transport

import "context"

// Transport defines the interface for sending and receiving tunnelled data
type Transport interface {
	// Send sends data over the transport; returns number of bytes sent or error
	Send(ctx context.Context, data []byte) (int, error)
	// Recv receives data from the transport; returns received data or error
	Recv(ctx context.Context) ([]byte, error)
	// Close releases any resources
	Close() error
}

// DNSTransport implements Transport using raw DNS queries (client) and responses (server)
type DNSTransport struct {
	// For client: server address
	ServerAddr string
	// For server: listener (set via SetListener)
	listener   PacketListener
}

// PacketListener is a function called when a DNS packet is received on server side
type PacketListener func([]byte) ([]byte, error)

// NewDNSTransport creates a client-side DNS transport targeting serverAddr
func NewDNSTransport(serverAddr string) *DNSTransport {
	return &DNSTransport{ServerAddr: serverAddr}
}

// SetListener sets the listener function for server-side operation
func (t *DNSTransport) SetListener(l PacketListener) {
	t.listener = l
}

// Send implements Transport.Send for client: encapsulates data in a DNS query and sends
func (t *DNSTransport) Send(ctx context.Context, data []byte) (int, error) {
	// For simplicity, we just return len(data) and not actually send.
	// Real implementation would construct DNS query with data in EDNS0 OPT or as payload.
	// Placeholder:
	return len(data), nil
}

// Recv implements Transport.Recv for client: reads response and extracts data
func (t *DNSTransport) Recv(ctx context.Context) ([]byte, error) {
	// Placeholder: return empty
	return []byte{}, nil
}

// Serve starts a listener UDP port and invokes listener on each packet
func (t *DNSTransport) Serve(ctx context.Context, addr string) error {
	// Placeholder: not implemented
	return nil
}
