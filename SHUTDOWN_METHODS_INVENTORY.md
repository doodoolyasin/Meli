# Shutdown Methods Inventory (2026-10-22)

GitHub-sourced tunnel methods to be consolidated into this app (one app, "New Profile" per method,
each profile = input form + "open?" health-check + execute). Source of truth for phase 1.

## A. Already in the app — expose/brand as profiles (no new vendoring)
| # | Engine | Where vendored | Android tunnel type |
|---|--------|----------------|---------------------|
| 1 | MasterDNS (was "Meli") | Go core `cmd/client`, `cmd/server` + `internal/arq` | (new TunnelType; user-facing name MasterDNS) |
| 2 | SlipStream / SlipStream-Plus | `android/app/src/main/rust/slipstream-rust{,-plus}` | SLIPSTREAM / SLIPSTREAM_SSH |
| 3 | DNSTT | `android/dnstt` | DNSTT / DNSTT_SSH |
| 4 | NoizDNS | `android/noizdns` | NOIZDNS ("sayedns" value) |
| 5 | VayDNS | `android/vaydns` + `android/vaydns-mobile` | VAYDNS / VAYDNS_SSH |

## B. Vendoring candidates (upstream → integration route)
| # | Repo | License (check) | Integration route |
|---|------|-----------------|-------------------|
| 1 | masterking32/MasterDnsVPN (7125★) | check | Go core reference for MasterDNS tunnel type + server cmd |
| 2 | nullroute1970/StormDNS | MIT | gomobile AAR client + server cmd (arq/multi-resolver, v2 tunnel) |
| 3 | blackestwhite/masterdns-qs-tunnel | check | Go client engine (fast uplink, spoofed UDP downlink) |
| 4 | esrrhs/pingtunnel | check | client runs as local proxy; Android needs root-free ICMP mode check |
| 5 | ParsaKSH/spoof-tunnel | check | raw-socket tunnel; Android variant requires wrapper — verdict first |
| 6 | kookoo1sabzy/BaleVPN (406★) | check | Bale P2P tunnel — architecture ref |
| 7 | aminsaedi/qr-tunnel | check | LiveKit DataChannel over Bale video call — Bale owner of design |
| 8 | RostamVPN/nooshdaroo | check | tiny SOCKS5-over-DNS client (binder target) |
| 9 | makriq-org/qwdtt | check | qWDTT Android client + wdtt-server |
| 10 | LivingG0D/Golden-GRE | check | GRE transport (server-side/relay) |
| 11 | ttrroyy/qThrone + qThroneMobile (1000+ commits) | check | READ-ONLY reference; `transport-sources.lock.json` reveals CSQTT/olcRTC/OpenFlux upstreams |

## Reference (no vendoring)
- WhiteDNS/WhiteDNS-Android (1409★) — profile-import UX (stormdns:// links), DNSt resolver lists
- SamNet-dev/findns + net2share/dnst-scanner — resolver scanner base for health-check
- Free-Guy-IR/cloudflareAuto_change_ip — auto DNS switch help
- ehsanking/iran-connectivity — DC/CDN reachability maps for health-check
- net4people/bbs #561/#586 — field reports: dnstt very survivable; plain ICMP tunnels get
  detected+blocked quickly; censor throttles by volume (VLESS/DNS/ICMP die ~10 min under video) →
  health-check must test under load, pacing/padding required.

## Core app additions (not profiles)
- Unified health-check engine ("باز هست؟") per profile type.
- Multi-resolver racing reuse (round-robin already exists).
- Captive-portal 204 local server (fix Android "!" route drop).
- `.gitignore` trap: anchor binaries as `/client`, `/server` (do NOT add bare names).
