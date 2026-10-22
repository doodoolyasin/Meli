# طراحی یکپارچه‌سازی بله (BALE_INTEGRATION_DESIGN)
<div dir="rtl">

> این سند طراحی است، نه کد تولیدی. ادعای کد-ریپو با مسیر فایل، ادعای upstream با URL ارجاع دارد؛ نامعلوم‌ها صریحاً «نامعلوم» علامت خورده‌اند.

---

## ۱. انتخاب پایه: کدام upstream ریسک پورت کمتری دارد؟

| معیار | aminsaedi/qr-tunnel (LiveKit DataChannel داخل تماس تصویری Bale) | kookoo1sabzy/BaleVPN (P2P روی Bale) |
|---|---|---|
| دسترسی به Bale | اتوماسیون مرورگر: Playwright/Chrome کنترل‌شده؛ `bale-integration/bridge.js` با `--role callee` یک کروم کنترل‌شده بلند می‌کند و getUserMedia را intercept می‌کند (https://github.com/aminsaedi/qr-tunnel/blob/main/bale-integration/bridge.js) | سیگنالینگ بومی: `bale-signaling-rust/bale-signaling/src/peer.rs` و `grpc_web.rs` — WS مستقیم به بک‌اند Bale با protoهای استخراج‌شده از باندل وب Bale (https://github.com/kookoo1sabzy/BaleVPN/tree/main/reverse_engineering) |
| حمل ترافیک | DataChannel داخل تماس تصویری که SFU بله پیام‌های آن را بین شرکت‌کنندگان رله می‌کند؛ از README آن پروژه: ~10KB/s روی DataChannel (https://github.com/aminsaedi/qr-tunnel#how-it-works) | webrtc-rs در `lktunnel-rust/lktunnel/src/rtc.rs` + QUIC `socks5_quic.rs` + NAT/TUN در `src/nat/`؛ استقرار «تماس صوتی بله بین دو حساب» (https://github.com/kookoo1sabzy/BaleVPN#readme) |
| قابلیت اندروید | عملاً ناممکن بی‌تغییر: مسیرها دسکتاپ/Playwright (`bale-integration/profile-a|b`, `serve-cli.js`) | آماده اندروید: `bale-vpn-android/androidApp` + JNI shim `bale-vpn-android/rust/lktunnel-jni` که `liblktunnel_jni.so` می‌سازد (https://github.com/kookoo1sabzy/BaleVPN/blob/main/docs/architecture.md) |
| آنچه مشابه‌برداری از آن به کد Go ما ارزان است | هدر 19 بایتی `transportFrame` با flags/seq/ack/CRC32 در `qr-tunnel/internal/transport/transport.go`؛ SOCKS5 سرور در `qr-tunnel/internal/socks5/server.go`؛ تفکیک لایه‌ها `internal/provider|transport|socks5` | طراحی traitی (`lk-signaling-rust/lk-signaling/src/lib.rs` جدا از پیاده‌سازی Bale) و لایه‌بندی manager/server/client |

**نتیجه:** پایه انتخابی **مسیر سیگنالینگ BaleVPN** است: تنها مسیری که روی اندروید بدون مرورگر کار می‌کند (پورت به Go: `pkg/bale/signaling` آینده). ریسک اصلی آن: بازمهندسی proto و احراز هویت حساب Bale — نحوه گرفتن/تجدید توکن در `bale-signaling-rust` با خواندن مستقیم کد تأیید نشده؛ «نامعلوم».
از qr-tunnel فقط **الگوی ساختار** (لایه‌بندی provider/transport/socks5 و فریم‌های seq/ack/CRC32) مشابه‌برداری می‌شود، نه اتصال Playwright.

هشدار امنیتی مستند هر دو: SFU بله به محتوای رمزنشده دسترسی دارد و QUIC self-signed در برابر MITM فعال مقاوم نیست (http://github.com/kookoo1sabzy/BaleVPN#privacy--encryption). پس روی DataChannel باید CryptoChannel موجود ما (`internal/security/codec.go` در meli-work) اضافه شود.

---

## ۲. وضعیت کد فعلی ما

ماژول `github.com/doodoolyasin/meli` (Go 1.26، go.mod). گزارش صادقانه:

- `pkg/webrtc/webrtc.go` — **سالم ولی ناقص:** کدک فریم `[4B len][4B connID][1B msgType][payload]` با `MsgConnect/MsgConnectOK/MsgConnectErr/MsgData/MsgClose/MsgKeepalive` و `ChannelMultiplexer` با `Register/Unregister/Dispatch/RegisterWaiter/KeepaliveTicker`. تست‌های `pkg/webrtc/webrtc_test.go` (کدک, طول خراب, dispatch+keepalive) تست واقعی هستند و فریم/دیسپاچ را می‌پوشانند. **هیچ پیاده‌سازی ICE/DTLS/SDP وجود ندارد؛** فرستنده کاملاً تزریق‌پذیر است (`peerSender func(*Frame) error`).
- `pkg/bale/bale.go` — **SOCKS5 واقعی:** `TunnelSession.StartSocksBridge` روی `127.0.0.1:<port>` با RFC 1928 کامل (فقط CONNECT؛ IPv4/IPv6/Domain؛ رفتار ردّ بی‌سرور با REP=0x05 تست شده) و `RelayBridge` سمت خروج با `handleConnect/SetDialer` و default dial 10s. أيضاً `CheckReachability` ساده (HTTP GET به `MeetBaseURL`).
- **نکته نامعلوم:** `BaleEndpoints` (meet.bale.ai، stun/turn.bale.ai) هنوز با استقرار واقعی Bale تطبیق داده نشده‌اند؛ هیچ سند بالادست همان نام‌ها را تأیید نمی‌کند (README qr-tunnel به SFU دیگری `meet-em.ble.ir` اشاره می‌کند). باید از ادغام واقعی بازچک شود.
- **اتصال انجام نشده:** `--bale` در `cmd/client/main.go:163` تعریف شده ولی فقط در banner خط 317 خوانده می‌شود — no pkg/bale call site. سند خود ریپو در README بخش «Implementation status (audited 2026-10-21)» می‌گوید کانال WebRTC/Bale «no server side at all» (https://github.com/doodoolyasin/meli#implementation-status).
- **وابستگی WebRTC گریز از go.mod:** هیچ pion/webrtc-rs/LiveKit در go.mod نیست (go.mod meli فقط TOML/compress/crypto/net دارد).
- Android: SOCKS5 زیرساخت کامل موجود — جریان «App -> TUN -> hev-socks5-tunnel -> Bridge» با الگوی TorSocksBridge (`android/app/src/main/java/app/slipnet/tunnel/TorSocksBridge.kt`) و Socks5ProxyInstance هستند؛ TunnelType در `ServerProfile.kt:185` و Room در `ProfileEntity.kt`.

---

## ۳. معمار سرور companion

"MasterDNS companion" یعنی یک باینری Go که بیرون از شبکه فیلترشده (روی همان VPS سرور MasterDnsVPN ما در `cmd/server`) اجرا می‌شود و به همان تماس بله می‌پیوندد و خروجی اینترنت می‌دهد:

- **نقش:** همان `RelayBridge` موجود (`pkg/bale/bale.go`) — به `MsgConnect` با dial آدرس هدف پاسخ می‌دهد، `MsgConnectOK/Err` می‌فرستد و دو-طرفه stream می‌کند. کمبود تنها یک چیز است: **اتصال واقعی WebRTC** که `AttachPeer(sender)` را با sender واقعی تغذیه کند.
- **باینری جدید:** `cmd/bale-companion` (پیشنهاد طراحی؛ موجود نیست) که `RelayBridge` را می‌چرخاند و یک signaling client جدید `pkg/bale/signaling` را به آن وصل می‌کند.
- **مسیر signaling (سه گزینه، انتخاب اول):**
  1. **حساب Bale سمت companion** (مدل BaleVPN): companion با حساب Bale خودش login می‌کند، تماس ورودی از حساب طرف اندروید را auto-answer می‌کند و peerconnection می‌سازد. الگوی اثبات‌شده در BaleVPN (https://github.com/kookoo1sabzy/BaleVPN#readme)؛ جزئیات توکن/endpoint در Rust آن است و باید خوانده شود — «نامعلوم» تا بررسی.
  2. **SDP relay روی تونل DNS موجود:** اندروید SDP offer/answer را از راه تونل DNS موجود (`cmd/server` + `cmd/client`) می‌فرستد؛ اما طرف دوم همچنان به توکن/WS بله نیاز دارد، پس این فقط حلقه اول را حل می‌کند — حلقه دوم به توکن بله نیاز دارد؛ «نامعلوم».
  3. qr-tunnel path (Playwright روی سرور companion): قابل قبول فقط برای سرور (نه موبایل!) ولی دو کروم و حساب‌های جدا می‌خواهد؛ به‌عنوان fallback نگه داشته می‌شود.
- **ترتیب بسته‌ها (packet ordering):** DataChannel SCTP به‌پیش‌فرض ordered+reliable است، پس ترتیب بافر حفظ می‌شود؛ ولی فریم فعلی ما (`pkg/webrtc/webrtc.go`) **seq ندارد** — هدر پیشنهادی: `[len][seq][connID][msgType]` با CRC32 به سبک `qr-tunnel/internal/transport/transport.go` (هدر 19 بایتی) تا loss/dup قابل تشخیص و در حالت lossy (maxRetransmits) مرتب شود. `DecodeFrame` فعلی (`pkg/webrtc/webrtc.go`) فریم‌های شکسته را از stream با `io.ReadFull` می‌خواند و برای حمل پیام-محور روی DataChannel باید حفره‌گذار بافر خودمان نوشته شود.
- **اتصال گوشی (SOCKS5 گوشی):** دو حالت:
  - حالت A (سمت اندروید مستقیماً شرکت‌کننده تماس): هسته Go با gomobile bind (الگوی `android/gomobile-build/tools.go`) → `TunnelSession.StartSocksBridge` پورت محلی باز می‌کند → `SlipNetVpnService` ترافیک TUN را با `HevSocks5Tunnel` به آن پورت می‌فرستد (همان جریان مستند «App -> TUN -> hev-socks5-tunnel -> Bridge» در `TorSocksBridge.kt`).
  - حالت B (offload به companion در VPS): اندروید با `Socks5ProxyInstance` به companion وصل می‌شود و companion هم join call می‌کند — ساده‌تر ولی کل پهنای‌باند از مسیر اندروید↔companion می‌گذرد؛ سهم بله فقط وقتی است که آن مسیر از شبکه بله/باند داخلی رد شود.

---

## ۴. فیلدهای پروفایل و health-check

**فیلدهای جدید فرم پروفایل Bale** — الگو از `ServerProfile.kt` (data class)، `ProfileEntity.kt` (Room) و `EditProfileScreen.kt`:

| فیلد | نوع/پیش‌فرض | توضیح |
|---|---|---|
| `tunnelType` | مقدار جدید `"bale"` در enum `TunnelType` (`ServerProfile.kt:185`) | مانند بقیه روش‌ها |
| `baleRoomCode` | `String = ""` | شناسه تماس/مخاطب (دستی) |
| `baleCompanionHost` | `String = ""` | آدرس companion روی VPS (حالت B و مسیر SDP-relay) |
| `baleCompanionPort` | `Int = 0` | پورت سیگنال/حمل companion؛ 0 = فقط از تونل DNS موجود |
| `baleStunServer` | `String = "stun.bale.ai:3478"` | از `BaleEndpoints` فعلی (`pkg/bale/bale.go`)؛ نامعلوم — باید از استقرار واقعی بازچک شود |
| `baleKeepaliveSec` | `Int = 20` | interval برای `KeepaliveTicker` |
| `baleProbeUrl` | `String = "https://meet.bale.ai"` | URL قابل override برای health-check |
| `baleSignalingMode` | enum `BALE_ACCOUNT` / `SDP_RELAY` | انتخاب از بخش ۳ |
| `tcpListenPort` / `tcpListenHost` | موجود | پورت SOCKS5 محلی (`SocksPort` در `TunnelSession`) |

**ترتیب probe (اثبات در دسترس بودن Bale Meet بدون ورود به تماس):**
1. Resolve نام میزبان از DoH (الگوی `DnsPoolScanner.kt` / `DnsDoHProxy.kt`).
2. HTTPS GET به `baleProbeUrl` با timeout ~5s — منطق `CheckReachability` موجود (`pkg/bale/bale.go`)؛ قید قبول: status در بازه 2xx-4xx.
3. UDP STUN Binding Request به `baleStunServer` (با یک client کوچک و انتظار Binding Response؛ بدون auth و بدون join تماس).
4. TCP reachability همان میزبان روی :3478 (fallback وقتی UDP بسته است).
5. هیچ‌کدام login یا join تماس لازم ندارد → «اثبات بدون اتصال» برقرار است.

**حالت‌های شکست:**
- resolve یا HTTP فِیل و STUN هم فِیل → شبکه بله در دسترس نیست («اینترنت داخلی بله از این شبکه مسدود است»)؛ یعنی روش از پایه دردسترس نیست.
- HTTP OK ولی STUN fail → UDP بسته/throttle شده؛ فال‌بک به STUN-over-TCP/TURN-over-TCP و هشدار کیفیت به کاربر.
- همه OK ولی join ناموفق → مشکل سشن/حساب است؛ نیازمند ورودی‌های دستی بخش ۵.
- health-check با RTT بالا (>1500ms) → اخطار کیفیت سرعت پایین DataChannel (~10KB/s طبق README qr-tunnel).

---

## ۵. برنامه تست loopback و ورودی‌های کاربر

الگو گرفتنی از `test/e2e/socks5_e2e_test.go`: تگ `//go:build e2e`، `TestMain` با `sync.Once`، عبور از `binaries()` که `./cmd/server` و `./cmd/client` را build می‌کند، پورت‌های loopback با `freePort()` و اثبات روی بایت‌هایی که واقعاً از فریم گذشتند. فایل جدید: `test/e2e/bale_loopback_e2e_test.go`.

**شش مرحله loopback:**
1. **Build:** باینری پیشنهادی جدید `meli-bale-companion` (از `cmd/bale-companion`، بخش ۳) به لیست `binaries()` اضافه شود. این باینری امروز وجود ندارد؛ تا ساخته نشود تست `t.Skip` می‌خورد.
2. **میزبان loopback:** یک echo server روی `127.0.0.1:<freePort()>` (الگوی دقیق `pkg/bale/bale_test.go` از خط 104 به بعد).
3. **DataChannel شبیه‌سازی‌شده:** دو `TunnelSession` (کلاینت و companion) داخل process تست با یک pipe دوطرفه به هم وصل می‌شوند (با `AttachPeer` برای sender و `ReceiveFrame` برای receiver؛ الگوی دقیق `bale_test.go:139-145`)؛ بدون شبکه واقعی.
4. **گذر از SOCKS5:** پورت `StartSocksBridge` با `net.Dial` به `127.0.0.1:<port>` و یک SOCKS5 CONNECT handshake دست‌ساز (الگوی `bale_test.go:33-49`؛ greeting متد 0x00، CONNECT به echo)؛ assert پاسخ `0x05 0x00` و بازگشت echo payload.
5. **چندکاناله:** با ۴ connID موازی و payload از 1KB تا 16KB (مطابق بافر 16384 در `bale.go`)، ثبات framing و `ActiveCount` نهایی صفر.
6. **بررسی ترتیب/keepalive:** ارسال `N=100` فریم MsgData با شماره seq فرضی در payload و اثبات ترتیب خروجی؛ یک keepalive از `KeepaliveTicker` با interval کوتاه و اثبات رسیدن به sender.

**فقط کاربر می‌تواند بدهد (شبیه‌سازی‌ناپذیر در loopback):**
- یک حساب Bale فعال و رمز اجازه استفاده آن (برای مسیر `BALE_ACCOUNT`).
- لینک/کد تماس واقعی Bale Meet و شناسه حساب مخاطب companion.
- نام دامنه/میزبان SFU واقعی (README qr-tunnel به `meet-em.ble.ir` اشاره می‌کند؛ تعیین مقصد واقعی نیاز به test واقعی دارد).
- توکن/کوکی سشن وب Bale و refresh آن.
- اجرای یک تماس واقعی برای سنجه throughput/میزان ازدست‌رفتن UDP از داخل ایران.
- تصمیم نهایی معماری (حالت A اندروید-محور یا حالت B companion-محور).

</div>



