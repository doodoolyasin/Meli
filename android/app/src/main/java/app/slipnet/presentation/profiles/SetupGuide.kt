package app.slipnet.presentation.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.slipnet.domain.model.TunnelType

/**
 * Exact, method-specific setup steps rendered inside the profile screen.
 * Bilingual (EN + FA) so an operator can follow it on the device without a
 * separate doc. Steps reference the real field labels used in this screen
 * ("Profile Name", "Domain", "Scan for Working Resolvers", "Public Key", …).
 */
internal data class GuideStep(val en: String, val fa: String)

internal fun setupSteps(tunnelType: TunnelType): List<GuideStep> {
    val name = tunnelType
    val isSshBased = name.name.endsWith("_SSH") || name == TunnelType.SSH
    return when (name) {
        TunnelType.DNSTT, TunnelType.DNSTT_SSH,
        TunnelType.NOIZDNS, TunnelType.NOIZDNS_SSH,
        TunnelType.VAYDNS, TunnelType.VAYDNS_SSH -> {
            // DNS-tunnel family: DNSTT / NoizDNS / VayDNS
            val keyLabel = when {
                name == TunnelType.NOIZDNS || name == TunnelType.NOIZDNS_SSH -> "NoizDNS"
                name == TunnelType.VAYDNS || name == TunnelType.VAYDNS_SSH -> "VayDNS"
                else -> "DNSTT"
            }
            buildList {
                add(GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."))
                add(GuideStep("2. Profile Name: any label you recognize, e.g. \"My Server\".", "۲. Profile Name: هر اسمی که تشخیص می‌دهی، مثل «سرور من»."))
                add(GuideStep(
                    "3. Domain: the tunnel hostname of your server, e.g. t.example.com. This is the domain whose NS records point to your tunnel server.",
                    "۳. Domain: نام هاست تونل سرور، مثل t.example.com. دامنه‌ای که رکورد NS آن به سرور تونل اشاره می‌کند."
                ))
                add(GuideStep(
                    "4. DNS Resolver: tap \"Scan for Working Resolvers\" and wait for the scan to finish, or type one as host:port (e.g. 8.8.8.8:53).",
                    "۴. DNS Resolver: دکمه «Scan for Working Resolvers» را بزن و صبر کن اسکن تمام شود، یا یکی را به شکل host:port بنویس (مثل 8.8.8.8:53)."
                ))
                add(GuideStep(
                    "5. Public Key: paste the $keyLabel server key exactly as printed in the server config — it is the tunnel's secret, never share it.",
                    "۵. Public Key: کلید سرور $keyLabel را دقیقاً همان‌طور که در تنظیمات سرور چاپ شده وارد کن — این کلید راز تونل است، هرگز آن را منتشر نکن."
                ))
                if (isSshBased) add(GuideStep(
                    "6. SSH: enter the SSH Username plus either the Password or the private key of your SSH server.",
                    "۶. SSH: نام کاربری SSH و رمز یا کلید خصوصی سرور SSH را وارد کن."
                ))
                add(GuideStep(
                    "7. Save, go back to the list, tap the profile — first connect shows the VPN dialog; press OK.",
                    "۷. ذخیره کن، به لیست برگرد، روی پروفایل بزن — بار اول دیالوگ VPN می‌آید؛ OK را بزن."
                ))
            }
        }
        TunnelType.SLIPSTREAM, TunnelType.SLIPSTREAM_SSH -> listOf(
            GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."),
            GuideStep("2. Profile Name: any label you recognize.", "۲. Profile Name: هر اسمی که تشخیص می‌دهی."),
            GuideStep(
                "3. Domain: the Slipstream tunnel hostname, e.g. t.example.com.",
                "۳. Domain: نام هاست تونل Slipstream، مثل t.example.com."
            ),
            GuideStep(
                "4. Public Key: paste the Slipstream server key from the server config.",
                "۴. Public Key: کلید سرور Slipstream را از تنظیمات سرور وارد کن."
            ),
            GuideStep(
                "5. DNS Resolver: tap \"Scan for Working Resolvers\" or type host:port.",
                "۵. DNS Resolver: دکمه «Scan for Working Resolvers» را بزن یا host:port بنویس."
            ),
            GuideStep(
                "6. Save, go back to the list, tap the profile to connect.",
                "۶. ذخیره کن، به لیست برگرد، روی پروفایل بزن تا وصل شود."
            )
        )
        TunnelType.SSH -> listOf(
            GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."),
            GuideStep(
                "2. Domain: IP or hostname of the SSH server (e.g. ssh.example.com).",
                "۲. Domain: آی‌پی یا نام سرور SSH (مثل ssh.example.com)."
            ),
            GuideStep(
                "3. SSH Port: usually 22 — enter the real port of your server.",
                "۳. SSH Port: معمولاً ۲۲ — پورت واقعی سرورت را وارد کن."
            ),
            GuideStep(
                "4. SSH Username + Password (or private key) exactly as on your server.",
                "۴. نام کاربری SSH و رمز (یا کلید خصوصی) دقیقاً مثل سرورت."
            ),
            GuideStep(
                "5. Save, go back to the list, tap the profile — confirm the VPN dialog once.",
                "۵. ذخیره کن، به لیست برگرد، روی پروفایل بزن — دیالوگ VPN یک بار تأیید می‌شود."
            )
        )
        TunnelType.DOH -> listOf(
            GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."),
            GuideStep(
                "2. DoH URL: the DNS-over-HTTPS endpoint, e.g. https://dns.google/dns-query.",
                "۲. DoH URL: آدرس DNS-over-HTTPS، مثل https://dns.google/dns-query."
            ),
            GuideStep(
                "3. Save and connect from the profile list.",
                "۳. ذخیره کن و از لیست پروفایل‌ها وصل شو."
            )
        )
        TunnelType.SNOWFLAKE -> listOf(
            GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."),
            GuideStep(
                "2. First connect needs 1–2 minutes to bootstrap Tor — wait until it says Connected.",
                "۲. اتصال اول ۱ تا ۲ دقیقه طول می‌کشد تا Tor راه بیفتد — صبر کن تا Connected شود."
            ),
            GuideStep(
                "3. Optional: paste Tor bridges (from @GetBridgesBot or bridges.torproject.org) to reach it in heavy filtering.",
                "۳. اختیاری: پل‌های Tor را (از @GetBridgesBot یا bridges.torproject.org) وارد کن تا در فیلترینگ شدید هم وصل شود."
            )
        )
        TunnelType.NAIVE, TunnelType.NAIVE_SSH -> listOf(
            GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."),
            GuideStep(
                "2. Server: the Caddy/proxy host (e.g. proxy.example.com) and Port (usually 443).",
                "۲. Server: هاست پروکسی Caddy (مثل proxy.example.com) و Port (معمولاً ۴۴۳)."
            ),
            GuideStep(
                "3. Proxy Username + Password exactly as configured on the server.",
                "۳. نام کاربری و رمز پروکسی دقیقاً مثل تنظیمات سرور."
            ),
            if (isSshBased) GuideStep(
                "4. SSH: enter SSH Username and Password for the SSH hop.",
                "۴. SSH: نام کاربری و رمز SSH را برای مرحله SSH وارد کن."
            ) else GuideStep(
                "4. Save, go back to the list, tap the profile to connect.",
                "۴. ذخیره کن، به لیست برگرد، روی پروفایل بزن تا وصل شود."
            ),
            GuideStep(
                "5. Save, go back to the list, tap the profile — confirm the VPN dialog once.",
                "۵. ذخیره کن، به لیست برگرد، روی پروفایل بزن — دیالوگ VPN یک بار تأیید می‌شود."
            )
        )
        TunnelType.SOCKS5 -> listOf(
            GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."),
            GuideStep(
                "2. SOCKS5 Server: hostname or IP of the proxy, plus Port (e.g. 1080).",
                "۲. SOCKS5 Server: نام هاست یا آی‌پی پروکسی، و Port (مثل ۱۰۸۰)."
            ),
            GuideStep(
                "3. Username / Password only if the proxy requires credentials.",
                "۳. Username و Password فقط اگر پروکسی احراز هویت بخواهد."
            ),
            GuideStep(
                "4. Save, go back to the list, tap the profile to connect.",
                "۴. ذخیره کن، به لیست برگرد، روی پروفایل بزن تا وصل شود."
            )
        )
        TunnelType.VLESS -> listOf(
            GuideStep("1. Grant all permissions above, then continue.", "۱. همه مجوزهای بالا را بده، بعد ادامه بده."),
            GuideStep(
                "2. You can paste a full vless:// link directly, or fill host, port, UUID and transport yourself.",
                "۲. می‌توانی لینک کامل vless:// را مستقیم وارد کنی، یا هاست، پورت، UUID و ترنسپورت را خودت پر کنی."
            ),
            GuideStep(
                "3. Save, go back to the list, tap the profile to connect.",
                "۳. ذخیره کن، به لیست برگرد، روی پروفایل بزن تا وصل شود."
            )
        )
        TunnelType.BALE -> listOf(
            GuideStep("1. Grant all VPN and battery permissions above.", "۱. تمام مجوزهای VPN و باتری در بالا را صادر کنید."),
            GuideStep(
                "2. In Bale messenger: start a new Video/Voice Call or Meeting, copy the meeting link or call code.",
                "۲. در پیام‌رسان بله: یک تماس یا جلسه جدید ایجاد کنید و لینک جلسه یا شناسه تماس را کپی کنید."
            ),
            GuideStep(
                "3. Domain / Meeting: paste the meeting link (or room code) into the Domain field.",
                "۳. دامنه / شناسه جلسه: لینک جلسه یا کد اتاق بله را در فیلد Domain وارد کنید."
            ),
            GuideStep(
                "4. Server / Companion: enter your VPS Companion IP and Port (where companion server runs).",
                "۴. سرور Companion: آی‌پی و پورت سرور واسط خود را (که سرور همراه روی آن فعال است) وارد کنید."
            ),
            GuideStep(
                "5. Common mistakes: DO NOT use the same Bale account on both phone and server (call will be dropped). Mute camera and mic to allocate all bandwidth to data (~10-50 KB/s).",
                "۵. اشتباهات رایج: هرگز از یک حساب کاربری بله یکسان در هر دو طرف استفاده نکنید (تماس قطع می‌شود). دوربین و میکروفون را ببندید تا تمام پهنای‌باند به تبادل داده اختصاص یابد."
            ),
            GuideStep(
                "6. Tap 'Health Check' (باز هست یا نه) below to verify Bale endpoints are accessible before connecting.",
                "۶. دکمه 'بررسی سلامت' (باز هست یا نه) را بزنید تا پیش از اتصال از باز بودن شبکه بله مطمئن شوید."
            )
        )
        TunnelType.STORMDNS, TunnelType.MASTERDNS -> listOf(
            GuideStep("1. Grant all permissions above.", "۱. تمامی مجوزهای بالا را صادر کنید."),
            GuideStep("2. Domain: Enter your delegated tunnel domain (e.g. t.example.com).", "۲. دامنه: دامنه تفویض‌شده به سرور را وارد کنید."),
            GuideStep("3. Resolvers: Tap 'Scan for Working Resolvers' or enter a working UDP:53 resolver.", "۳. دی‌ان‌اس: دکمه اسکن را بزنید یا یک ريزالور فعال UDP:53 وارد کنید."),
            GuideStep("4. Encryption: Select encryption method (ChaCha20 / AES-GCM / XOR) matching your server.", "۴. رمزنگاری: متد رمزنگاری مطابق با سرور را انتخاب کنید.")
        )
        TunnelType.PINGTUNNEL -> listOf(
            GuideStep("1. Note: Root permission is required for ICMP raw sockets on Android.", "۱. نکته: دسترسی روت برای استفاده از سوکت‌های خام ICMP در اندروید الزامی است."),
            GuideStep("2. Server: Enter your VPS server IP in the Domain field.", "۲. سرور: آی‌پی سرور VPS را در فیلد دامنه وارد کنید."),
            GuideStep("3. Key: Enter the PingTunnel secret key matching server.", "۳. کلید: کلید امنیتی پینگ‌تانل مطابق سرور را وارد کنید."),
            GuideStep("4. Tap 'Health Check' to ensure ICMP Echo is not dropped by ISP.", "۴. دکمه بررسی سلامت را بزنید تا مطمئن شوید پکت‌های ICMP توسط اپراتور فیلتر نشده‌اند.")
        )
        TunnelType.NOOSHDAROO -> listOf(
            GuideStep("1. Grant all permissions above.", "۱. تمامی مجوزهای بالا را صادر کنید."),
            GuideStep("2. Domain: Enter your Nooshdaroo delegated DNS domain (e.g. n.example.com).", "۲. دامنه: دامنه دی‌ان‌اس تفویض‌شده نوشدارو را وارد کنید."),
            GuideStep("3. Resolvers: Tap 'Scan for Working Resolvers' for active resolvers.", "۳. ريزالور: دکمه اسکن را برای یافتن ريزالورهای فعال بزنید."),
            GuideStep("4. Pitfall: Avoid resolvers with deep packet inspection on label sizes.", "۴. اشتباه رایج: از ريزالورهایی که اندازه لیبل‌های DNS را فیلتر می‌کنند استفاده نکنید.")
        )
        TunnelType.SPOOF_TUNNEL -> listOf(
            GuideStep("1. Server: Enter the remote Spoof-Tunnel server IP and port.", "۱. سرور: آی‌پی و پورت سرور Spoof-Tunnel را وارد کنید."),
            GuideStep("2. Method: Select TTL spoofing / TCP split strategy.", "۲. متد: استراتژی جعل TTL یا برش پکت‌های TCP را انتخاب کنید."),
            GuideStep("3. Tap 'Health Check' to verify direct TCP handshake.", "۳. دکمه بررسی سلامت را برای سنجش هندشیک مستقیم TCP بزنید."),
            GuideStep("4. Pitfall: Spoofing requires adjusting TTL according to your carrier hops.", "۴. اشتباه رایج: مقدار TTL جعل‌شده باید متناسب با تعداد هاپ‌های شبکه اپراتور شما تنظیم شود.")
        )
        TunnelType.GOLDEN_GRE -> listOf(
            GuideStep("1. Server: Enter the Golden-GRE bridge server endpoint.", "۱. سرور: آدرس سرور واسط Golden-GRE را وارد کنید."),
            GuideStep("2. Tunnel ID: Enter the GRE key / tunnel index assigned by server.", "۲. شناسه تونل: شناسه GRE یا اندیس تونل اختصاص داده شده را وارد کنید."),
            GuideStep("3. Tap 'Health Check' to test encapsulation reachability.", "۳. دکمه بررسی سلامت را جهت اطمینان از مسدود نبودن GRE بزنید.")
        )
        TunnelType.QWDTT -> listOf(
            GuideStep("1. Domain: Enter your qWDTT tunnel domain (e.g. q.example.com).", "۱. دامنه: دامنه تونل qWDTT را وارد کنید."),
            GuideStep("2. Resolvers: Scan and pick low-latency UDP:53 resolvers.", "۲. دی‌ان‌اس: ريزالورهای کم‌تاخیر UDP:53 را انتخاب کنید."),
            GuideStep("3. Feature: Uses QUIC framing over DNS for reliable ordered delivery.", "۳. ویژگی: از فریمینگ QUIC بر بستر DNS برای ارسال بسته‌ها بدون ریزش استفاده می‌کند.")
        )
        TunnelType.PAQET -> listOf(
            GuideStep("1. Server: Enter Paqet server IP:Port (e.g. 1.2.3.4:9999).", "۱. سرور: آی‌پی و پورت سرور Paqet را وارد کنید (مثل 1.2.3.4:9999)."),
            GuideStep("2. Key: Enter the KCP encryption secret key matching your server.", "۲. کلید: کلید رمزنگاری KCP مطابق سرور را وارد کنید."),
            GuideStep("3. Note: Root permission or Termux root is required on Android for raw socket capture.", "۳. نکته: دسترسی روت برای کپچر سوکت خام در اندروید الزامی است."),
            GuideStep("4. Pitfall: You MUST set iptables NOTRACK and RST DROP rules on your server; otherwise the Linux kernel OS will send TCP RST packets that kill raw sessions.", "۴. اشتباه رایج: حتماً باید رول‌های iptables NOTRACK و RST DROP را روی سرور اعمال کنید؛ در غیر این صورت کرنل لینوکس پکت‌های ریست ارسال کرده و سشن را قطع می‌کند.")
        )
        else -> listOf(
            GuideStep(
                "1. Grant the permissions above, fill the fields for this method, then save and tap the profile to connect.",
                "۱. مجوزهای بالا را بده، فیلدهای این روش را پر کن، بعد ذخیره کن و روی پروفایل بزن تا وصل شود."
            )
        )
    }
}

@Composable
internal fun SetupGuide(tunnelType: TunnelType, modifier: Modifier = Modifier) {
    val steps = setupSteps(tunnelType)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Setup steps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "مراحل راه‌اندازی (فارسی)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        steps.forEach { step ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.en,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = step.fa,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "If connect fails: run the resolver scan again, check the key exactly matches the server, and confirm battery + VPN permissions are all granted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "اگر وصل نشد: اسکن resolver را دوباره اجرا کن، مطمئن شو کلید دقیقاً مثل سرور است، و هر سه مجوز (VPN، اعلان، باتری) را داده باشی.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
