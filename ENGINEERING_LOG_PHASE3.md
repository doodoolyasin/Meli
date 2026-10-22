# ENGINEERING_LOG — Phase 3

Date: 2026-10-21
Repository: `/home/ubuntu/meli-work`
Starting commit: `4b7e337` (Phase 2 checkpoint), branch `work/verification-2026-10-20`
Working tree: modified (see below).

## 1. Repository State at Start

### Current branch and commit
- HEAD: `4b7e337` (Phase 2: deliver cold DNS answers)
- Branch: `work/verification-2026-10-20`

### Working-tree status
Modified files:
- `cmd/server/main.go` — Key fingerprint instead of raw key log
- `internal/udpserver/socks5_upstream.go` — `socksTargetControl()` for destination policy

New untracked files:
- `internal/security/fingerprint.go` — `KeyFingerprint()` truncated hash
- `internal/security/fingerprint_test.go` — Fingerprint unit tests
- `internal/udpserver/socks5_policy_alias_test.go` — SOCKS target policy alias tests
- `test/e2e/impairment_e2e_test.go` — Network impairment proxy and resilience tests

### Changes from Phase 1 (verified)
- All Phase 1 fixes remain in effect (build ✅, vet ✅, race ✅, e2e 11/11 ✅)

### Changes from Phase 2 (verified)
- Local DNS proxy with waiter registry (cold queries answered in 101ms) ✅
- SOCKS5 UDP ASSOCIATE with cold DNS ✅
- Bounded fragment store ✅
- ARQ Done() channel fix ✅
- Pacer over-admission fix ✅
- CLI honesty (12 modes "not wired") ✅

## 2. Risk-Based Reliability Model (Draft)

### Critical domains (investigation priority):

1. **Connection state under impairment** — `TestTunnelResilienceUnderImpairment` exercises 10% loss + duplication + 20-60ms delay + 2.5s outage + recovery. Passes 5 runs with race detector. ⚠️ Need to verify: no goroutine leaks, fd stabilization, state consistency after recovery.

2. **SOCKS target policy enforcement** — New `socksTargetControl()` + alias tests. Default-deny policy blocks IPv4-mapped loopback, IPv4-mapped cloud-metadata, RFC1918 (unless ALLOW_PRIVATE_TARGETS). ⚠️ Need to verify: no bypass paths, policy applies at dial time not just resolution time, metadata endpoint always blocked.

3. **Session cleanup on error** — When a connection fails mid-establishment, are all goroutines and FDs released? ⚠️ No existing test covers this systematically.

4. **Retry/idempotency** — Does the system retry operations that shouldn't be retried (non-idempotent writes)? ⚠️ No regression test exists.

5. **DNS waiter TTL expiry** — The `dns_waiters` registry has per-key/global bounds + TTL. Need to verify: expired waiters are cleaned up, no memory leak from accumulated waiters.

### High domains:

6. **Goroutine lifecycle during connect/disconnect cycles** — `TestRepeatedConnectDisconnectResourceStability` does 25 cycles in 8.37s. Passes, but race detector?

7. **Malformed packet handling** — `TestMalformedUDPTrafficDoesNotDisruptActiveSession` passes. Need to verify: no panic, no FD leak, state remains consistent.

8. **Server restart recovery** — `TestServerRestartRecovery` passes (2.5s recovery). Need to verify: no stale sessions, no duplicated writes post-recovery.

### Medium domains:

9. **Configuration change during active session** — No test exists.

10. **DNS cache expiration and stale waiters** — Related to #5.

### Low/informational:

11. **Build reproducibility** — Lockfiles and toolchain.

12. **Logging structured fields** — Fingerprint module adds non-sensitive identifier.

## 3. Debugging Checkpoint 1 Results

✅ Repository state verified: HEAD `4b7e337`, working tree modifications documented.
✅ Phase 1 & 2 changes verified: all builds/vests/tests pass.
✅ High-risk issues identified: connection state under impairment, SOCKS policy enforcement, session cleanup on error, retry idempotency, DNS waiter TTL expiry.
✅ Baseline established: all 14 e2e tests pass (with `-tags=e2e`), impairment test passes 5 runs with race detector, SOCKS policy tests pass.
✅ Existing work preserved: no files discarded or reset.
✅ Environment limitations recorded: VPS 2vCPU 1.9GB RAM, no JDK/NDK/SDK (Android testing impossible locally).

## 4. Next Steps

Proceed to DEBUGGING CHECKPOINT 2: Create prioritized reliability register and reproduce the most important actionable failures.

**Immediate focus (Critical)**:
1. Run `TestTunnelResilienceUnderImpairment` with race detector 10+ times and inspect goroutine/fd counts before/after.
2. Write a regression test for session cleanup on connection failure.
3. Verify SOCKS target policy has no bypass paths through address aliasing.
4. Add a test for retry behavior on non-idempotent operations.

Please advise which of these to tackle first, or if there's a different high-priority issue you want investigated.
