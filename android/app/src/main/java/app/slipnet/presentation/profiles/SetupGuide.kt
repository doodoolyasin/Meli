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
