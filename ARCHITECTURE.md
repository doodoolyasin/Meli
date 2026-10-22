# MasterDNS / SlipNet / MasterDnsVPN — Architecture (source-backed)

This document describes the code that exists in this repository, not an intended
design. Every path below was read during the Phase 1 audit (2026-10-21,
commit `13f6991` plus the Phase 1 working-tree changes).

## 1. Repository shape

| Path | LOC (Go) | Role |
| --- | --- | --- |
| `cmd/client` | 376 | Client binary: local SOCKS5/TCP proxy, local DNS listener, MTU probing, optional modes |
| `cmd/server` | 211 | Server binary: UDP DNS ingress, ARQ, TCP/SOCKS5 egress |
| `cmd/demux` | 161 | DNS front-door demultiplexer (routes one :53 socket to several engines) |
| `internal/client` | 13,390 | Client engine: sessions, streams, SOCKS5/SOCKS4 handling, MTU, balancer, async pipeline |
| `internal/udpserver` | 11,245 | Server engine: UDP ingress, session store, deferred connects, egress, fallback |
| `internal/arq` | 5,530 | Shared sliding-window ARQ over a lossy datagram channel |
| `internal/config` | 3,707 | TOML/JSON/base64 config, flag binder, derived limits |
| `internal/dnsparser` | 2,789 | Lite DNS packet parsing for the hot path |
| `internal/vpnproto` | 1,284 | Wire format of tunnel packets |
| `internal/security` | 583 | AES-GCM / ChaCha20-Poly1305 codecs, key derivation |
| `internal/dnscache`, `internal/fragmentstore`, `internal/mlq`, `internal/socksproto` | ~1,900 | Response cache, bounded fragment assembly, multi-level queue, SOCKS constants |
| `pkg/*` | ~2,300 | Optional/auxiliary packages; see §5 for which are wired |
| `android/` | 60,301 (Kotlin) | Jetpack Compose app, VpnService, gomobile AAR, Rust/native submodules |

`go list ./...` reports 45 packages; 469 Go tests exist (plus 9 `e2e`-tagged
tests in `test/e2e`).

## 2. Runtime topology

```
app / browser
   │  SOCKS5 (TCP)  or  DNS (UDP)
   ▼
cmd/client ── internal/client
   │  TCPListener (LISTEN_IP:LISTEN_PORT)   DNSListener (LOCAL_DNS_IP:LOCAL_DNS_PORT)
   │        │                                      │
   │        └────────► Client.HandleSOCKS5         └─► Client.ProcessDNSQuery
   │                        │                              │
   │                        └────────► stream + ARQ ◄──────┘
   │                                    │
   │                     dispatcher → planner/encoder → asyncWriterWorker
   ▼                                    │
resolvers (client_resolvers.txt)  ◄─────┘   UDP/53 DNS queries carrying tunnel frames
   │
   ▼
cmd/server ── internal/udpserver
   │  UDP :53 ingress (reuseport workers) → dnsparser → session lookup → ARQ
   │                                                              │
   │                        dialTCPTargetContextPolicy / dialSOCKSStreamTargetContext
   ▼                                                              ▼
                                                         destination TCP service
```

### 2.1 Client → resolver path

1. `internal/client/tcp_listener.go:Start` binds `LISTEN_IP:LISTEN_PORT` (both
   loopback stacks when the address is loopback) and spawns
   `handleConnection` per accepted socket.
2. `internal/client/socks_manager.go:HandleSOCKS5` performs the greeting,
   authentication (optional), and request parsing, then calls
   `handleSOCKSConnect` (CMD CONNECT) or `handleSocksUDPAssociate` (CMD UDP
   ASSOCIATE).
3. `handleSOCKSConnect` allocates a stream via `new_stream`, which builds an
   `*arq.ARQ` per stream (`internal/arq`).
4. Outstanding packets live in `clientStreamTXPacket`; `dispatcher.go` moves
   them to `plannerQueue`, the planner/encoder selects a resolver and encodes
   the DNS query, and `asyncWriterWorker`
   (`internal/client/async_runtime.go:760`) writes the datagram to the socket.
   This is the single egress point for client traffic (and where the optional
   pacer is applied).
5. `internal/client/balancer.go` tracks per-resolver RTT/loss and chooses
   resolvers; `mtu.go` performs the initial MTU probe.

### 2.2 Server ingress → egress

1. `internal/udpserver/server.go:New` wires the session store, fragment stores,
   DNS cache, invalid-cookie tracker and deferred-connect workers.
2. `server_ingress.go` / `server_runtime.go` read datagrams from the reuseport
   UDP sockets (`reuseport_unix.go`, `reuseport_fallback.go`), parse with
   `internal/dnsparser`, and dispatch by session.
3. `server_session.go` holds per-session state (`sessionRecord`,
   `Stream_server`); `server_deferred.go` handles connection setup that must not
   block the read loop (`processDeferredSOCKS5Syn`, `dialWithinDeadline`).
4. Egress is either fixed forwarding (`FORWARD_IP`/`FORWARD_PORT`) or per-target
   through `socks5_upstream.go`, with an optional external SOCKS5 hop and a
   private-target policy (`ALLOW_PRIVATE_TARGETS`).
5. `stream_server.go` bridges the ARQ stream to the upstream `net.Conn` and
   sends return traffic back through the same DNS channel.

### 2.3 Local DNS proxy mode

`internal/client/dns_listener.go:Start` binds `LOCAL_DNS_IP:LOCAL_DNS_PORT`.
`ProcessDNSQuery` answers from `internal/dnscache` on a hit and otherwise
fragments the query onto stream 0 as `PACKET_DNS_QUERY_REQ` control packets.
Note: on a cache miss it returns `false`, which `handleSocksUDPAssociate` treats
as "close the association so the client retries" — this is deliberate behaviour,
not a bug, but it means UDP ASSOCIATE sessions are short-lived by design.

## 3. Wire format and reliability

- `internal/vpnproto` defines packet types, header flags and packing helpers.
- `internal/arq` implements the sliding window: selective ACK, NACK with gap
  limits, adaptive RTO (`updateAdaptiveRTO`), control-packet reliability
  (`SendControlPacketWithTTL`), close handshakes (CLOSE_READ / CLOSE_WRITE /
  RST) and a terminal drain.
- `ARQ.Done()` closes only after the close epilogue (logging +
  `OnARQClosed`) has run; this was changed in Phase 1 because callers use it as
  the "safe to free per-stream state" signal.

## 4. Configuration flow

`internal/config` loads TOML (`client_config.toml.example`,
`server_config.toml.example`), JSON, or base64-encoded JSON, then applies CLI
overrides through `NewClientConfigFlagBinder` / `NewServerConfigFlagBinder`.
Derived limits (queue sizes, fragment-store capacities) are computed in
`Effective*` methods; those capacities are now enforced by
`internal/fragmentstore`.

## 5. Packages with no data-path wiring

These compile and (some) have unit tests, but nothing in `cmd/*` or the engine
calls them. They are reachable only through the CLI flags that now report
themselves as unwired:

`pkg/bale`, `pkg/camouflage`, `pkg/desync`, `pkg/fcm`, `pkg/icmp`, `pkg/ntp`,
`pkg/raptorq`, `pkg/fec`, `pkg/webrtc`, plus the duplicate trees
`pkg/tunnel`, `pkg/tunnel/arq`, `pkg/tunnel/basecodec`, `pkg/tunnel/enums`
(copies of `internal/*`), and the unused `pkg/transport`. Empty directories:
`pkg/netstack`, `pkg/transports`, `pkg/tunnel/internal`.

`pkg/hunter` runs real DNS probes but its result set is printed, not applied to
the client's resolver pool.

## 6. Android application

- `android/app` — Kotlin/Jetpack Compose UI (123 files, ~60k LOC), `VpnService`
  (`SlipNetVpnService`), `hev-socks5-tunnel` C sources under
  `app/src/main/cpp`, per-flavor Gradle setup (`full`, `lite`).
- `android/gomobile-build` — `make build` produces the Go AAR consumed by the
  app (requires JDK/NDK/gomobile; not available on this host).
- Submodules declared in `.gitmodules` (`android/dnstt`, `android/noizdns`,
  `android/vaydns`, `android/vaydns-mobile`, `android/lyrebird`,
  `app/src/main/rust/slipstream-rust[-plus]`); the CI job re-clones them with
  `--depth 1` instead of `git submodule update`.
- Builds happen only in `.github/workflows/build-all.yml` (JDK 21, NDK
  29.0.14206865, cargo-ndk, gomobile). No Android build or device test was
  performed locally in Phase 1.

## 7. Known structural risks

- The Android release job generates a **new signing key on every run**, so APKs
  from consecutive builds cannot be installed over each other.
- Three near-identical copies of the tunnel code exist (`internal/*` and
  `pkg/tunnel/*`); fixes must be applied to the copy that is actually imported
  (`internal/*`) or they will silently have no effect.
- `internal/udpserver` and `internal/client` are large single-package surfaces
  (11k and 13k LOC) with limited package-level boundaries, which makes
  regression isolation harder than it needs to be.
