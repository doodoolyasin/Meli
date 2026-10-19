package evasion

import (
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
)

// CaptivePortalDomains lists standard captive portal verification hostnames checked by Android and iOS.
// During Iranian internet blackouts, queries to these domains fail or redirect to Iranian filtering portals (Peyvandha),
// causing Android to display an exclamation mark (!) on Wi-Fi/LTE, drop connections, and refuse VPN routing.
var CaptivePortalDomains = map[string]bool{
	"connectivitycheck.gstatic.com":       true,
	"connectivitycheck.android.com":       true,
	"clients3.google.com":                 true,
	"play.googleapis.com":                 true,
	"captive.apple.com":                   true,
	"www.airport.us":                      true,
	"www.ibook.info":                      true,
	"www.thinkdifferent.us":               true,
	"www.appleiphonecell.com":             true,
	"msftconnecttest.com":                 true,
	"ipv6.msftconnecttest.com":            true,
	"www.msftncsi.com":                    true,
	"network-test.debian.org":             true,
	"nmcheck.gnome.org":                   true,
}

// IsCaptivePortalDomain returns true if the queried domain is an OS connectivity probe.
func IsCaptivePortalDomain(domain string) bool {
	clean := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(domain)), ".")
	return CaptivePortalDomains[clean]
}

// Local204Server runs a lightweight in-process HTTP server on localhost that always responds with HTTP 204 No Content.
// Resolving captive portal domains to 127.0.0.1:port instantly clears the exclamation mark (!) from Android status bars.
type Local204Server struct {
	listener net.Listener
	port     int
	mu       sync.Mutex
}

// StartLocal204Server starts the generator on an ephemeral or requested port.
func StartLocal204Server(preferredPort int) (*Local204Server, error) {
	addr := fmt.Sprintf("127.0.0.1:%d", preferredPort)
	l, err := net.Listen("tcp", addr)
	if err != nil {
		// Fallback to random available port
		l, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return nil, err
		}
	}

	s := &Local204Server{
		listener: l,
		port:     l.Addr().(*net.TCPAddr).Port,
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Connection", "close")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.WriteHeader(http.StatusNoContent) // HTTP 204 (Signals healthy unrestricted internet to Android)
	})

	go func() {
		_ = http.Serve(l, handler)
	}()

	return s, nil
}

// Port returns the active listening port
func (s *Local204Server) Port() int {
	return s.port
}

// Close terminates the server
func (s *Local204Server) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.listener != nil {
		return s.listener.Close()
	}
	return nil
}
