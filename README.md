# Meli - Advanced DNS Tunneling Suite
A high-performance, censorship-resistant tunneling framework built on DNS with:
- Zero-allocation DNS packet crafting
- RaptorQ FEC for loss resilience (placeholder)
- Anti-bufferbloat pacing (planned)
- Multi-transport (DNS, WebRTC, HTTP) (planned)
- Resolver Hunter & Health-check (planned)
- gVisor Netstack integration (planned)

## Quick Start

```bash
# Clone
git clone https://github.com/doodoolyasin/meli.git
cd meli

# Build
go build -o meli-server ./cmd/server
go build -o meli-client ./cmd/client

# Run server (default UDP port 5053 for testing)
./meli-server

# In another terminal, send a test query
./meli-client 127.0.0.1 5053
```

## Docker

```bash
docker build -t meli .
docker run -p 5053:5053/udp meli
```

## GitHub Actions

CI builds binaries for Linux on each push to `main`.

## Next Steps

- Implement RaptorQ FEC using external library or pure Go.
- Add anti-bufferbloat pacing with microsecond ticker.
- Implement Resolver Hunter to discover low-latency Iranian resolvers.
- Add DPI evasion techniques (bad checksum, EDNS0 OPT, QUIC DCID steganography).
- Integrate gVisor Netstack for VPN-like TUN interface.
- Add multi-transport support (WebRTC, HTTP tunneling).
