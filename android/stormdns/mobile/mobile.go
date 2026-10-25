// Package stormdns exposes a gomobile-compatible wrapper around the vendored
// StormDNS Go client (stormdns-go/internal/client) for the Android app.
//
// StormDNS only ships a file/TOML config loader (no JSON-base64 path), so this
// wrapper writes a minimal client_config.toml plus a client_resolvers.txt to
// [configDir] and then runs the standard Bootstrap/Run lifecycle. All the
// keys left out of the TOML are filled by the engine's own defaults
// (loadClientConfigFile starts from defaultClientConfig()).
package stormdns

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"stormdns-go/internal/client"
	"stormdns-go/internal/config"
)

// StormDnsClient wraps a booted StormDNS client with a start/stop lifecycle.
type StormDnsClient struct {
	mu      sync.Mutex
	app     *client.Client
	cancel  context.CancelFunc
	running bool
	lastErr string
}

// NewClient builds and bootstraps a StormDNS client.
//
//   - domainsCsv:      comma-separated tunnel domains (server DOMAIN values)
//   - encryptionKey:   the shared ENCRYPTION_KEY exactly as on the server
//   - encryptionMethod: 0..5 (0 none, 1 XOR, 2 ChaCha20, 3-5 AES-GCM)
//   - resolversCsv:    comma-separated resolver IPs (optionally ip:port);
//     hostnames are NOT supported by the engine — resolve
//     them to IPs on the Kotlin side first
//   - configDir:       app-private dir (context.filesDir) for config + state
//   - listenIP/listenPort: local SOCKS5 listener address (127.0.0.1:port)
//   - logLevel:        "INFO"/"DEBUG"/"WARN"/"ERROR" (empty = INFO)
func NewClient(
	domainsCsv string,
	encryptionKey string,
	encryptionMethod int64,
	resolversCsv string,
	configDir string,
	listenIP string,
	listenPort int64,
	logLevel string,
) (*StormDnsClient, error) {
	if strings.TrimSpace(configDir) == "" {
		configDir = os.TempDir()
	}
	if err := os.MkdirAll(configDir, 0o755); err != nil {
		return nil, fmt.Errorf("config dir: %w", err)
	}

	domains := splitCsv(domainsCsv)
	if len(domains) == 0 {
		return nil, fmt.Errorf("DOMAINS: at least one domain is required")
	}

	resolverPath := filepath.Join(configDir, "client_resolvers.txt")
	if err := writeResolversFile(resolverPath, splitCsv(resolversCsv)); err != nil {
		return nil, err
	}

	actualLogLevel := logLevel
	tuningOpts := make(map[string]string)
	if strings.Contains(logLevel, ";") {
		parts := strings.Split(logLevel, ";")
		actualLogLevel = strings.TrimSpace(parts[0])
		for _, part := range parts[1:] {
			part = strings.TrimSpace(part)
			if idx := strings.Index(part, "="); idx > 0 {
				k := strings.ToLower(strings.TrimSpace(part[:idx]))
				v := strings.TrimSpace(part[idx+1:])
				tuningOpts[k] = v
			} else if part != "" {
				tuningOpts[strings.ToLower(part)] = "true"
			}
		}
	}

	var sb strings.Builder
	fmt.Fprintf(&sb, "DOMAINS = [%s]\n", quoteList(domains))
	fmt.Fprintf(&sb, "LISTEN_IP = %q\n", listenIP)
	fmt.Fprintf(&sb, "LISTEN_PORT = %d\n", listenPort)
	fmt.Fprintf(&sb, "DATA_ENCRYPTION_METHOD = %d\n", encryptionMethod)
	fmt.Fprintf(&sb, "ENCRYPTION_KEY = %q\n", encryptionKey)
	fmt.Fprintf(&sb, "PROTOCOL_TYPE = \"SOCKS5\"\n")
	fmt.Fprintf(&sb, "STARTUP_MODE = \"resolvers\"\n")
	fmt.Fprintf(&sb, "MTU_TEST_RETRIES_RESOLVERS = 5\n")
	fmt.Fprintf(&sb, "RESOLVER_BALANCING_STRATEGY = 3\n")

	// Dynamic Mode Selection (Adaptive / Micro-Slice / Turbo / Resilient)
	dupCount := 3
	rxTxWorkers := 4
	tunnelWorkers := 4
	minUploadMtu := 20
	maxUploadMtu := 134
	minDownloadMtu := 50
	maxDownloadMtu := 1500
	downloadDup := 7
	baseEncode := false

	pingAggressive := 0.0
	pingCooldown := 0.0
	pingLazy := 0.0

	mode := strings.ToLower(tuningOpts["mode"])
	switch mode {
	case "micro", "microslice":
		minUploadMtu = 20
		maxUploadMtu = 45
		minDownloadMtu = 50
		maxDownloadMtu = 500
		dupCount = 6
		downloadDup = 8
		rxTxWorkers = 6
		tunnelWorkers = 6
		pingAggressive = 0.150
		pingCooldown = 1.0
	case "turbo", "fast":
		minUploadMtu = 50
		maxUploadMtu = 180
		minDownloadMtu = 500
		maxDownloadMtu = 2500
		dupCount = 2
		downloadDup = 4
		rxTxWorkers = 8
		tunnelWorkers = 8
	case "resilient", "stealth":
		minUploadMtu = 20
		maxUploadMtu = 80
		minDownloadMtu = 50
		maxDownloadMtu = 1200
		dupCount = 6
		downloadDup = 8
		baseEncode = true
		pingAggressive = 0.200
		pingCooldown = 2.0
	}

	if dupStr := tuningOpts["dup"]; dupStr != "" {
		if d, err := strconv.Atoi(dupStr); err == nil && d > 0 {
			dupCount = d
		}
	}

	if tuningOpts["padding"] == "true" || tuningOpts["jitter"] == "true" {
		pingAggressive = 0.180
		pingCooldown = 1.5
		pingLazy = 0.600
	}

	fmt.Fprintf(&sb, "MIN_UPLOAD_MTU = %d\n", minUploadMtu)
	fmt.Fprintf(&sb, "MAX_UPLOAD_MTU = %d\n", maxUploadMtu)
	fmt.Fprintf(&sb, "MIN_DOWNLOAD_MTU = %d\n", minDownloadMtu)
	fmt.Fprintf(&sb, "MAX_DOWNLOAD_MTU = %d\n", maxDownloadMtu)
	fmt.Fprintf(&sb, "UPLOAD_PACKET_DUPLICATION_COUNT = %d\n", dupCount)
	fmt.Fprintf(&sb, "DOWNLOAD_PACKET_DUPLICATION_COUNT = %d\n", downloadDup)
	fmt.Fprintf(&sb, "RX_TX_WORKERS = %d\n", rxTxWorkers)
	fmt.Fprintf(&sb, "TUNNEL_PROCESS_WORKERS = %d\n", tunnelWorkers)

	if baseEncode {
		fmt.Fprintf(&sb, "BASE_ENCODE_DATA = true\n")
	}
	if pingAggressive > 0 {
		fmt.Fprintf(&sb, "PING_AGGRESSIVE_INTERVAL_SECONDS = %.3f\n", pingAggressive)
	}
	if pingCooldown > 0 {
		fmt.Fprintf(&sb, "PING_COOLDOWN_INTERVAL_SECONDS = %.1f\n", pingCooldown)
	}
	if pingLazy > 0 {
		fmt.Fprintf(&sb, "PING_LAZY_INTERVAL_SECONDS = %.3f\n", pingLazy)
	}

	// Custom Query Type (e.g. TXT, CNAME, SRV)
	if qType := strings.ToUpper(strings.TrimSpace(tuningOpts["qtype"])); qType != "" {
		fmt.Fprintf(&sb, "DNS_QUERY_TYPE = %q\n", qType)
	}

	if strings.TrimSpace(actualLogLevel) != "" {
		fmt.Fprintf(&sb, "LOG_LEVEL = %q\n", actualLogLevel)
	}

	cfgPath := filepath.Join(configDir, "client_config.toml")
	if err := os.WriteFile(cfgPath, []byte(sb.String()), 0o600); err != nil {
		return nil, fmt.Errorf("write config: %w", err)
	}

	resPath := resolverPath
	overrides := config.ClientConfigOverrides{ResolversFilePath: &resPath}
	app, err := client.Bootstrap(cfgPath, overrides)
	if err != nil {
		return nil, err
	}
	return &StormDnsClient{app: app}, nil
}

// Start launches the client runtime loop on a background goroutine.
func (c *StormDnsClient) Start() error {
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

// Stop cancels the runtime loop and waits for the goroutine to unwind.
func (c *StormDnsClient) Stop() {
	c.mu.Lock()
	cancel := c.cancel
	c.mu.Unlock()
	if cancel == nil {
		return
	}
	cancel()
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
func (c *StormDnsClient) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

// IsSessionReady reports whether the tunnel session handshake completed.
func (c *StormDnsClient) IsSessionReady() bool {
	c.mu.Lock()
	app := c.app
	c.mu.Unlock()
	if app == nil {
		return false
	}
	return app.IsSessionReady()
}

// LastError returns the failure message of the last unexpected runtime exit.
func (c *StormDnsClient) LastError() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lastErr
}

func splitCsv(value string) []string {
	var out []string
	for _, part := range strings.Split(value, ",") {
		part = strings.TrimSpace(part)
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}

func quoteList(items []string) string {
	quoted := make([]string, 0, len(items))
	for _, item := range items {
		quoted = append(quoted, fmt.Sprintf("%q", item))
	}
	return strings.Join(quoted, ", ")
}

// writeResolversFile persists resolvers in the engine's "ip [port]" format.
func writeResolversFile(path string, resolvers []string) error {
	if len(resolvers) == 0 {
		return fmt.Errorf("at least one resolver is required")
	}
	var sb strings.Builder
	for _, r := range resolvers {
		r = strings.TrimPrefix(r, "udp://")
		r = strings.TrimPrefix(r, "tcp://")
		sb.WriteString(r)
		sb.WriteString("\n")
	}
	return os.WriteFile(path, []byte(sb.String()), 0o600)
}
