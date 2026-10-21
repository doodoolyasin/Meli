# ENGINEERING_LOG — Phase 2

Date: 2026-10-21
Repository: `/home/ubuntu/meli-work`
Starting commit: `19ccb5e` (Phase 1 checkpoint), branch `work/verification-2026-10-20`
Working tree at start: clean.

Phase 1 log (`ENGINEERING_LOG_PHASE1.md`) was read and its claims re-checked
against the source: build, vet, unit tests, race suite and the 9 e2e tests were
rerun before any Phase 2 edit and all passed.

## 1. Baseline (rerun, not taken on trust)

| Check | Result |
| --- | --- |
| `go build ./...` | OK |
| `go vet ./...` | clean |
| `go test ./...` | all packages pass |
| `go test -race ./internal/... ./pkg/...` | clean |
| `go test -tags=e2e ./test/e2e` | 9/9 pass, ~35 s |

## 2. Defect P2-1 — the local DNS proxy never delivered a cold answer

**Observed.** With `LOCAL_DNS_ENABLED = true`, a stub resolver sends one query
for an uncached name. The client logs `DNS Query Request`, the server forwards
the query to its configured upstream, the upstream answers — and the stub
receives nothing until it times out. Measured before the fix:

```
dns_e2e_test.go:225: no DNS reply for a cold query after 8s
(upstream saw 1 queries): read udp ...: i/o timeout
```

The upstream counter proves the forward path worked (client → tunnel → server →
upstream) and that only the return direction was broken.

**Correct behaviour.** RFC 1035 §4.2: a UDP resolver expects the answer on the
same socket, correlated by the transaction ID, within its own timeout. A proxy
that silently drops the first query per name forces every client into a
timeout-and-retry cycle, and clients that do not retry (or use a short timeout)
simply fail to resolve.

**Responsible components.**
- `internal/client/dns_listener.go:HandleDNSQueryRes` — assembled and cached the
  answer, then discarded it.
- `internal/client/dns_listener.go:ProcessDNSQuery` — documented itself as
  "fire-and-forget for misses" and returned `false`.
- `internal/client/socks_manager.go:handleSocksUDPAssociate` — treated `false`
  as "close the association to trigger client retry".

**Root cause.** The pending-query callback (`respond func([]byte)`) had no
lifetime beyond the call that created it. `internal/dnscache` tracks
`StatusPending` for deduplication but stores no callbacks, so nothing connected
an arriving answer to the stub that asked for it.

**Fix.**
- New `internal/client/dns_waiters.go`: a bounded registry keyed by
  `qtype|qclass|name` holding `{original query, callback, expiry}`.
  - per-key bound 16 (drop oldest), global bound 2048 (purge expired, then drop
    whole keys), TTL clamped to 2–30 s (`LOCAL_DNS_PENDING_TIMEOUT_SECONDS`).
- `ProcessDNSQuery` now registers the callback, dispatches to the tunnel only
  when `dnscache` says `DispatchNeeded`, and returns `true` when the query was
  accepted (answered now or queued).
- `HandleDNSQueryRes` parses the assembled answer, fills the cache, then hands
  each waiter its own copy patched with its own transaction ID
  (`dnscache.PatchResponseForQuery`).
- The SOCKS5 UDP relay therefore keeps its association open and answers on the
  same socket, as RFC 1928 §7 intends.

**Tests.**
- `internal/client/dns_waiters_test.go` — 6 tests: take-once, per-key bound
  (oldest dropped), global bound, expiry purge, TTL clamping, invalid input.
- `test/e2e/dns_e2e_test.go:TestLocalDNSProxyAnswersColdQuery` — real binaries,
  in-test authoritative resolver, one cold query must be answered with the
  upstream A record, and a second query must be served from cache without
  reaching the upstream again.
- `test/e2e/dns_e2e_test.go:TestSOCKSUDPAssociateCarriesColdDNSQuery` — same
  cold query over a real SOCKS5 UDP ASSOCIATE relay, asserting the relay
  address is usable, the framed reply carries the answer, and the upstream saw
  exactly one query.

**Result.** Both e2e tests pass (5.07 s and 3.71 s wall clock including a 3 s
settle period). The measured cold-query round trip through the tunnel is
**101 ms** on loopback (`cold DNS query answered in 101ms`). Before the fix the
same test failed with `i/o timeout` after 8 s.

## 3. Findings recorded but not fixed in Phase 2

- `SESSION_INIT_RACING_COUNT` is parsed, clamped and stored in
  `internal/config/client.go:462`, but never read by the client: session-init
  racing is not implemented. The knob is inert.
- `internal/client/dns_listener.go` still answers from cache only for the
  listener path when the cache is disabled (`LOCAL_DNS_CACHE_*` off): a nil
  cache means `DispatchNeeded` stays true, which is correct, but the registry
  TTL then governs delivery.
- Android build/device verification remains impossible here (no JDK/NDK/SDK).
- CI still signs each APK with an ephemeral key (documented in Phase 1).

## 4. Verification after Phase 2 edits

| Check | Result |
| --- | --- |
| `go build ./...` | OK |
| `go vet ./...` | clean |
| `gofmt -l internal/client test/e2e` | clean |
| `go test ./...` | all packages pass |
| `go test -tags=e2e ./test/e2e` | 11/11 pass in 42.8 s (9 inherited + 2 new) |
| `go test -race ./internal/client/ ./internal/dnscache/` | clean (6.5 s / 1.0 s) |
| e2e throughput (same test as Phase 1) | 256 KiB round trip in 2.78 s = 92.0 KiB/s; 25 connect/disconnect cycles in 8.37 s with fd 9→9 (the DNS listener adds one socket) |

## 5. Next priorities (Phase 3)

1. **Android data path**: the only unverified half of the product. Needs a JDK
   and either an emulator or CI artifact inspection; nothing about VpnService,
   TUN read/write or the gomobile AAR has been exercised.
2. **Wire or remove the inert transports**: EDNS0/QUIC stego, FEC, 0x20
   camouflage, query-type interleaving, resolver racing
   (`internal/client/tunnel_query.go` encoder + `internal/udpserver` parser).
3. **`SESSION_INIT_RACING_COUNT`**: implement racing or delete the knob.
4. **Cold-start session behaviour**: `handleSocksUDPAssociate` and the DNS
   listener both refuse to work before `SessionReady()`; a stub query that
   arrives during MTU probing is dropped rather than queued.
5. **Pacer end-to-end measurement** (carried over from Phase 1).
