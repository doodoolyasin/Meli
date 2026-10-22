# گزارش فاز ۱ وندورینگ موتورهای تونل

محدوده: شالوده‌گذاری ۱۳ مخزن upstream زیر `android/`، فقط بازرسی کد (بدون build gradle/go)، بدون تغییر در فایل‌های tracked موجود.

## ۱. مخزن‌های وندورشده (shallow clone + حذف .git)

| Directory | Upstream | Size | License |
|---|---|---|---|
| `android/masterdns-upstream` | masterking32/MasterDnsVPN | 2.2 MB | MIT |
| `android/stormdns` | nullroute1970/StormDNS | 1.9 MB | MIT (fork MasterDNS) |
| `android/masterdns-qs` | blackestwhite/masterdns-qs-tunnel | 74 KB | **none** (no license) |
| `android/pingtunnel` | esrrhs/pingtunnel | 13.4 MB | MIT |
| `android/spoof-tunnel` | ParsaKSH/spoof-tunnel | 686 KB | Apache 2.0 |
| `android/balevpn` | kookoo1sabzy/BaleVPN | 3.6 MB | MIT |
| `android/qr-tunnel` | aminsaedi/qr-tunnel | 967 KB | MIT (README only) |
| `android/nooshdaroo` | RostamVPN/nooshdaroo | 535 KB | MIT |
| `android/qwdtt` | makriq-org/qwdtt | 4.9 MB | **GPL-3 — منع** |
| `android/golden-gre` | LivingG0D/Golden-GRE | 369 KB | Apache 2.0 |
| `android/csqtt` | amurcanov/csqtt | 4.2 MB | **PolyForm Noncommercial — غیرقابل** |
| `android/olcrtc` | openlibrecommunity/olcrtc | 2.5 MB | **WTFPL — خارج از allowlist** |
| `android/openflux` | p1neappleXpress/OpenFlux | 4.9 MB | **GPL-3 — منع** |

مجموع: ~40.2 MB. همه cloneها با `git clone --depth 1 --single-branch` انجام شد و nested `.git` حذف شد (تأیید `find android -name .git` → 0). هیچ clone شکست نخورد.

## ۲. قفل qThroneMobile — transport-sources.lock

- فایل قفل با `curl` از شاخه `dev` (default branch، نه `main`) گرفته شد: `ttrroyy/qThroneMobile@dev`.
- سه upstream استخراج شد: `openlibrecommunity/olcrtc.git`، `p1neappleXpress/OpenFlux.git`، `amurcanov/csqtt.git` — هر سه مخزن سورس واقعی بودند و شالوده شدند (`android/olcrtc`, `android/openflux`, `android/csqtt`).
- qWDTT در قفل نبود؛ از مخزن مستقیم `makriq-org/qwdtt` وندور شد.
- ارجاع‌های قفل (revision/sha256/bundle URLs) فقط برای qThrone خودش معتبرند؛ ما سورس upstream را مستقیماً گرفتیم.
- qThroneMobile خودش clone نشد (حجم زیاد، READ-ONLY ref طبق B).

## ۳. مسیرهای یکپارچه‌سازی (integration route)

- **gomobile-aar** (Go core، bindable): `masterdns-upstream`، `stormdns`، `masterdns-qs` (سد قانونی)، `qr-tunnel` (وابستگی LiveKit بررسی شود)، `olcrtc` (دارای `cmd/olcrtc-cgo` + `internal/mobile` با protector)، `openflux` (دارای بندینگ gomobile آمادهٔ upstream + `build_android.sh`)، `qwdtt` (سد GPL).
- **external-binary** (raw socket / Rust): `pingtunnel` (`icmp_listen_android.go` موجود؛ root-free بودن تأیید نشده)، `spoof-tunnel` (Go client + Rust XDP relay)، `balevpn`، `nooshdaroo`، `csqtt`.
- **server-side-only**: `golden-gre` (relay به زبان C: `relay/golden-gre-relay.c`، مونوسایفر vendored).

## ۴. ریسک‌ها و گلوگاه‌ها

- **لایسنس غیرمجاز:** `qwdtt` و `openflux` GPL-3، `csqtt` PolyForm Noncommercial (تجاری ممنوع)، `olcrtc` WTFPL خارج از allowlist؛ `masterdns-qs` اصلاً لایسنس ندارد → قبل از هر bind/AAR باید دفاع حقوقی یا کانال موازی باشد. جزئیات فیلد‌به‌فیلد در `vendors_assessment.jsonl`.
- **qr-tunnel:** MIT فقط در README؛ فایل LICENSE باید از upstream بگیرد.
- **pingtunnel:** بزرگ‌ترین درخت (13.4 MB) و حالت ICMP روی اندروید بدون root هنوز اثبات نشده — لازم probeِ `SDT ping socket`.
- **حذف ناسازگاری آینده:** upstream های بسته (csqtt/olcrtc/masterdns-qs) EoL یا Experimental (پرچم در README) — برای اعتمادپذیری به ساخت مستقیم از کد upstream سالم تکیه نخواهیم کرد.
- `.gitignore` دست نخورد؛ هیچ فایل tracked موجود تغییر نکرده؛ هیچ نام باینری خام به .gitignore اضافه نشد.

## ۵. خروجی‌ها

- `vendors_assessment.jsonl` — 13 خط، هر خط یک JSON معتبر با فیلدهای خواسته‌شده.
- `VENDORING_PHASE1_REPORT.md` — همین گزارش.
