# ⚡ Meli DNS Tunneling Suite & Multi-Engine Android Platform

<p align="center">
  <img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" height="128" alt="Meli Logo" />
</p>

<p align="center">
  <b>A unified, censorship-resistant DNS tunneling suite engineered for extreme network degradation, wholesale UDP throttling, and Intranet Whitelist bypass.</b>
</p>

<p align="center">
  <a href="https://github.com/doodoolyasin/Meli/releases/tag/v1.0.0"><img src="https://img.shields.io/badge/Release-v1.0.0-blue.svg" alt="Release" /></a>
  <a href="https://github.com/doodoolyasin/Meli/actions"><img src="https://img.shields.io/badge/CI%2FCD-Passing-brightgreen.svg" alt="Build Status" /></a>
  <a href="https://github.com/doodoolyasin/Meli/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v3-green.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/Go-1.24-00ADD8.svg" alt="Go Version" />
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20Linux%20%7C%20Windows%20%7C%20macOS-orange.svg" alt="Platforms" />
</p>

---

## 🏗️ Architecture & Fork Consolidation

Meli unifies the best components of the modern DNS anti-censorship ecosystem into a single, high-performance monorepo:

```
github.com/doodoolyasin/Meli (Unified Monorepo)
├── cmd/
│   ├── server/           # High-throughput Linux server (Multi-core UDP, SOCKS5 egress, ARQ)
│   └── client/           # Cross-platform client CLI (Auto-hunt, Pacer, Desync, FEC)
├── internal/             # Production Core Engine (MasterDnsVPN Engine)
│   ├── arq/              # Sliding-window selective ACK ARQ (70%+ packet-loss resilience)
│   ├── basecodec/        # Compact FQDN codecs (LowerBase32, LowerBase36, RawBase64)
│   ├── compression/      # Multi-engine payload compression (ZSTD, LZ4, ZLIB)
│   ├── security/         # Cryptographic layers (AES-GCM, ChaCha20-Poly1305, Dynamic Key Derivation)
│   ├── udpserver/        # Multi-worker lock-free UDP server engine
│   └── client/           # Parallel DNS query balancer, MTU discoverer & racer
├── pkg/                  # Meli Advanced DPI Evasion & Reconnaissance
│   ├── hunter/           # Embedded database of 7,854 Iranian ISP/Datacenter DNS nodes + Real A-Query Hunter
│   ├── pacer/            # Microsecond Leaky-Bucket rate controller (anti-bufferbloat for MCI/Irancell)
│   ├── desync/           # DPI camouflage: RFC 6891 EDNS0 OPT (Option 65001) & RFC 9000 QUIC DCID Steganography
│   └── raptorq/          # Systematic Reed-Solomon / Fountain Erasure Coding for packet recovery
└── android/              # Complete Multi-Engine Android Application (SlipNet Jetpack Compose UI)
    ├── app/src/main/     # Android VpnService, HevSocks5Tunnel, compose UI screens
    └── res/raw/          # Embedded 7,854 Iranian resolvers (iran_resolvers.txt) scanned at Tier 0
```

---

## 🎨 Distinctive Visual Highlighting (SlipNet UI Integration)

To distinguish the new **Meli** engine and features inside the SlipNet Android application, all Meli-specific components are visually rendered with **Glowing Electric Cyan (`#00E5FF`)** and custom badges:

1. **⚡ Meli Tunnel in Add Profile Menu:**  
   Highlighted with a custom cyan border, dark-cyan background, and instant 1-click configuration for MasterDNS + FEC.
2. **`[⚡ MELI PRO]` Badge in Profile View:**  
   Displays an electric-cyan badge on profiles utilizing advanced Meli anti-DPI algorithms.
3. **🇮🇷 7,854 Iranian Priority Resolvers in DNS Scanner:**  
   The scanner auto-prioritizes domestic Iranian resolvers (Irancell, MCI, Shatel, Mokhaberat) under a dedicated `🇮🇷 7,854 IRAN NODES` cyan header, ensuring your tunnel bypasses international gateway filtering.

---

## 🚀 Quick Start Guide

### 1. Download Binaries & Android APK
Pre-built binaries and release APKs are automatically compiled and published via GitHub Actions on every commit:
* 📥 **[Download Latest Releases (v1.0.0)](https://github.com/doodoolyasin/Meli/releases/tag/v1.0.0)**
  * Android: `SlipNet-v2.5.5-full-release-arm64-v8a.apk` (or universal edition)
  * Linux Server: `meli-server-linux-amd64`, `meli-server-linux-arm64`
  * Client CLI: `meli-client-linux-amd64`, `meli-client-windows-amd64.exe`, `meli-client-darwin-arm64`

---

### 2. Running the Server (Linux / VPS)
On an outside/foreign VPS with an NS domain record pointed to your server IP:

```bash
# 1. Download and extract server binary
chmod +x meli-server-linux-amd64

# 2. Generate a secure random encryption key
./meli-server-linux-amd64 -genkey

# 3. Start server with your NS domain and SOCKS5 proxy
./meli-server-linux-amd64 \
    -config server_config.toml.example \
    -d "t.yourdomain.com" \
    -k "YOUR_GENERATED_KEY" \
    -edns0
```

---

### 3. Running the Client (CLI Mode)
To connect from inside a filtered network (e.g. Iran, China):

```bash
# Auto-hunt the fastest domestic Iranian resolvers and connect:
./meli-client-linux-amd64 \
    -config client_config.toml.example \
    -d "t.yourdomain.com" \
    -k "YOUR_GENERATED_KEY" \
    -hunt-iran \
    -pacer 128 \
    -edns0
```

#### CLI Flags:
* `-hunt-iran` / `-hunt`: Concurrently probes 7,854 embedded Iranian DNS resolvers and dynamically selects the top 10 lowest-latency nodes.
* `-pacer <KB/s>`: Microsecond leaky-bucket rate limiter that prevents cellular carrier packet drops due to bufferbloat.
* `-edns0`: Wraps covert payloads into RFC 6891 EDNS0 Extension records (Option 65001).
* `-fec <shards>`: Forward Error Correction repair redundancy using Reed-Solomon systematic coding.

---

### 4. Running on Android
1. Install `SlipNet-v2.5.5-full-release-arm64-v8a.apk` on your Android device.
2. Tap the `+` button and choose **`⚡ Meli Tunnel`** (highlighted in Electric Cyan).
3. Set your tunnel domain (e.g. `t.yourdomain.com`) and encryption key.
4. Go to **DNS Scanner**, where `🇮🇷 7,854 IRAN NODES` are tested at top priority.
5. Tap **Connect**!

---

## 🌪️ Emergency Circumvention Architecture (National Intranet & BGP Shutdown Mode)

Meli incorporates advanced, battle-tested circumvention techniques researched from severe censorship battlegrounds across **Iran (NIN / SHOMA)**, **Russia (TSPU / RKN)**, **China (GFW)**, and **Cuba / Turkmenistan (Whitelist-Only)**:

| Layer | Technique | Mechanism | Threat Defeated |
|---|---|---|---|
| **L3 Transport** | **Multi-Resolver Racing (`-race`)** | Dispatches concurrent DNS queries across up to 3 distinct Autonomous Systems (TIC, MCI, Irancell) and accepts the fastest valid reply. | Survives non-homogeneous BGP shutdowns and temporary ISP peering cuts. |
| **L7 DNS** | **0x20 Entropy Camouflage (`-camo`)** | Inverts letter cases randomly (RFC 1035) and prepends realistic CDN subdomains (`edge-cache-[hash]...`). | Blinds DPI ML classifiers trained on flat Base32 lowercase patterns. |
| **L7 DNS** | **Query Type Interleaving (`-interleave`)** | Interleaves query types according to natural browser distributions (~55% A, ~20% AAAA, ~15% TXT, ~5% CNAME). | Evades DPI statistical anomaly detectors looking for continuous TXT streams. |
| **L3/L4 Stego** | **ICMP Echo Covert Tunnel (`-icmp`)** | Encapsulates AES-CTR encrypted sequence-numbered frames inside standard ICMP Type 8 (Request) and Type 0 (Reply) ping packets. | Last-resort fallback when UDP port 53 is zero-rated or completely dropped. |
| **L4 UDP** | **Amnezia-Style Packet Jitter (`-padding`)** | Appends between 16 and 64 bytes of random junk padding (`Jmin`..`Jmax`) to disorganize packet length histograms. | Defeats statistical packet-length fingerprinting. |
| **Application** | **WebRTC SFU Media Multiplexing (`-webrtc`)** | Encapsulates multiplexed TCP/UDP streams inside WebRTC SCTP DataChannels or VP8 video frames over whitelisted national meeting infrastructure. | Survives strict domain/IP whitelisting by masquerading as domestic video conferencing. |
| **TLS** | **Domestic Whitelist SNI Camouflage** | Spoofs TLS ClientHello SNI with domestic whitelisted domains (`bale.ir`, `arvancloud.ir`, `snapp.ir`). | Passes middlebox SNI whitelist checks. |

---

## 🛡️ License
Licensed under the GNU General Public License v3.0 (GPL-3.0).
