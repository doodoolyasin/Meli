# Feature verification status

Classification used here (no other meaning is implied):

- **VERIFIED** — exercised by a test that asserts the behaviour end to end.
- **PARTIALLY VERIFIED** — implemented and exercised, but only part of the claim
  (e.g. unit-level only, or one direction) is covered.
- **IMPLEMENTED, NOT VERIFIED** — code exists, nothing asserts it at runtime.
- **IMPLEMENTED, NOT WIRED** — code exists but no code path reaches it.
- **BLOCKED** — needs infrastructure missing on this host (see §Environment).
- **DOCUMENTED ONLY** — appears in docs, not in code.

All end-to-end evidence below is **loopback only** (`test/e2e`, `-tags=e2e`).
No public resolver, no real censored network, no Android device was used.

| Feature | Primary code | Status | Evidence |
| --- | --- | --- | --- |
| DNS tunnel (ARQ over UDP/53), client ↔ server | `internal/arq`, `internal/vpnproto`, `internal/client`, `internal/udpserver` | **VERIFIED (loopback)** | `test/e2e` — 5/5 SOCKS5 subtests, 256 KiB round trip, 25 connect/disconnect cycles |
| SOCKS5 CONNECT to per-target destinations | `internal/client/socks_manager.go`, `internal/udpserver/socks5_upstream.go` | **VERIFIED (loopback)** | `TestSOCKSPerTargetForwarding` (single, two targets, 64 KiB payload, concurrent, half-close) |
| Fixed-forward mode (`FORWARD_IP/PORT`) | `internal/udpserver/socks5_upstream.go` | **VERIFIED (loopback)** | `TestFixedForwardMode` |
| Default-deny target policy | `internal/udpserver/*policy*` | **VERIFIED (loopback)** | `TestSOCKSTargetPolicyDefaultDeny` |
| Reconnect after server restart | `internal/client/client.go:Run` | **VERIFIED (loopback)** | `TestServerRestartRecovery` — recovered on attempt 2 after 2.5 s |
| Malformed-datagram resilience | `internal/udpserver/server_ingress.go` | **VERIFIED (loopback)** | 500 malformed datagrams, session survived, RSS 12.7→14.2 MB |
| fd/goroutine stability under churn | client + server lifecycle | **VERIFIED (loopback)** | 25 cycles, client fd 8→8, server fd 6→6 |
| SOCKS5 UDP ASSOCIATE (DNS relay) | `internal/client/socks_manager.go:handleSocksUDPAssociate` | **PARTIALLY VERIFIED** | Unit tests: reachable BND.ADDR, foreign-source drop, TCP-lifetime teardown. No e2e DNS-through-UDP-associate test |
| Client egress pacer (`-pacer`) | `internal/client/client.go:SetPacerBytesPerSecond`, `async_runtime.go` | **PARTIALLY VERIFIED** | Wired in Phase 1; unit tests prove aggregate throttling (20 KB/s, shared bucket). No e2e throughput measurement yet |
| MTU probing / clamping | `internal/client/mtu.go`, `internal/config` | **PARTIALLY VERIFIED** | Exercised implicitly (session init fails without it); no test asserts the negotiated values |
| SOCKS5 username/password auth | `internal/client/socks_manager.go` | **IMPLEMENTED, NOT VERIFIED** | Only policy helpers are unit-tested |
| SOCKS4 | `internal/client/socks_manager.go` | **IMPLEMENTED, NOT VERIFIED** | Only `supportsSOCKS4` policy is unit-tested |
| Resolver hunter (`-hunt-iran`) | `pkg/hunter` | **PARTIALLY VERIFIED** | Real A-queries are sent; discovered resolvers are printed but **not applied** to the client pool |
| Captive-portal 204 responder (`-captive`) | `pkg/evasion` | **PARTIALLY VERIFIED** | Server starts on 127.0.0.1 (verified by running the binary); no DNS mapping points probe domains at it |
| EDNS0 OPT steganography (`-edns0`) | `pkg/desync` | **IMPLEMENTED, NOT WIRED** | `cmd/server` has no option-65001 parser; flag now warns and does nothing |
| QUIC DCID camouflage (`-quic-dcid`) | `pkg/desync` | **IMPLEMENTED, NOT WIRED** | Same as above |
| FEC / Reed-Solomon (`-fec`) | `pkg/fec`, `pkg/raptorq` | **IMPLEMENTED, NOT WIRED** | Encoder is constructed and discarded; no repair packets are emitted |
| 0x20 / CDN-prefix camouflage (`-camo`) | `pkg/camouflage` | **IMPLEMENTED, NOT WIRED** | Helper functions only; encoder never calls them |
| DNS query-type interleaving (`-interleave`) | `pkg/camouflage` | **IMPLEMENTED, NOT WIRED** | Same |
| Random padding (`-padding`) | `pkg/desync` | **IMPLEMENTED, NOT WIRED** | Same |
| Multi-resolver racing (`-race`) | `pkg/hunter`, `internal/client/balancer.go` | **IMPLEMENTED, NOT WIRED** | The flag only prints a message; the balancer always uses its normal selection |
| ICMP tunnel (`-icmp`) | `pkg/icmp` | **IMPLEMENTED, NOT WIRED** | Client-side codec only; no server counterpart, so it cannot carry traffic at all |
| NTP tunnel (`-ntp`) | `pkg/ntp` | **IMPLEMENTED, NOT WIRED** | Client codec + tests (Phase 1); no server counterpart |
| FCM tunnel (`-fcm`) | `pkg/fcm` | **IMPLEMENTED, NOT WIRED** | Client-side TCP codec only; no server counterpart |
| WebRTC SFU tunnel (`-webrtc`) | `pkg/webrtc` | **IMPLEMENTED, NOT WIRED** | Frame codec + multiplexer; no signalling/ICE implementation, no server |
| Bale Meet tunnel (`-bale`) | `pkg/bale` | **IMPLEMENTED, NOT WIRED** | Depends on `pkg/webrtc`; same limitation |
| DNS front-door demux | `cmd/demux` | **IMPLEMENTED, NOT VERIFIED** | Not exercised by any test |
| Android app (Compose UI, VpnService, gomobile AAR) | `android/**` | **BLOCKED** | No JDK/NDK/SDK on this host; build path is CI only |
| dnstt / noizdns / vaydns / slipstream integration | `android/*` submodules | **BLOCKED** | Submodules are not initialised locally; CI clones them with `--depth 1` |

## Claims in README that this table contradicts

- The README describes FEC, EDNS0/QUIC steganography, camouflage, query-type
  interleaving and resolver racing as active parts of the engine. They are
  packages with no call sites in the data path.
- The README describes auto-hunting as feeding the client's resolver pool. It
  discovers resolvers and prints them.
- The README lists ICMP/NTP/FCM/WebRTC/Bale as usable channels. Only the client
  half exists.

## What would move each row forward

- UDP ASSOCIATE: an e2e test that resolves a name through the SOCKS5 UDP relay.
- Pacer: run the e2e throughput test with `-pacer` and compare with the 95.5 KiB/s
  baseline.
- Stego/FEC/camouflage/interleaving/racing: wire each into
  `internal/client/tunnel_query.go` (encoder) and the server parser, then assert
  on the wire bytes.
- ICMP/NTP/FCM/WebRTC/Bale: a server-side listener per channel is a prerequisite.
- Android: `./gradlew assembleFullRelease` in CI with a stable keystore, then a
  device/emulator smoke test.
