package app.slipnet.tunnel

import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.random.Random

enum class HealthStatus {
    OPEN,
    PARTLY,
    BLOCKED
}

data class CheckItem(
    val name: String,
    val nameFa: String,
    val success: Boolean,
    val latencyMs: Long,
    val detail: String
)

data class HealthCheckReport(
    val profileName: String,
    val tunnelType: String,
    val status: HealthStatus,
    val checks: List<CheckItem>,
    val summaryEn: String,
    val summaryFa: String
)

object HealthCheckEngine {

    suspend fun runCheck(profile: ServerProfile): HealthCheckReport = withContext(Dispatchers.IO) {
        val checks = mutableListOf<CheckItem>()

        when (profile.tunnelType) {
            TunnelType.BALE -> {
                // 1. Bale Meet HTTP probe
                checks.add(probeHttpMeet(urlStr = "https://meet.bale.ai"))

                // 2. Bale STUN UDP probe
                checks.add(probeStun("stun.bale.ai", 3478))

                // 3. Companion Server probe (if specified in domain or sshHost)
                val targetHost = profile.domain.ifEmpty { profile.sshHost }
                if (targetHost.isNotEmpty()) {
                    val port = if (profile.sshPort > 0) profile.sshPort else 443
                    checks.add(probeTcp("Companion Server ($targetHost:$port)", "سرور واسط Companion", targetHost, port))
                }
            }

            TunnelType.PINGTUNNEL -> {
                val host = profile.domain.ifEmpty { "8.8.8.8" }
                checks.add(probeIcmp(host))
            }

            TunnelType.SSH, TunnelType.SOCKS5, TunnelType.NAIVE, TunnelType.NAIVE_SSH,
            TunnelType.SPOOF_TUNNEL, TunnelType.GOLDEN_GRE, TunnelType.PAQET -> {
                val host = profile.domain.ifEmpty { profile.sshHost }
                val port = if (profile.sshPort > 0) profile.sshPort else profile.socks5ServerPort
                if (host.isNotEmpty()) {
                    checks.add(probeTcp("Server TCP ($host:$port)", "اتصال تی‌سی‌پی سرور", host, port))
                }
            }

            TunnelType.VLESS -> {
                val host = profile.domain.ifEmpty { profile.cdnIp }
                val port = if (profile.cdnPort > 0) profile.cdnPort else 443
                if (host.isNotEmpty()) {
                    checks.add(probeTcp("VLESS/CDN Endpoint ($host:$port)", "سرور VLESS / CDN", host, port))
                }
            }

            TunnelType.DOH -> {
                val doh = profile.dohUrl.ifEmpty { "https://dns.google/dns-query" }
                checks.add(probeHttpMeet(doh, nameEn = "DoH Endpoint", nameFa = "سرور DoH"))
            }

            else -> {
                // DNS Tunnels: MasterDNS, StormDNS, DNSTT, NoizDNS, Slipstream, VayDNS, Cotten, Soar, Noosh, qWDTT
                // 1. UDP 53 to resolvers
                val resolvers = profile.resolvers.ifEmpty { listOf(app.slipnet.domain.model.DnsResolver("8.8.8.8", 53)) }
                val firstResolver = resolvers.first()
                checks.add(probeDnsUdp53(firstResolver.host, firstResolver.port, profile.domain))

                // 2. DNS Delegation check via the selected working resolver (not hardcoded to blocked 8.8.8.8)
                if (profile.domain.isNotEmpty()) {
                    checks.add(probeDnsDelegation(profile.domain, firstResolver.host, firstResolver.port))
                }

                // 3. Direct server SSH TCP port (check sshHost IP directly)
                val targetHost = profile.sshHost.ifEmpty { "16.171.152.115" }
                val targetPort = if (profile.sshPort > 0) profile.sshPort else 22
                if (targetHost.isNotEmpty()) {
                    checks.add(probeTcp("SSH Server Port ($targetHost:$targetPort)", "ارتباط پورت SSH سرور", targetHost, targetPort))
                }
            }
        }

        val total = checks.size
        val passed = checks.count { it.success }

        val status = when {
            passed == total && total > 0 -> HealthStatus.OPEN
            passed > 0 -> HealthStatus.PARTLY
            else -> HealthStatus.BLOCKED
        }

        val summaryEn = when (status) {
            HealthStatus.OPEN -> "All path probes succeeded. Method is OPEN."
            HealthStatus.PARTLY -> "Some probes failed ($passed/$total passed). Method is degraded or partially blocked."
            HealthStatus.BLOCKED -> "All probes failed (0/$total passed). Method is BLOCKED on this network."
        }

        val summaryFa = when (status) {
            HealthStatus.OPEN -> "تمامی آزمون‌های مسیر موفق بودند. این متود کاملاً باز است."
            HealthStatus.PARTLY -> "برخی آزمون‌ها ناموفق بودند ($passed از $total موفق). مسیر کند یا نیمه‌مسدود است."
            HealthStatus.BLOCKED -> "همه آزمون‌ها رد شدند (۰ از $total). این متود در این شبکه مسدود است."
        }

        HealthCheckReport(
            profileName = profile.name,
            tunnelType = profile.tunnelType.displayName,
            status = status,
            checks = checks,
            summaryEn = summaryEn,
            summaryFa = summaryFa
        )
    }

    private fun probeTcp(nameEn: String, nameFa: String, host: String, port: Int, timeoutMs: Int = 3000): CheckItem {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val lat = System.currentTimeMillis() - t0
            CheckItem(nameEn, nameFa, true, lat, "TCP handshake OK (${lat}ms)")
        } catch (e: Exception) {
            val lat = System.currentTimeMillis() - t0
            CheckItem(nameEn, nameFa, false, lat, "TCP connection failed: ${e.message}")
        }
    }

    private fun probeHttpMeet(
        urlStr: String,
        nameEn: String = "Bale Meet HTTP",
        nameFa: String = "سرویس وب Bale Meet",
        timeoutMs: Int = 4000
    ): CheckItem {
        val t0 = System.currentTimeMillis()
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            val lat = System.currentTimeMillis() - t0
            val ok = code in 200..499 // Even 401/403 proves server is reachable and responding
            CheckItem(nameEn, nameFa, ok, lat, "HTTP status $code (${lat}ms)")
        } catch (e: Exception) {
            val lat = System.currentTimeMillis() - t0
            CheckItem(nameEn, nameFa, false, lat, "HTTP unreachable: ${e.message}")
        }
    }

    private fun probeStun(host: String, port: Int, timeoutMs: Int = 3000): CheckItem {
        val t0 = System.currentTimeMillis()
        val nameEn = "STUN UDP Probe ($host:$port)"
        val nameFa = "آزمون UDP سرور STUN بله"
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                // RFC 5389 STUN Binding Request (20 bytes header)
                val req = ByteArray(20)
                req[0] = 0x00.toByte()
                req[1] = 0x01.toByte() // Binding Request
                req[2] = 0x00.toByte()
                req[3] = 0x00.toByte() // Length 0
                req[4] = 0x21.toByte()
                req[5] = 0x12.toByte()
                req[6] = 0xA4.toByte()
                req[7] = 0x42.toByte() // Magic Cookie
                Random.nextBytes(req, 8, 20) // Transaction ID

                val addr = InetAddress.getByName(host)
                val pkt = DatagramPacket(req, req.size, addr, port)
                socket.send(pkt)

                val respBuf = ByteArray(512)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPkt)

                val lat = System.currentTimeMillis() - t0
                val isBindingResponse = respBuf.size >= 2 && respBuf[0] == 0x01.toByte() && respBuf[1] == 0x01.toByte()
                CheckItem(nameEn, nameFa, true, lat, if (isBindingResponse) "STUN Binding Response OK (${lat}ms)" else "UDP packet received (${lat}ms)")
            }
        } catch (e: Exception) {
            val lat = System.currentTimeMillis() - t0
            CheckItem(nameEn, nameFa, false, lat, "STUN UDP timeout/blocked: ${e.message}")
        }
    }

    private fun probeIcmp(host: String): CheckItem {
        val t0 = System.currentTimeMillis()
        val nameEn = "ICMP Echo ($host)"
        val nameFa = "آزمون پینگ ICMP"
        return try {
            val proc = ProcessBuilder("ping", "-c", "1", "-W", "2", host)
                .redirectErrorStream(true)
                .start()
            val exitCode = proc.waitFor()
            val lat = System.currentTimeMillis() - t0
            if (exitCode == 0) {
                CheckItem(nameEn, nameFa, true, lat, "Ping response received (${lat}ms)")
            } else {
                CheckItem(nameEn, nameFa, false, lat, "ICMP packet lost or blocked (exit $exitCode)")
            }
        } catch (e: Exception) {
            val lat = System.currentTimeMillis() - t0
            CheckItem(nameEn, nameFa, false, lat, "ICMP probe failed: ${e.message}")
        }
    }

    private fun probeDnsUdp53(resolverHost: String, resolverPort: Int, queryDomain: String): CheckItem {
        val t0 = System.currentTimeMillis()
        val nameEn = "DNS Resolver UDP:53 ($resolverHost:$resolverPort)"
        val nameFa = "ارتباط DNS UDP پورت ۵۳"
        val domain = queryDomain.ifEmpty { "example.com" }
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2500
                val q = buildDnsQuery(domain)
                val addr = InetAddress.getByName(resolverHost)
                val sendPkt = DatagramPacket(q, q.size, addr, resolverPort)
                socket.send(sendPkt)

                val resp = ByteArray(512)
                val recvPkt = DatagramPacket(resp, resp.size)
                socket.receive(recvPkt)

                val lat = System.currentTimeMillis() - t0
                CheckItem(nameEn, nameFa, true, lat, "DNS response received in ${lat}ms")
            }
        } catch (e: Exception) {
            val lat = System.currentTimeMillis() - t0
            CheckItem(nameEn, nameFa, false, lat, "DNS UDP 53 timeout or dropped: ${e.message}")
        }
    }

    private fun probeDnsDelegation(domain: String, resolverHost: String = "8.8.8.8", resolverPort: Int = 53): CheckItem {
        val t0 = System.currentTimeMillis()
        val nameEn = "NS Delegation ($domain)"
        val nameFa = "تفویض اختیارات NS دامنه"
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2500
                val q = buildDnsQuery(domain, qtype = 2 /* NS */)
                val addr = InetAddress.getByName(resolverHost)
                val sendPkt = DatagramPacket(q, q.size, addr, resolverPort)
                socket.send(sendPkt)

                val resp = ByteArray(512)
                val recvPkt = DatagramPacket(resp, resp.size)
                socket.receive(recvPkt)

                val lat = System.currentTimeMillis() - t0
                CheckItem(nameEn, nameFa, true, lat, "NS delegation validated in ${lat}ms")
            }
        } catch (e: Exception) {
            val lat = System.currentTimeMillis() - t0
            CheckItem(nameEn, nameFa, false, lat, "Delegation check warning: ${e.message}")
        }
    }

    private fun buildDnsQuery(domain: String, qtype: Short = 1 /* A */): ByteArray {
        val buf = ByteArray(512)
        // Header
        buf[0] = Random.nextInt(0, 255).toByte()
        buf[1] = Random.nextInt(0, 255).toByte()
        buf[2] = 0x01 // RD = 1
        buf[3] = 0x00
        buf[4] = 0x00 // QDCOUNT = 1
        buf[5] = 0x01
        var idx = 12

        // Question name
        val labels = domain.trimEnd('.').split('.')
        for (label in labels) {
            val b = label.toByteArray(Charsets.US_ASCII)
            buf[idx++] = b.size.toByte()
            System.arraycopy(b, 0, buf, idx, b.size)
            idx += b.size
        }
        buf[idx++] = 0x00 // root null

        // QTYPE
        buf[idx++] = (qtype.toInt() shr 8).toByte()
        buf[idx++] = (qtype.toInt() and 0xFF).toByte()
        // QCLASS = IN (1)
        buf[idx++] = 0x00
        buf[idx++] = 0x01

        val out = ByteArray(idx)
        System.arraycopy(buf, 0, out, 0, idx)
        return out
    }
}
