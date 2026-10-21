# ENGINEERING_LOG — Phase 1

Date: 2026-10-21
Repository: `/home/ubuntu/meli-work`
Module: `github.com/doodoolyasin/meli`
Branch at start: `work/verification-2026-10-20`
Commit at start: `13f69913 2211...` — `checkpoint: Phase 2 inherited tree before Phase 3 reliability work`

## 1. Initial state (before any Phase 1 edit)

Working tree was **not clean**. These were inherited and preserved, not reverted:

```
 M internal/client/socks_manager.go
 M internal/dnsparser/parser.go
 M internal/udpserver/server_deferred.go
 M internal/udpserver/server_postsession.go
 M test/e2e/socks5_e2e_test.go
?? internal/client/socks_handshake_deadline_test.go
?? internal/dnsparser/parser_bounds_test.go
?? test/e2e/phase3_reliability_test.go
```

Phase 1 edits were applied on top of that state; the two sets are not separated
by commit.

Environment: 2 vCPU, 1.9 GB RAM (1.0 GB available), 16 GB free on `/`,
Go 1.24.1, git 2.43, Python 3.14, **no JDK/Android SDK/NDK**, no docker.
Consequence: Go can be built and tested locally; Android cannot (CI only).

## 2. Baseline measurements (before edits)

| Check | Result |
| --- | --- |
| `go build ./...` | OK, 3.17 s |
| `go vet ./...` | 1 finding: `pkg/ntp/ntp.go:92 uint32(now.Nanosecond()) (32 bits) too small for shift of 32` |
| `gofmt -l .` | 13 unformatted files (`cmd/demux`, `pkg/desync`, `pkg/pacer`, `pkg/raptorq`, `pkg/transport`, `pkg/tunnel/*`, `test_fixed.go`) |
| `go test ./...` | all packages pass (469 tests) |
| `go test -tags=e2e ./test/e2e` | 9/9 pass in 36.3 s; 95.5 KiB/s effective, small-RTT mean 192 ms / worst 202 ms; 25 connect/disconnect cycles in 7.5 s with fd counts flat |
| `go test -race ./internal/... ./pkg/...` | **FAIL**: data race in `internal/udpserver` (test mock), data race in `internal/client` (mutable package global), intermittent flake in `internal/arq` |

## 3. Defects reproduced, root causes, fixes

### 3.1 NTP timestamp fraction always zero (correctness / stealth)

- Observed: `go vet` flags `uint32(now.Nanosecond()) << 32 / 1000000000`.
- Root cause: operator precedence — `(uint32(ns) << 32) / 1e9`. Shifting a
  32-bit value by 32 yields 0, so every covert NTP packet claimed to be emitted
  exactly on a second boundary. That is a stable fingerprint for DPI/NTP
  monitors.
- Fix: `pkg/ntp/ntp.go` — `ntpTimestamp()` using 64-bit arithmetic.
- Tests: `pkg/ntp/ntp_test.go` — fixed-point values (0 s, 0.5 s, 1 ms), wire
  layout of `Send`, `Recv` round trip, short-key rejection.
- Result: `go test ./pkg/ntp` 4/4 pass; `go vet ./pkg/ntp` clean.

### 3.2 SOCKS5 UDP ASSOCIATE was an open relay with leaked descriptors (security / reliability)

- Observed by reading `handleSocksUDPAssociate`:
  - `net.ListenUDP("udp", 0.0.0.0:0)` — bound on every interface.
  - The reply carried `BND.ADDR = 0.0.0.0` (the computed `replyIP` was unused).
  - No source-address check: any host that could reach the port could relay DNS
    through the tunnel.
  - The TCP control connection was never closed and its lifetime was unrelated
    to the association (RFC 1928 §7 requires the association to end with it).
- Root cause: the association was modelled as a standalone UDP server instead of
  a per-client relay owned by the control connection.
- Fix: `internal/client/socks_manager.go`
  - bind on the local address of the control connection, fall back to 127.0.0.1;
  - reply with the real bound address/port;
  - `defer conn.Close()` plus a reader goroutine that closes the UDP socket when
    the control connection ends;
  - `socksUDPPeerAllowed()` pins the source IP to the control peer and learns the
    source port from the first accepted datagram;
  - failure paths of `handleSOCKSConnect` now close the socket (they previously
    returned with the connection open).
- Tests: `internal/client/socks_udp_associate_test.go` — reply address is
  reachable, foreign loopback source (`127.0.0.2`) is dropped, association ends
  when the TCP connection closes, table test for the peer policy.
- Result: 4 tests pass, including the negative test that a foreign datagram does
  **not** terminate the association.

### 3.3 Unbounded fragment stores (remote memory exhaustion)

- Observed: `internal/fragmentstore.Store` accepted a `capacity` and used it only
  as a map size hint. Keys are `{session, sequence}` / `{session, stream,
  sequence}`, i.e. attacker-chosen. Each live entry embeds `[256][]byte`.
  `internal/config` computes `Effective*FragmentStoreCapacity()` (clamped to
  64…8192) that was never enforced.
- Root cause: missing enforcement of the configured bound.
- Fix: `internal/fragmentstore/store.go` — hard cap on live entries and on
  completion markers, with cheap arbitrary eviction (documented trade-off).
- Tests: `store_test.go` — 256 inserts hold ≤ cap, completion markers hold ≤ cap,
  and a fresh key can still start and finish an assembly after eviction pressure.
- Result: 4/4 pass.

### 3.4 Zero MTU caused an infinite fragmentation loop (hang)

- Observed: `fragmentPayload(payload, 0)` loops forever (`i += 0`) appending
  slices → hang + unbounded memory. Reachable if the upload MTU is ever 0 at a
  call site (session-reset race, or a future caller).
- Fix: `internal/client/client_utils.go` — non-positive MTU means "no
  fragmentation".
- Tests: `internal/client/client_utils_test.go` — terminates for 0/-1/-4096
  within a 2 s guard; exact split/reassembly for 10 bytes at MTU 4.

### 3.5 Race: mutable package-global handshake timeout

- Observed: `go test -race ./internal/client` — write in the test restoring
  `socksHandshakeTimeout` vs read in `beginSocksHandshake` (goroutine).
- Root cause: timeout stored in a package variable, mutated at runtime.
- Fix: moved to `Client.socksHandshakeTimeout` with
  `defaultSocksHandshakeTimeout = 30s`; `beginSocksHandshake` became a method.
- Result: `go test -race -run 'SOCKS|Socks' ./internal/client` passes.

### 3.6 Race: `Done()` fired before the ARQ close epilogue

- Observed: `go test -race -count=8 ./internal/arq` — `t.Logf` from
  `finalizeClose` racing the test's completion, because `Done()` returned
  `ctx.Done()` and `finalizeClose` cancels the context **before** logging and
  calling `OnARQClosed`.
- Impact beyond tests: any caller treating `Done()` as "stream fully closed,
  release shared state" could run concurrently with `OnARQClosed`.
- Fix: `internal/arq/arq.go` — dedicated `done` channel closed at the end of
  `finalizeClose`; `Done()` returns it.
- Result: race no longer reproduces; `go test -race -count=8 ./internal/arq`
  then exposed a separate timing-sensitive test (see §5).

### 3.7 Race in a test double

- `internal/udpserver/stream_syn_test.go:testNetConn.closed` was a plain bool
  written by the production reaper goroutine and read by the test.
- Fix: `atomic.Bool` in `testNetConn` and `testReadWriteCloser`.
- Result: `TestProcessDeferredSOCKS5SynDoesNotAttachAfterCancellation` passes
  under `-race`.

### 3.8 CLI reported unimplemented modes as active (honesty / integration)

- Observed: `cmd/client/main.go` printed "active"/"standby" for pacer, EDNS0,
  FEC, racing, camouflage, padding, ICMP, interleaving, WebRTC, NTP, FCM and
  Bale while only constructing a value and discarding it. `cmd/server/main.go`
  did the same for EDNS0/QUIC DCID. `-hunt-iran` printed discovered resolvers
  without applying them.
- Fix: the client now prints an explicit "requested, but not wired into the
  transport" line per flag, the server logs a warning, and the hunter says
  "discovery only". The pacer was **implemented for real** instead of reported
  (see §4).
- Verified by running the built binary with all flags: 11 honest warnings +
  captive-portal line.

### 3.9 CI could be green with no artifacts; APK signing key was ephemeral

- `.github/workflows/build-all.yml`:
  - `cp ... || true` and `gh release upload ... || true` hid missing APKs and
    failed uploads.
  - `go mod tidy` in CI could rewrite the dependency graph instead of failing.
  - A new keystore is generated per run with password `123456`, so consecutive
    APKs cannot be installed as updates.
- Fix: `go mod download` + `go mod verify`; APK collection fails the job when no
  APK is produced; release is created if missing and upload failures are no
  longer swallowed; the ephemeral-keystore problem is documented in the workflow
  (fixing it needs a secret I do not have).
- YAML validated with `yaml.safe_load`.

## 4. Implemented feature (not just a fix): client egress pacer

- `Client.SetPacerBytesPerSecond(rate)` installs an `atomic.Pointer`-held
  `pacer.LeakyBucket`; `paceOutbound()` is called once per datagram in
  `asyncWriterWorker`. Nil bucket costs one atomic load.
- CLI `-pacer KB/s` now actually limits aggregate egress.
- Writing the concurrency test exposed a bug in `pacer.SleepUntilSend`: it slept
  once and, if the wake-up was still short, returned **without deducting
  tokens**, so two waiters could both be admitted on one budget. Fixed to loop
  until tokens are actually taken (mutex released while sleeping). Without that
  fix the newly wired pacer would have over-admitted under load.
- Tests: `internal/client/pacer_test.go` — no cost when disabled (2000 datagrams
  < 100 ms), 60 KB at 20 KB/s takes ≥ 0.8 s, and two concurrent workers share
  the limit (40 KB at 10 KB/s ≈ 2 s, which is the theoretical minimum).
- Not yet measured end to end; that is the first item in §6.

## 4.1 Security review performed (limited)

Scope: Go sources, CLI flags, Android manifest. Not a full audit; no dynamic
testing against a hostile network, no APK inspection (no Android toolchain).

- No `InsecureSkipVerify` anywhere in the Go tree.
- No hardcoded encryption keys, API keys or passwords in Go or Kotlin sources
  (the test key in `test/e2e` is generated per run).
- Tunnel crypto uses AES-GCM/ChaCha20-Poly1305 via `internal/security`; no
  custom cipher construction was found.
- Fixed in this phase: open UDP relay on SOCKS5 UDP ASSOCIATE (§3.2), unbounded
  fragment stores (§3.3).
- Found, **not** changed (needs a device test): `BootReceiver` is
  `android:exported="true"`, so any installed app can send it a boot intent and
  trigger the app's start-on-boot path. Widget providers and the Quick Settings
  tile must stay exported (framework requirement).
- Found, **not** fixed: CI signs each APK with a freshly generated key and the
  password is in the workflow file (§3.9); release integrity is therefore not
  established.

## 5. Final verification (after all edits)

| Check | Result |
| --- | --- |
| `go build ./...` | OK |
| `go vet ./...` | clean (the NTP finding is gone) |
| `go test ./...` | pass |
| `go test -race ./internal/... ./pkg/...` | clean (exit 0, no race report) |
| `go test -tags=e2e ./test/e2e` | 9/9 pass after all edits: 25 cycles in 8.17 s, fd flat (8/6), 256 KiB round trip at 136.0 KiB/s, RTT unchanged, server restart recovered in 2.5 s |
| `go test ./internal/arq` repeated 6× | 6/6 pass |
| `go test -race -count=8 ./internal/arq` | 1 failure in `TestARQ_ClientGracefulCloseWriteFailureQueuesCloseWrite` (see below) |

Known flake: `TestARQ_ClientGracefulCloseWriteFailureQueuesCloseWrite` calls
`ReceiveData` while the write loop may already have failed the upstream write
and finalised the close; whether `ReceiveData` is accepted depends on
scheduling. It passes alone and in normal `-count=1` runs, and failed once in
eight repeated runs in one process. Not fixed in Phase 1 because the assertion
itself needs a decision (make the ordering deterministic vs. weaken the claim).

## 6. Next technical priorities (in order)

1. **Pacer e2e measurement**: extend `test/e2e` to start the client with
   `-pacer` and compare throughput against the 95.5 KiB/s baseline.
2. **UDP ASSOCIATE e2e**: resolve a name through the SOCKS5 UDP relay to cover
   the path that now has only unit tests.
3. **Flaky ARQ close test**: decide the intended ordering and make the test
   deterministic.
4. **Wire the encrypted/camouflage options** (`internal/client/tunnel_query.go`
   encoder + server parser) or delete the flags. Current state is honest but
   inert.
5. **Server-side listeners for ICMP/NTP/FCM/WebRTC/Bale**, or demote those
   packages to research code.
6. **Stable Android keystore in CI** so release APKs are upgradeable.
7. **Remove the duplicated `pkg/tunnel` trees** after confirming no out-of-tree
   consumer, so future fixes cannot land in the wrong copy.

## 7. Decisions that affect later phases

- Phase 1 did not change the wire protocol, config format, or ARQ semantics
  except `Done()` timing (strictly later, never earlier).
- `pkg/tunnel*` was left in place deliberately: unused, but harmless, and
  deleting 7.7k LOC without confirming external consumers is riskier than
  documenting it.
- Eviction in `fragmentstore` is arbitrary rather than LRU: under a flood the
  attacker's entries and legitimate ones are indistinguishable, and bounded
  memory was the priority. Revisit only if real loss is observed.
