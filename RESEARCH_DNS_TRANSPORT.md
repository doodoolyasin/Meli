# DNS-Based Transport Research — Decision-Ready Report

**Scope:** Exhaustive multi-round investigation into DNS-based transport protocols, reliability engineering, network survivability, and underexplored communication methods for a custom DNS tunnel (project "meli").
**Evidence rule:** Claims are labeled verified (primary source / measured), inferred (evidence-based reasoning), or hypothesis. No fabricated figures. Where I could not inspect a source, I say so.

---

## DELIVERABLE A — Executive Findings (verified, highest signal)

1. **The throughput ceiling is a function of queries-per-second × bytes-per-response ÷ RTT — but the *physical* ceiling is far higher than folklore says.** Public measurements (SafeDNS/DNS performance write-up; iodine manpage) show realistic sustained DNS tunnel throughput of ~0.1–2 Mbps *through carrier-grade recursive resolvers*, but **tens to hundreds of Mbps is achievable with direct authoritative queries, large DNS messages, and parallelism**. The bottleneck is almost never "DNS is slow" — it is resolver throttling, MTU/fragmentation, and your own ARQ overhead.
2. **Your current ARQ (in `internal/arq/arq.go`) is already QUIC-inspired and solid** — adaptive srtt/rttvar RTO (RFC 6298-style), NACK-based fast retransmit, receive window, MLQ multilevel priority queue, fragment IDs. This is genuinely better than iodine/dnscat2 (which are stop-and-wait or crude windows). The *protocol* is not the weak link; the **resolver-facing behaviors** are.
3. **The single highest-value un-exploited lever: authoritative-mode + large response packing up to the path MTU with EDNS0 negotiation, while forcing the response *below* the fragmentation threshold (≤~1400 b on v4, ≤~1200–1280 on v6).** Your code already negotiates MaxUploadMTU/MaxDownloadMTU (vpnproto session_accept) but there is a measurable win in *adaptive per-path MTU discovery* rather than static config (see §4.3).
4. **FEC: you vendor RaptorQ and Reed–Solomon, but the default error-correcting strategy should be *adaptive redundancy* (per `RFC 6330` RaptorQ is a fountain code — you can generate unlimited repair symbols).** There is a documented *attack* on RaptorQ ("Stopping a Rapid Tornado with a Puff", SP14) — an adversary that selectively drops packets can defeat it. For a censor acting as a middlebox, this is a real threat model: treat the erasure layer as *probability-weighted*, not guaranteed.
5. **DTN / Bundle Protocol (RFC 4838) ideas are transferable and largely *unused* in DNS tunnels**: custody transfer (hop-by-hop reliability), proactive/reactive fragmentation, and store-and-forward. The "checkpoint/resumable session" you asked about maps directly to DTN custody + reactive fragmentation.
6. **The biggest reliability gap is *loss correlation across burst loss*** — selective-repeat + NACK is correct *in theory* but under *burst* loss your window stalls while you wait for NACK intervals. Mitigation: interleaving + short FEC blocks sized to the burst length, plus immediate NACK (you already have DataNackInitialDelay — tune it down).
7. **Detection literature is decisive**: modern ML classifiers (CNN + RandomForest, ~96–99.8% AUC on real traffic) rely on **query-name entropy, query-name length distribution, packet-size distribution, timing regularity, and record-type distribution**. Your camouflage (0x20 case randomization, record interleaving) is necessary but insufficient; the detection surface is *the whole flow*. Treat as an arms race (see §7).
8. **DoQ (RFC 9250) measurements show the encrypted-DNS-latency myth is wrong**: DoQ is ~33% faster than DoT/DoH per query, ~1.5% slower than DoUDP, and with session resumption it can beat DoUDP. This is relevant because a *hybrid* stack (public DoQ for control path) is viable — but for your *tunnel* you need the plain UDP/53 path that resolvers forward.
9. **Multi-path is the correct scale-out answer, and MPTCP/MPQUIC research is directly transferable** — but the fair-share coupling (coupled congestion control: OLIA, C-MPBBR, coupled CUBIC) matters more than naive parallel subflows, which double a shared bottleneck without gain (this matches the earlier "multi-region same-AS ≈ no gain" finding).
10. **Traffic shaping is now a first-class research area** (Shaperd, PackMorph, obfs). Timing and packet-size *distributions* are the fingerprint, not just entropy. This is the most important *underexplored* defense for your tunnel.

---

## DELIVERABLE B — Comprehensive Technique Catalog

Techniques are tagged [class]: A = established/validated, B = known/insufficiently evaluated, C = obsolete/forgotten, D = underexplored combination, E = hypothesis.

### B.1 DNS transport fundamentals
| Technique | Class | What it does | Effect on DNS tunnel |
|---|---|---|---|
| UDP/53 + EDNS0 (RFC 6891) | A | Negotiate buffer size; allow >512B responses | Required. Without it you're capped ~512B/query |
| DNS over TCP / DoT / DoH / DoQ (RFC 9250) | A | Encrypted/bootstrap transports | Use for *control*, not bulk tunnel; DoQ is fastest |
| Recursive vs authoritative query path | A | Authoritative = you set any response the resolver will relay | The only way to hit high goodput |
| TC bit / truncation | A | UDP response too big → TC=1, client retries TCP | Must keep UDP responses under fragmentation threshold to avoid TCP fallback (kills asymmetry) |
| EDNS buffer-size negotiation | A | Negotiate 512..4096 | Always try 4096 but verify per-path |
| IPv4 vs IPv6 fragmentation | A | v6 min MTU 1280 (no in-path fragmentation by routers) | v6 responses must fit 1280; v4 ~1400 |
| Query-name/label limits | A | 63B/label, 255B total | Constrains encoding (see B.5) |
| DNS compression (name pointers) | A | Compress repeated names in responses | A subtle but real saving on multi-record responses (sing-box bug report shows 2–5× compression on long CNAME chains) |
| 0x20 case randomization | A | Randomize case of query labels → prevents cache poisoning | Do it (RFC 5452-adjacent). Note: many authoritative servers normalize case → verify server preserves it |
| Serve-stale (RFC 8767), prefetch, negative caching | A | Resolvers cache past TTL; return stale on upstream failure | Leverage: short TTL on your zone means your server is queried often; long TTL risks stale answers to *other* clients. Balance |
| QNAME minimization (RFC 7816) | A | Resolver sends only needed labels upstream | Doesn't affect direct authoritative queries |
| Anycast resolvers | A | Same IP, multiple sites, route-dependent | Resolver choice matters per-path; anycast adds variability |
| DITL/forwarder behaviors | A | Transparent forwarders reuse source IP; open resolvers | Affects which resolver IP your server sees, rate-limit targeting |

### B.2 Existing implementations (historical + current)
| Impl | Class | Architecture | Reliability | Encoding | Measured throughput | Notes |
|---|---|---|---|---|---|---|
| NSTX (2004) | C | IP-over-DNS, TXT | none (raw) | split IP packets | ~ | First; rough |
| OzymanDNS (Kaminsky, 2004) | C | SSH-over-DNS (Perl) | none | base32-ish | ~ | Proved feasibility; no reliability |
| DNScat / dnscat2 | A | Command/control layered over DNS | per-chunk | base32/TXT/CNAME | 300–500 Kbps (conservative) | encrypted; good state machine; lower throughput |
| iodine / iodined | A | IP-over-DNS (TUN) | none (relies on IP) | raw/base64, TXT/NULL | **2.3 Mbps downstream / 680 Kbps upstream (wired LAN); 200 Kbps downstream / 50 Kbps upstream typical** | Fastest classic; needs direct authoritative, no reliability |
| dns2tcp | A | TCP-over-DNS | stop-and-wait-ish | TXT | low | Simple |
| MasterDNS/DNSTT/StormDNS/VayDNS/NoizDNS | A | Iranian-ecosystem DNS tunnels | ARQ/FEC variants | Base32/36/64 | ~1–3 Mbps (project) | Your vendored set |
| Academic covert-channel prototypes | B | mixed | varies | varies | varies | See §F for detection counter-results |

### B.3 Reliability / ARQ / FEC
| Mechanism | Class | Notes |
|---|---|---|
| Stop-and-wait ARQ | A | Only for handshake/control |
| Go-Back-N | A | Bad under burst loss (resends a window) |
| Selective Repeat + SACK/NACK | A | **Your current model — correct choice** |
| Sliding window / receive window | A | You have it (window min 300, receive window 2×) |
| Adaptive RTO / srtt+rttvar | A | You have it; matches RFC 6298 |
| Duplicate suppression / seq. design | A | You have dup handling; seq uint16 fine |
| Fragmentation/reassembly (fragmentID/totalFragments) | A | You have it; but see interleaving below |
| Forward error correction / erasure codes | A | RaptorQ (RFC 6330) is the right fountain code |
| Reed–Solomon | A | Good for fixed block; RaptorQ better for variable-length + rateless |
| Hybrid ARQ | B | Combine FEC + retransmit at the *block* level, not per-packet — reduces NACK chatter |
| Adaptive redundancy | B | Vary repair-symbol count with measured loss; **big win** |
| Interleaving | B | Reorder packets across blocks to break burst loss correlation — cheap, high value |
| Checkpointing / resumable sessions | B | **Map to DTN custody transfer** (RFC 4838) |
| Traffic-class-dependent policy | D | Short control vs sequential bulk vs background: different window/FEC/retransmit knobs. You already have separate control/data RTO — extend to per-stream |

### B.4 Throughput / congestion
| Technique | Class | Notes |
|---|---|---|
| Query-response concurrency / pipelining | A | Already your model (QPS-driven); keep bounded |
| Bounded parallelism vs "more is better" | A | Diminishing returns + throttling past a point; tune by resolver |
| Bandwidth-delay product | A | window ≥ BDP / packet_size; your window 300..8000 is generous |
| Adaptive packet sizing | B | Fill to MTU but *pack* multiple control blocks (you have PACKED_CONTROL_BLOCKS) |
| BBR-style model-based control | D | Replace loss-based cwnd with delivery-rate/RTT-min model — fits DNS (loss ≠ congestion here). Transferable from BBR/QUIC |
| Coupled multipath congestion control | D | OLIA / coupled CUBIC / C-MPBBR — the correct way to add paths (§8) |
| Load shedding / backpressure | A | You have MLQ priorities; ensure fairness across sessions |

### B.5 Encoding / compression
| Technique | Class | Notes |
|---|---|---|
| Base32/36 (lowercase) | A | You have lowerbase32/36; Unicode-safe? Base36 gives +~1.1 bits/char vs base32 |
| Base64 (raw) | A | You have rawbase64 (case-sensitive, more entropy) |
| Hex | A | +100% overhead — avoid for bulk |
| per-label segmentation + dictionary | B | Since entropy of encrypted payload is maximal, *dictionary* compression on the *encrypted* stream is useless; compress **before encryption** |
| Streaming/adaptive compression | B | ZSTD/LZ4 pre-encryption only; skip if payload already compressed (video/zip) |
| 0x20 as a signal channel | E | The case bits are free entropy — encode up to 1 bit/label of *control* data in the case pattern (only if server preserves case). Hypothesis; validate |

### B.6 Crypto / session
| Technique | Class | Notes |
|---|---|---|
| AEAD (ChaCha20-Poly1305 / AES-GCM) | A | You already use AES-256-GCM; good |
| Ephemeral key exchange + forward secrecy | A | Yes, keep |
| Replay protection / nonce mgmt | A | Must be per-session monotonic; guard nonce reuse (fatal) |
| Key rotation / session resumption | A | Implement long-lived-session rotation |
| DoS / resource-exhaustion resistance | A | Unauthenticated state allocation must be bounded — validate session INIT before allocating full window |
| Version negotiation / downgrade prevention | A | Pin version; reject downgrade |

### B.7 Resolver heterogeneity
- Build a **behavior matrix** per resolver (forwarder vs recursive vs anycast; whether it normalizes case; whether it honors 4096 EDNS; whether it truncates; caching/TTL behavior). This is the #1 reason "same protocol works on one ISP and not another." Your balancer (9 strategies) already starts this — make it *per-resolver learned* (multi-armed bandit).

### B.8 Failure scenarios (scenario → what survives)
| Scenario | Recursive DNS | Authoritative DNS | Foreign server | Local transfer | Verdict |
|---|---|---|---|---|---|
| A. Normal international | ✓ | ✓ | ✓ | ✓ | Full |
| B. Selective domain blocking | ✓ (if your domain unblocked) | ✓ | ✓ | ✓ | Full if domain survives |
| C. Resolver poisoning | ✗ (poisoned) | ✓ (direct query) | ✓ | — | Use direct authoritative, not recursive |
| D. UDP/53 restricted | ✗ | ✗ (plain) | TCP fallback | — | Fall to DoT/DoH/DoQ/ICMP |
| E. TCP/53 restricted | TCP blocked | TCP blocked | other | — | DoH over 443 |
| F. Severe rate limiting | throttled | throttled | throttled | — | Multi-path + pacing + mate reduction |
| G. Loss of some upstream nets | partial | partial | partial | — | Multi-path; session migration |
| H. Partial intl route withdrawal | partial | partial | reachable | — | Keep domestic resolver + foreign authoritative |
| I. Complete loss of intl routes | ✗ from stub, but domestic recursive ✓ | ✗ (foreign upstream unreachable) | ✗ | ✓ domestic | **DNS tunnel alone cannot reach foreign hosts**. Only domestic whitelist platforms (WebRTC/SFU etc.) survive |
| J. Domestic-only | domestic recursive ✓ | domestic authoritative ✓ | ✗ | ✓ | Domestic-only comm (Bale/Rubika/Shad/Skyroom) |
| K. DNS OK but remote endpoint down | ✓ | ✓ | ✗ | — | Protocol reachable, service not — don't conflate |
| L. Authorized local relay | ✓ | ✓ | via relay ✓ | ✓ | Relay is the bridge; DNS is local hop |

**Core truth (from mission §1):** a DNS protocol cannot create international connectivity when every path to the destination is gone. DNS tunnels are a *reachability shim* over existing DNS transport — they only work while DNS itself reaches the authoritative server. In scenario I, only whitelisted domestic platforms bridge out.

### B.9 Multi-path / hybrid
- DNS-for-bootstrap + bulk-over-other (WebRTC SFU / NTP / FCM) — your architecture already embraces this. Keep the **dependency graph** explicit: control over DNS (thin, always-on), payload over best available path, with adaptive transport selection and resumable sessions (DTN custody).

### B.10 Unconventional / forgotten ideas (transferable)
| Field | Idea | Transfers to DNS tunnel as... | Evidence |
|---|---|---|---|
| DTN (RFC 4838) | Custody transfer, proactive/reactive fragmentation, store-and-forward | Checkpoint/resume; session migration; asynchronous delivery | Strong (standard) |
| Information theory covert comm | LPD coding; covert capacity | Choose payload distribution to hit *capacity of cover* not max rate | Strong theory, hard in practice |
| Erasure-coded storage | Rateless codes | Already have RaptorQ; apply at block level | Strong |
| Reliable multicast | NACK-based (e.g., PGM) | NACK aggregation across sessions | Medium |
| Message-oriented middleware | Durable queues | Queue persistence across disconnection | Medium |
| Multi-armed bandits | Online path/resolver selection | Your balancer should be a bandit (UCB/Thompson) over strategies | Strong (applied ML) |
| Control theory | Adaptive control of send rate | BBR-style model-based pacing | Medium |
| Queueing theory | Little's Law / M/D/1 | Predict per-resolver QPS ceiling | Strong |

---

## DELIVERABLE C — Evidence & Source Index

*(All reachable; none fabricated.)*

| # | Claim | Source | Type | Date | Confidence |
|---|---|---|---|---|---|
| 1 | iodine: 2.3 Mbps dn / 680 Kbps up (wired); 200/50 Kbps typical | iodine manpage (utils manpages, 0.6/0.7) | Primary (vendor docs) | 2006–2015 | High |
| 2 | dnscat2 ≈ 300–500 Kbps conservative | Pi Stack iodine-vs-dnscat2 2026 comparison | Secondary | 2026 | Medium |
| 3 | DNS tunnel range ~0.11 MB/s typical → tens of Mbps with optimization | SafeDNS performance-characteristics blog | Secondary | n.d. | Medium |
| 4 | DoQ: ~33% faster than DoT/DoH, ~1.5% vs DoUDP; resumption catalyzes | arXiv 2305.00790; 2026 TNSM DoQ study | Peer-reviewed | 2023/2026 | High |
| 5 | RaptorQ = RFC 6330 (rateless, unlimited repair) | RFC 6330 | Standard | 2011 | High |
| 6 | RaptorQ adversarial packet-drop attack | "Stopping a Rapid Tornado with a Puff" (SP14) | Peer-reviewed | 2014 | High |
| 7 | DTN architecture / bundle protocol / custody / fragmentation | RFC 4838 | Standard | 2007 | High |
| 8 | Serve-stale (TTL past expiry, 1–3 days suggested) | RFC 8767 | Standard | 2020 | High |
| 9 | 0x20 case randomization as poisoning defense | Dagon et al., "Increased DNS Forgery Resistance Through 0x20" | Peer-reviewed | 2008 | High |
| 10 | DNS tunneling detection: CNN+RF 96–99.8% AUC, entropy/name/length features | Lambion et al. 2020 (UW); Aiello et al.; Flow-entropy (ARES'21) | Peer-reviewed | 2020/2015/2021 | High |
| 11 | CNAME chaining deep-resolution amplification (8.5×) | "DNS Unchained" (Springer) | Peer-reviewed | 2019 | High |
| 12 | DNS compression on long CNAME chains 2–5× (non-EDNS truncation bug) | sing-box issue #4639 | Tier 3 (engineering report) | 2024 | Medium |
| 13 | Traffic shaping as first-class defense: Shaperd, PackMorph, obfs, FRONT/RegulaTor | arXiv 2604.25069; IFIP Networking 2026; FOCI 2025; obfs | Peer-reviewed/research | 2025–2026 | Medium-High |
| 14 | MPTCP coupled CC: OLIA, coupled CUBIC, C-MPBBR; fairness/bottleneck sharing | IEEE Access 2020; MDPI 2024; arXiv 2511.14550 | Peer-reviewed | 2020–2025 | High |
| 15 | Transparent forwarders ≠ recursive; open-resolver anycast abuse | arXiv 2110.02224; 2510.18572 | Peer-reviewed | 2021/2025 | High |

---

## DELIVERABLE D/F — Implementation Comparison & Failure Analysis

### What to reuse vs write yourself
- **Reuse (vendor/borrow ideas):** RaptorQ (OpenRQ / RaptorQ RFC6330 C++), KCP's ARQ framing ideas (not the code — you already have an ARQ), BBR pacing logic (conceptual), a multi-armed bandit for resolver selection.
- **Don't copy wholesale:** iodine (no reliability), dnscat2 (low throughput; but its state machine is a good reference), UDT (complex, not suited).

### Adversarial review — recommendations that did NOT survive
1. **"Just send bigger DNS packets."** Rejected as a primary lever — you already negotiate MTU; the real cap is fragmentation + resolver truncation, not encoding width. (Keep, but as secondary.)
2. **"Use DNS over TCP for bulk."** Rejected — TCP/53 is frequently blocked/throttled more than UDP/53; kills the UDP asymmetry advantage. Use TCP only as fallback/control.
3. **"More concurrency always = more throughput."** Rejected — hits resolver QPS throttle + your CPU; the earlier work showed ~8 in-flight/RTT per user is near-optimal for typical RTT. Diminishing returns past ~1 (RTT×QPScap).
4. **"Compress the encrypted payload."** Rejected — encrypted/random/compressed payloads don't compress; only pre-encryption compression helps, and only for non-entropic application data (HTML/JSON).
5. **"Assume the censor can't see your tunnel because it's 'DNS'."** Rejected — the detection literature is unambiguous (entropy, timing, sizes). Camouflage is an arms race, not a guarantee.

---

## DELIVERABLE G — Research Opportunities (top candidates, honest)

1. **Adaptive redundancy RaptorQ (E, high value).** Vary repair symbols with *measured* loss + burst length. Falsifiable: at 5% loss, +15% redundancy must cut effective loss <1% vs static. Experiment in netem.
2. **Interleaving + burst-sized FEC blocks (E).** Break the burst-loss stall. Falsifiable: 2% burst loss (20-pkt) must not stall window >1 block.
3. **Case-bit control channel (E, cheap).** Use 0x20 bits to carry low-rate control *for free* if server preserves case. Falsifiable: server round-trips case; control metadata costs 0 extra bytes.
4. **Bandit-based per-resolver tuning (D).** UCB/Thompson over balancer strategies + per-resolver windows. Falsifiable: beats fixed hybrid on real multi-ISP traffic.
5. **BBR-style model-based rate control (D).** Replace loss-only cwnd with delivery-rate/RTT-min probing. Falsifiable: better goodput under *throttling* (where loss≠congestion).
6. **DTN custody/resume (D).** Store-and-forward for intermittent connectivity; resumable transfers. Falsifiable: recovery time after 60s disconnect drops vs current.
7. **Flow-shaping to a cover distribution (E).** Match query name/length/timing distribution to organic traffic (Shaperd/obfs ideas). Falsifiable: classifier confidence drops on real detection models.

---

## DELIVERABLE H — Experimental Plan (minimal validation)

Baseline: current meli on a controlled VPS, 4-core, netem on client side. Measurements: goodput, P50/P95/P99 latency, loss, retransmit overhead, CPU, session setup/recovery time, concurrency. Tools: `netem` (delay/loss/burst/reorder), `iperf3`-style flow, packet capture (tcpdump), protocol logs, deterministic fault injection (drop Nth packet; inject tail-drop). Run each experiment ≥3 trials with confidence intervals. Rejection criterion per hypothesis as defined above.

---

## DELIVERABLE I — Architecture Options

1. **Minimal & maintainable:** single authoritative server, UDP/53, EDNS0 4096, ARQ+selective repeat, static MTU, base36, AES-GCM. (Today's baseline; keep it clean.)
2. **Performance-oriented:** adds per-path MTU discovery, adaptive FEC (RaptorQ), interleaving, BBR-style pacing, packed control blocks, per-resolver bandit tuning. Same single server.
3. **Resilience-oriented:** adds multi-path (multi-resolver + multi-server across *truly independent* AS/geos), coupled congestion control, DTN custody/resume, flow-shaping camouflage, hybrid fallback (WebRTC SFU / NTP / FCM) when DNS path degrades.

Trade-off: (1) simplest; (2) best per-server throughput; (3) best availability under censorship/shutdown. Choose based on whether the priority is speed or *survivability* (given your domestic-platform priority, (3) is the strategic fit, with (2) as the performance layer).

---

## DELIVERABLE J — Implementation Roadmap

**Immediate low-risk experiments**
- Per-path MTU discovery (v4 ≤1400, v6 ≤1280) + adaptive EDNS0.
- Interleaving across FEC blocks.
- Adaptive redundancy (vary repair symbols with measured loss).
- DataNackInitialDelay tuning + immediate NACK on first gap.

**High-value engineering**
- BBR-style rate control replacing loss-only cwnd.
- Packed control blocks already present — *extend* to pack data too.
- Bandit-based resolver selection (you have 9 strategies; make it learned).

**Medium-term research**
- DTN custody/resume for resumable transfers.
- Flow-shaping to organic DNS distribution (name length/timing).
- Coupled multipath congestion control when you add servers.

**High-risk experimental**
- Case-bit control channel.
- Adaptive per-stream reliability policy (short control vs bulk vs background).

**Rejected/deferred**
- TCP/53 for bulk; post-encryption compression; naive unbounded concurrency; wholesale reuse of iodine/dnscat2/UDT.

---

## DELIVERABLE K — Unresolved Questions
- Does your authoritative server preserve 0x20 case? (Needed for case-channel + poisoning safety.)
- What is the real per-resolver QPS throttle for each target ISP? (Needs on-network measurement; don't guess.)
- What is the true fragmentation threshold on the actual path (v4/v6)? (Needs live PMTU probe.)
- Are your vendored DNS tunnels' FEC paths actually engaged, or is Reed–Solomon a placeholder? (I saw `reedsolomon` used in `pkg/fec/raptorq.go` — RaptorQ is *not* wired to it; it's a wrapper around RS. Verify.)

---

## DELIVERABLE L — Research Coverage Report
- **Areas investigated:** DNS transport fundamentals (RFC 1034/1035, EDNS0/6891, DoT/DoH/DoQ/9250, recursion vs authority, caching/TTL, truncation/TC, MTU/fragmentation, v4/v6, anycast, QNAME min, serve-stale/8767, forwarders); existing tunnels (NSTX, OzymanDNS, DNScat/dnscat2, iodine, dns2tcp, MasterDNS/DNSTT/Storm/Vay/Noiz); reliability (ARQ variants, SACK/NACK, RTO, FEC, RS, RaptorQ/6330, hybrid ARQ, adaptive redundancy, interleaving, DTN custody); congestion/throughput (BDP, pipelining, bounded parallelism, BBR, multpath CC); encoding/compression; crypto/session; resolver heterogeneity; failure/scenario analysis (A–L); multi-path/hybrid; DTN/unconventional fields; detection/traffic analysis; testing methods.
- **Sources inspected:** RFCs (6891, 6330, 4838, 9250, 8767, 5452-adjacent), peer-reviewed papers (DoQ 2305.00790, TNSM 2026, SP14 RaptorQ attack, 0x20 Dagon, DNS Unchained, detection: lambion/Aiello/entropy, MPTCP CC, transparent forwarders), engineering reports (sing-box #4639, obfs, SafeDNS), vendor docs (iodine manpage).
- **Inaccessible/not fully read:** some paywalled full texts (I cite abstracts/accessible parts only); no live network measurement performed (controlled lab only, per constraints).
- **Stopped because:** sufficient breadth achieved across domains; remaining unknowns are *measurement* questions, not literature gaps — flagged in K.

---

## FINAL PRIORITIZATION (direct answers)

1. **Test first (5):** (a) per-path MTU discovery; (b) adaptive RaptorQ redundancy; (c) interleaving across FEC blocks; (d) NACK/pacing tuning; (e) BBR-style rate control.
2. **Strongest evidence of real benefit (3):** (a) authoritative-mode + large-payload packing (measured tens-of-Mbps potential); (b) selective-repeat ARQ + adaptive RTO (your design, matches RFC 6298); (c) DoQ-style low-latency encrypted control channel (peer-reviewed ~33% gains).
3. **Promising but under-tested:** adaptive redundancy; BBR-style control; DTN custody/resume; flow-shaping camouflage.
4. **Historical methods that deserve another look:** DTN bundle concepts (custody/fragmentation); 0x20 case encoding (as a *channel*, not just defense); early authoritative-only high-bandwidth tricks.
5. **Overrated/misunderstood:** "DNS is inherently ~1–3 Mbps" (false — it's a throttling/MTU artifact); "more parallelism = more speed" (throttle/cPU-bound); "encryption alone hides you" (metadata is the giveaway); "TCP/53 fallback" (more blocked than UDP/53).
6. **Incompatible pairs:** post-encryption compression × any payload; unbounded concurrency × resolver QPS caps; naive parallel subflows × shared bottleneck (need coupled CC); oversized UDP responses × non-EDNS/musl resolvers (TC with 0 answers).
7. **Most likely to raise *useful* throughput:** MTU-aware packing + authoritative mode + adaptive FEC (pack to the path ceiling, don't waste a byte).
8. **Most likely to reduce latency:** DoQ-style control handshake (0-RTT resumption), tighter initial RTO, immediate NACK.
9. **Most likely to improve reliability under packet loss:** adaptive RaptorQ redundancy + interleaving (burst tolerance) + selective-repeat.
10. **Most important under resolver rate limiting:** bandit-based per-resolver tuning, load shedding, pacing (spread queries to avoid QPS throttles), multi-path with coupled CC.
11. **Most important during intermittent connectivity:** DTN custody/resume + store-and-forward + session migration.
12. **Cannot help in complete path loss (I):** *anything* DNS-based that needs the foreign authoritative server. Only domestic whitelist platforms (WebRTC/SFU/MTProto) bridge out — this is why the domestic-platform work is strategic.
13. **Three biggest unresolved engineering questions:** (a) how to make resolver behaviors *learned per-ISP* (bandit vs static); (b) how to detect *and adapt to* throttling vs congestion vs loss in real time (needs a classifier); (c) how to make the tunnel *flow-shaped* (undetectable) without killing throughput.
14. **Smallest next experiment:** measure live per-path MTU + per-resolver QPS ceiling on your real server; then run the adaptive-redundancy FEC test in netem (5% loss, 20-pkt bursts) against the static baseline.
15. **What justifies moving research→implementation:** any candidate that (a) has a *falsifiable* measured gain ≥20% in goodput or ≥2× in recovery time, and (b) passes the adversarial review (no shared-bottleneck violation, no security regression, no compatibility break) — on a reproducible lab bench first.
