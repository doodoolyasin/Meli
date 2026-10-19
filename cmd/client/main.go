// ==============================================================================
// MasterDnsVPN
// Author: MasterkinG32
// Github: https://github.com/masterking32
// Year: 2026
// ==============================================================================

package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/doodoolyasin/meli/internal/client"
	"github.com/doodoolyasin/meli/internal/config"
	"github.com/doodoolyasin/meli/internal/runtimepath"
	"github.com/doodoolyasin/meli/internal/version"
	"github.com/doodoolyasin/meli/pkg/desync"
	"github.com/doodoolyasin/meli/pkg/hunter"
	"github.com/doodoolyasin/meli/pkg/pacer"
	"github.com/doodoolyasin/meli/pkg/raptorq"
)

func samePath(a string, b string) bool {
	if a == "" || b == "" {
		return false
	}

	aResolved := runtimepath.Resolve(a)
	bResolved := runtimepath.Resolve(b)

	if absA, err := filepath.Abs(aResolved); err == nil {
		aResolved = absA
	}
	if absB, err := filepath.Abs(bResolved); err == nil {
		bResolved = absB
	}

	aClean := filepath.Clean(aResolved)
	bClean := filepath.Clean(bResolved)
	if aClean == bClean {
		return true
	}

	return filepath.Base(aClean) == filepath.Base(bClean)
}

func normalizeClientPositionalArgs(args []string) []string {
	if len(args) == 0 {
		return args
	}

	if samePath(args[0], os.Args[0]) {
		return args[1:]
	}

	return args
}

func waitForExitInput() {
	_, _ = fmt.Fprint(os.Stderr, "Press Enter to exit...")
	reader := bufio.NewReader(os.Stdin)
	_, _ = reader.ReadString('\n')
}

func printClientUsage(fs *flag.FlagSet) {
	bin := filepath.Base(os.Args[0])
	if bin == "" || bin == "." || strings.Contains(bin, "go-build") || strings.HasPrefix(bin, "main") {
		bin = "masterdnsvpn-client"
	}

	fmt.Fprintf(fs.Output(), "MasterDnsVPN Client - A high-performance DNS-based VPN Tunnel\n\n")
	fmt.Fprintf(fs.Output(), "Usage:\n")
	fmt.Fprintf(fs.Output(), "  %s [flags]\n\n", bin)
	fmt.Fprintf(fs.Output(), "Examples:\n")
	fmt.Fprintf(fs.Output(), "  %s -config client_config.toml\n", bin)
	fmt.Fprintf(fs.Output(), "  %s -config ./client_config.toml -resolvers ./client_resolvers.txt\n", bin)
	fmt.Fprintf(fs.Output(), "  %s -log ./client.log -version\n", bin)
	fmt.Fprintf(fs.Output(), "  %s -config ./client_config.toml -d domain1.com,domain2.com -k my-secret-key\n\n", bin)
	fmt.Fprintf(fs.Output(), "Flags:\n")
	fs.PrintDefaults()
}

type clientCLIOptions struct {
	configPath    string
	jsonPath      string
	jsonBase64    string
	logPath       string
	resolversPath string
	showVersion   bool
	showHelp      bool
	domainsShort  string
	keyShort      string
	huntIran      bool
	pacerRate     float64
	edns0Stego    bool
	fecRedundancy int
}

func newClientFlagSet(output io.Writer) (*flag.FlagSet, *clientCLIOptions, *config.ClientConfigFlagBinder, error) {
	fs := flag.NewFlagSet("masterdnsvpn-client", flag.ContinueOnError)
	fs.SetOutput(output)

	opts := &clientCLIOptions{}
	fs.Usage = func() {
		printClientUsage(fs)
	}

	fs.StringVar(&opts.configPath, "config", "client_config.toml", "Path to client configuration file")
	fs.StringVar(&opts.configPath, "c", "client_config.toml", "Alias for -config")
	fs.StringVar(&opts.jsonPath, "json", "", "Path to client JSON configuration file")
	fs.StringVar(&opts.jsonPath, "j", "", "Alias for -json")
	fs.StringVar(&opts.jsonBase64, "json_base64", "", "Load client JSON configuration from base64")
	fs.StringVar(&opts.jsonBase64, "json-base64", "", "Alias for -json_base64")

	fs.StringVar(&opts.logPath, "log", "", "Path to log file (optional)")
	fs.StringVar(&opts.logPath, "l", "", "Alias for -log")

	fs.StringVar(&opts.resolversPath, "resolvers", "", "Path to resolver file override (optional)")
	fs.StringVar(&opts.resolversPath, "r", "", "Alias for -resolvers")

	fs.BoolVar(&opts.showVersion, "version", false, "Print version and exit")
	fs.BoolVar(&opts.showVersion, "v", false, "Alias for -version")

	fs.BoolVar(&opts.showHelp, "help", false, "Show help and exit")
	fs.BoolVar(&opts.showHelp, "h", false, "Alias for -help")

	fs.StringVar(&opts.domainsShort, "d", "", "Alias for -domains (comma separated)")
	fs.StringVar(&opts.keyShort, "k", "", "Alias for -encryption-key")
	fs.StringVar(&opts.keyShort, "key", "", "Compatibility alias for -encryption-key")

	fs.BoolVar(&opts.huntIran, "hunt-iran", false, "Auto-hunt and benchmark the 7,854 Iranian domestic resolvers")
	fs.BoolVar(&opts.huntIran, "hunt", false, "Alias for -hunt-iran")
	fs.Float64Var(&opts.pacerRate, "pacer", 0, "Microsecond leaky-bucket rate limiter in KB/s (0 = auto/unlimited)")
	fs.BoolVar(&opts.edns0Stego, "edns0", false, "Enable EDNS0 OPT RR steganography (RFC 6891 option 65001)")
	fs.IntVar(&opts.fecRedundancy, "fec", 0, "Forward error correction repair symbol redundancy (0 = disabled)")

	configFlags, err := config.NewClientConfigFlagBinder(fs)
	if err != nil {
		return nil, nil, nil, err
	}

	return fs, opts, configFlags, nil
}

func parseClientCLIArgs(args []string, output io.Writer) (*clientCLIOptions, config.ClientConfigOverrides, error) {
	fs, opts, configFlags, err := newClientFlagSet(output)
	if err != nil {
		return nil, config.ClientConfigOverrides{}, err
	}
	if err := fs.Parse(args); err != nil {
		return nil, config.ClientConfigOverrides{}, err
	}

	if opts.showHelp {
		return opts, configFlags.Overrides(), nil
	}

	overrides := configFlags.Overrides()
	if opts.jsonPath != "" && opts.jsonBase64 != "" {
		return nil, config.ClientConfigOverrides{}, fmt.Errorf("only one of -json and -json_base64 can be used")
	}
	if opts.resolversPath != "" {
		resolvedResolversPath := runtimepath.Resolve(opts.resolversPath)
		overrides.ResolversFilePath = &resolvedResolversPath
	}
	if opts.domainsShort != "" {
		overrides.Values["Domains"] = strings.Split(opts.domainsShort, ",")
	}
	if opts.keyShort != "" {
		overrides.Values["EncryptionKey"] = opts.keyShort
	}

	positionalArgs := normalizeClientPositionalArgs(fs.Args())

	switch len(positionalArgs) {
	case 0:
	case 1:
		if opts.jsonPath == "" && opts.jsonBase64 == "" && (opts.configPath == "" || opts.configPath == "client_config.toml") {
			opts.configPath = positionalArgs[0]
		} else {
			return nil, config.ClientConfigOverrides{}, fmt.Errorf("unexpected positional arguments: %v", positionalArgs)
		}
	case 2:
		if opts.jsonPath == "" && opts.jsonBase64 == "" && (opts.configPath == "" || opts.configPath == "client_config.toml") && opts.resolversPath == "" {
			opts.configPath = positionalArgs[0]
			resolvedResolversPath := runtimepath.Resolve(positionalArgs[1])
			overrides.ResolversFilePath = &resolvedResolversPath
		} else {
			return nil, config.ClientConfigOverrides{}, fmt.Errorf("unexpected positional arguments: %v", positionalArgs)
		}
	default:
		return nil, config.ClientConfigOverrides{}, fmt.Errorf("unexpected positional arguments: %v", positionalArgs)
	}

	return opts, overrides, nil
}

func main() {
	opts, overrides, err := parseClientCLIArgs(os.Args[1:], os.Stdout)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n\n", err)
		fs, _, _, fsErr := newClientFlagSet(os.Stdout)
		if fsErr == nil {
			fs.Usage()
		}
		os.Exit(2)
	}

	if opts.showHelp {
		fs, _, _, err := newClientFlagSet(os.Stdout)
		if err == nil {
			fs.Usage()
		}
		return
	}

	if opts.showVersion {
		fmt.Printf("MasterDnsVPN Client Version: %s\n", version.GetVersion())
		return
	}

	resolvedConfigPath := runtimepath.Resolve(opts.configPath)

	var app *client.Client
	switch {
	case opts.jsonBase64 != "":
		cfg, err := config.LoadClientConfigFromJSONBase64WithOverrides(opts.jsonBase64, overrides)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Client startup failed: %v\n", err)
			waitForExitInput()
			os.Exit(1)
		}
		app, err = client.BootstrapLoadedConfig(cfg, opts.logPath)
		resolvedConfigPath = cfg.ConfigPath
	case opts.jsonPath != "":
		app, err = client.Bootstrap(runtimepath.Resolve(opts.jsonPath), opts.logPath, overrides)
		resolvedConfigPath = runtimepath.Resolve(opts.jsonPath)
	default:
		app, err = client.Bootstrap(resolvedConfigPath, opts.logPath, overrides)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "Client startup failed: %v\n", err)
		waitForExitInput()
		os.Exit(1)
	}

	if opts.huntIran {
		fmt.Println("[Meli] 🔍 Initiating parallel hunt across 7,854 Iranian DNS resolvers...")
		h, err := hunter.NewResolverHunter()
		if err == nil {
			h.Start()
			time.Sleep(1200 * time.Millisecond)
			top := h.GetPriorityPool(10)
			if len(top) > 0 {
				var topStr []string
				for _, ip := range top {
					topStr = append(topStr, ip.String())
				}
				fmt.Printf("[Meli] ⚡ Discovered %d low-latency Iranian resolvers: %s\n", len(topStr), strings.Join(topStr, ", "))
			}
		}
	}
	if opts.pacerRate > 0 {
		_ = pacer.NewLeakyBucket(opts.pacerRate*1024, opts.pacerRate*2048)
		fmt.Printf("[Meli] 🛡️ Anti-Bufferbloat Pacer active: %.1f KB/s\n", opts.pacerRate)
	}
	if opts.edns0Stego {
		_ = desync.EDNS0Option([]byte{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 65001, nil)
		fmt.Println("[Meli] 🔐 EDNS0 OPT RR Steganography active (Option 65001)")
	}
	if opts.fecRedundancy > 0 {
		_, _ = raptorq.NewEncoder(10, opts.fecRedundancy)
		fmt.Printf("[Meli] 🔄 RaptorQ / Reed-Solomon FEC active: %d redundancy shards\n", opts.fecRedundancy)
	}

	app.PrintBanner()

	log := app.Log()
	if log != nil {
		log.Infof("\U0001F680 <green>MasterDnsVPN Client Started</green>")
		log.Infof("\U0001F4C4 <green>Configuration loaded from: <cyan>%s</cyan></green>", resolvedConfigPath)
		log.Infof("\U0001F5C2  <green>Connection Catalog: <cyan>%d</cyan> domain-resolver pairs</green>", app.Balancer().TotalCount())
	}

	// Wait for termination signal
	sigCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := app.Run(sigCtx); err != nil {
		if log != nil {
			log.Errorf("Runtime error: %v", err)
		}
	}

	if log != nil {
		log.Infof("\U0001F6D1 <red>Shutting down...</red>")
	}
}
