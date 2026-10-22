// Package masterdnsmobile exposes a gomobile-compatible wrapper around the
// MasterDNS (Meli) core DNS-tunnel client (internal/client) for the Android
// app. The engine boots from a base64-encoded JSON client config (the same
// fields as client_config.toml, e.g. DOMAINS, ENCRYPTION_KEY,
// DATA_ENCRYPTION_METHOD, LISTEN_IP, LISTEN_PORT) plus a resolver file in the
// classic "ip port" per-line format.
//
// The wrapper runs Client.Run on a background goroutine and exposes a
// Start/Stop lifecycle plus IsRunning/IsSessionReady for the Kotlin bridges.
package masterdnsmobile

import (
	"context"
	"sync"
	"time"

	"github.com/doodoolyasin/meli/internal/client"
	"github.com/doodoolyasin/meli/internal/config"
)

// MasterDnsClient wraps a booted core client with a start/stop lifecycle.
type MasterDnsClient struct {
	mu      sync.Mutex
	app     *client.Client
	cancel  context.CancelFunc
	running bool
	lastErr string
}

// NewClient builds and bootstraps a MasterDNS client from a base64-encoded
// JSON client config. resolversFilePath must point to an absolute resolver
// file ("ip [port]" per line); the app writes it next to its config files.
// logPath may be empty to log to stderr. Equivalent to the CLI's
// -json-base64 + -resolvers startup path.
func NewClient(jsonConfigB64 string, resolversFilePath string, logPath string) (*MasterDnsClient, error) {
	overrides := config.ClientConfigOverrides{}
	if resolversFilePath != "" {
		p := resolversFilePath
		overrides.ResolversFilePath = &p
	}
	cfg, err := config.LoadClientConfigFromJSONBase64WithOverrides(jsonConfigB64, overrides)
	if err != nil {
		return nil, err
	}
	app, err := client.BootstrapLoadedConfig(cfg, logPath)
	if err != nil {
		return nil, err
	}
	return &MasterDnsClient{app: app}, nil
}

// Start launches the client runtime loop on a background goroutine. It is a
// no-op when the client is already running. The returned error is reserved
// for future synchronous failures; once accepted, the loop reports its own
// failures through LastError.
func (c *MasterDnsClient) Start() error {
	c.mu.Lock()
	if c.running {
		c.mu.Unlock()
		return nil
	}
	app := c.app
	c.running = true
	c.lastErr = ""
	c.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	c.mu.Lock()
	c.cancel = cancel
	c.mu.Unlock()

	go func() {
		err := app.Run(ctx)
		c.mu.Lock()
		c.running = false
		c.cancel = nil
		if err != nil && ctx.Err() == nil {
			c.lastErr = err.Error()
		}
		c.mu.Unlock()
	}()
	return nil
}

// Stop cancels the runtime loop (Flushes/Ping close-burst included) and waits
// for the goroutine to unwind before returning.
func (c *MasterDnsClient) Stop() {
	c.mu.Lock()
	cancel := c.cancel
	c.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	for i := 0; i < 100; i++ {
		c.mu.Lock()
		running := c.running
		c.mu.Unlock()
		if !running {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// IsRunning reports whether the runtime loop goroutine is alive.
func (c *MasterDnsClient) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

// IsSessionReady reports whether the DNS tunnel session finished its
// handshake (MTU tests + session init) and is able to relay traffic.
func (c *MasterDnsClient) IsSessionReady() bool {
	c.mu.Lock()
	app := c.app
	c.mu.Unlock()
	if app == nil {
		return false
	}
	return app.IsSessionReady()
}

// LastError returns the failure message of the last unexpected runtime exit,
// or an empty string when the client stopped cleanly or is still running.
func (c *MasterDnsClient) LastError() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lastErr
}
