package app.slipnet.util

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first

/**
 * Shares the active tunnel with devices connected to this phone's hotspot / LAN.
 *
 * Non-root: Android never routes tethered packets through a VpnService, so we expose the
 * tunnel's local proxies on all interfaces through a plain TCP relay:
 *   0.0.0.0:[SOCKS_PORT] -> 127.0.0.1:<tunnel SOCKS5 port>
 *   0.0.0.0:[HTTP_PORT]  -> 127.0.0.1:<tunnel HTTP proxy port>
 * plus a PAC file at http://<ip>:[HTTP_PORT]/proxy.pac so the client only sets
 * "Proxy: Auto" in its Wi-Fi settings — no VPN app needed on the client.
 *
 * Root: policy-route everything arriving from the hotspot interface into the tun device
 * and NAT it, so clients need no configuration at all.
 */
object HotspotRelay {
    private const val TAG = "HotspotRelay"
    const val SOCKS_PORT = 10809
    const val HTTP_PORT = 10810
    private const val ROUTE_TABLE = 1777
    private const val RULE_PRIORITY = 17000

    private val running = AtomicBoolean(false)
    private val servers = mutableListOf<ServerSocket>()

    private val relayedUp = java.util.concurrent.atomic.AtomicLong(0L)
    private val relayedDown = java.util.concurrent.atomic.AtomicLong(0L)
    /** Cumulative bytes client->tunnel / tunnel->client. Monotonic for the process lifetime. */
    val bytesUp: Long get() = relayedUp.get()
    val bytesDown: Long get() = relayedDown.get()
    val bytesRelayed: Long get() = relayedUp.get() + relayedDown.get()
    @Volatile var activeClients: Int = 0
        private set

    fun isRunning() = running.get()

    @Synchronized
    fun start(socksTarget: Int, httpTarget: Int): Result<Unit> {
        stop()
        return try {
            // If the user's own proxy already sits on a relay port, don't fight it for the bind.
            if (socksTarget != SOCKS_PORT) servers += listen(SOCKS_PORT, socksTarget, servePac = false)
            if (httpTarget != HTTP_PORT) servers += listen(HTTP_PORT, httpTarget, servePac = true)
            running.set(true)
            Log.i(TAG, "Relay up: :$SOCKS_PORT->$socksTarget, :$HTTP_PORT->$httpTarget")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Relay start failed: ${e.message}")
            stop()
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        servers.forEach { try { it.close() } catch (_: Exception) {} }
        servers.clear()
        activeClients = 0
    }

    private fun listen(port: Int, target: Int, servePac: Boolean): ServerSocket {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", port))
        Thread({
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                // Never act as an open proxy for the upstream Wi-Fi/carrier network:
                // only devices on our own hotspot/tether subnet (or loopback) are served.
                if (!isAllowedClient(c)) { try { c.close() } catch (_: Exception) {}; continue }
                Thread({ handle(c, target, servePac) }, "hotspot-relay-conn").apply { isDaemon = true; start() }
            }
        }, "hotspot-relay-$port").apply { isDaemon = true; start() }
        return ss
    }

    private fun handle(client: Socket, target: Int, servePac: Boolean) {
        synchronized(this) { activeClients++ }
        try {
            client.tcpNoDelay = true
            val cin = java.io.BufferedInputStream(client.getInputStream(), 8192)
            if (servePac) {
                cin.mark(64)
                val head = ByteArray(24)
                var n = 0
                client.soTimeout = 3000
                try {
                    while (n < 14) { val k = cin.read(head, n, head.size - n); if (k < 0) break; n += k }
                } catch (_: java.net.SocketTimeoutException) {}
                client.soTimeout = 0
                cin.reset()
                if (n > 0 && String(head, 0, n, Charsets.ISO_8859_1).startsWith("GET /proxy.pac")) {
                    servePacFile(client)
                    return
                }
            }
            Socket().use { up ->
                up.tcpNoDelay = true
                up.connect(InetSocketAddress("127.0.0.1", target), 5000)
                val t = Thread({ pump(up.getInputStream(), client.getOutputStream(), relayedDown); try { client.shutdownOutput() } catch (_: Exception) {} }, "relay-down")
                t.isDaemon = true; t.start()
                pump(cin, up.getOutputStream(), relayedUp)
                try { up.shutdownOutput() } catch (_: Exception) {}
                // No timeout: a half-closed client may still be receiving a long download.
                // The down pump ends as soon as the upstream proxy closes its side.
                t.join()
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            synchronized(this) { activeClients = (activeClients - 1).coerceAtLeast(0) }
        }
    }

    private fun pump(i: InputStream, o: OutputStream, counter: java.util.concurrent.atomic.AtomicLong) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n); o.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {}
    }

    private fun servePacFile(client: Socket) {
        val ip = (client.localAddress?.hostAddress ?: "192.168.43.1").substringBefore('%')
        val pac = """function FindProxyForURL(url, host) {
  // No dnsResolve(): it would leak every hostname to the carrier DNS and stall on poisoning.
  if (isPlainHostName(host) || shExpMatch(host, "*.local") ||
      shExpMatch(host, "192.168.*") || shExpMatch(host, "10.*") ||
      shExpMatch(host, "127.*")) return "DIRECT";
  return "PROXY $ip:$HTTP_PORT; SOCKS5 $ip:$SOCKS_PORT; SOCKS $ip:$SOCKS_PORT";
}
"""
        val body = pac.toByteArray()
        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/x-ns-proxy-autoconfig\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(hdr.toByteArray()); write(body); flush() }
    }

    private fun isAllowedClient(c: Socket): Boolean = try {
        val ra = c.inetAddress
        if (ra.isLoopbackAddress) true
        else if (ra !is Inet4Address) false
        else {
            val r = ra.address
            hotspotInterfaces()
                .flatMap { it.interfaceAddresses.orEmpty() }
                .any { ia ->
                    val a = ia.address as? Inet4Address ?: return@any false
                    val plen = ia.networkPrefixLength.toInt().coerceIn(0, 32)
                    val mask = if (plen == 0) 0 else (-1 shl (32 - plen))
                    fun i(b: ByteArray) = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
                        ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
                    (i(a.address) and mask) == (i(r) and mask)
                }
        }
    } catch (_: Exception) { false }

    // ── Interface detection ────────────────────────────────────────────

    private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "wlan2", "wigig", "rndis", "usb", "ncm", "bt-pan", "eth")

    /**
     * Tethering interfaces. Besides the usual ap0/swlan0/wlan1/rndis names, many phones run the
     * hotspot on wlan0 itself when Wi-Fi client mode is off; there the phone is the gateway (.1).
     */
    private fun hotspotInterfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .filter { nif ->
                HOTSPOT_PREFIXES.any { p -> nif.name.startsWith(p) } ||
                    (nif.name.startsWith("wlan") && nif.inetAddresses.toList().any {
                        it is Inet4Address && it.isSiteLocalAddress && (it.address[3].toInt() and 255) == 1
                    })
            }
    } catch (_: Exception) { emptyList() }

    /** (interface name, IPv4) of the tethering interface, or null if no hotspot is up. */
    fun detectHotspot(): Pair<String, String>? = try {
        hotspotInterfaces()
            .firstNotNullOfOrNull { nif ->
                nif.inetAddresses.toList().firstOrNull { it is Inet4Address }?.hostAddress?.let { nif.name to it }
            }
    } catch (_: Exception) { null }

    /** Best LAN IPv4 to show the user: hotspot first, then Wi-Fi. */
    fun shareIp(): String = detectHotspot()?.second ?: try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            .firstNotNullOfOrNull { n -> n.inetAddresses.toList().firstOrNull { it is Inet4Address }?.hostAddress }
    } catch (_: Exception) { null } ?: "192.168.43.1"

    private fun tunName(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .firstOrNull { it.isUp && it.name.startsWith("tun") }?.name
    } catch (_: Exception) { null }

    // ── Root transparent mode ──────────────────────────────────────────

    fun hasRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        p.inputStream.bufferedReader().readText().trim() == "0" && p.waitFor() == 0
    } catch (_: Exception) { false }

    /** Route all hotspot clients through tun. Returns null on success or an error message. */
    /** "a.b.c.d/len" network of the hotspot interface, used for the return route. */
    private fun subnetOf(ifName: String): String? = try {
        NetworkInterface.getByName(ifName)?.interfaceAddresses.orEmpty()
            .firstOrNull { it.address is Inet4Address }?.let { ia ->
                val plen = ia.networkPrefixLength.toInt().coerceIn(1, 32)
                val b = ia.address.address
                val v = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
                    ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
                val n = v and (-1 shl (32 - plen))
                "${(n ushr 24) and 255}.${(n ushr 16) and 255}.${(n ushr 8) and 255}.${n and 255}/$plen"
            }
    } catch (_: Exception) { null }

    @Volatile var appliedFor: Pair<String, String>? = null
        private set

    fun applyRootRouting(): String? {
        val hs = detectHotspot()?.first ?: return "Hotspot interface not found — turn the hotspot on first"
        val tun = tunName() ?: return "Tunnel (tun) interface not found — connect in VPN mode first"
        val net = subnetOf(hs) ?: return "Hotspot subnet not found"
        clearRootRouting()
        val c = "-m comment --comment aryana_hs"
        val script = listOf(
            "sysctl -w net.ipv4.ip_forward=1 >/dev/null",
            "ip route replace default dev $tun table $ROUTE_TABLE",
            // Return path: de-NATed replies coming out of tun must reach the hotspot subnet,
            // otherwise Android's per-network tables drop them.
            "ip route replace $net dev $hs table $ROUTE_TABLE",
            "ip rule add iif $hs lookup $ROUTE_TABLE priority $RULE_PRIORITY",
            "ip rule add iif $tun to $net lookup $ROUTE_TABLE priority ${RULE_PRIORITY + 1}",
            "iptables -w -t nat -I POSTROUTING -s $net -o $tun $c -j MASQUERADE",
            "iptables -w -I FORWARD -i $hs -o $tun $c -j ACCEPT",
            "iptables -w -I FORWARD -i $tun -o $hs -m state --state RELATED,ESTABLISHED $c -j ACCEPT",
            // Client DNS must also leave through the tunnel, not the carrier resolver.
            "iptables -w -t nat -I PREROUTING -i $hs -p udp --dport 53 $c -j DNAT --to-destination 1.1.1.1:53",
            "iptables -w -t nat -I PREROUTING -i $hs -p tcp --dport 53 $c -j DNAT --to-destination 1.1.1.1:53",
            // The tunnel is IPv4-only here; block forwarded IPv6 so clients can't bypass it.
            "(ip6tables -w -I FORWARD -i $hs $c -j DROP || true)"
        ).joinToString(" && ")
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val err = p.errorStream.bufferedReader().readText()
            if (p.waitFor() == 0) { appliedFor = hs to tun; null } else "Root routing failed: ${err.take(200)}"
        } catch (e: Exception) { "Root routing failed: ${e.message}" }
    }

    fun clearRootRouting() {
        val script = """
            i=0; while ip rule del lookup $ROUTE_TABLE 2>/dev/null && [ ${'$'}i -lt 20 ]; do i=${'$'}((i+1)); done
            ip route flush table $ROUTE_TABLE 2>/dev/null
            for t in nat filter; do
              iptables -w -t ${'$'}t -S 2>/dev/null | grep aryana_hs | sed 's/^-A/-D/' | while read r; do eval iptables -w -t ${'$'}t ${'$'}r; done
            done
            ip6tables -w -S FORWARD 2>/dev/null | grep aryana_hs | sed 's/^-A/-D/' | while read r; do eval ip6tables -w ${'$'}r; done
            true
        """.trimIndent()
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() } catch (_: Exception) {}
        appliedFor = null
    }
}

/** Glue used by both the VPN service (on connect/disconnect) and the UI toggle. */
object HotspotShare {
    private const val TAG = "HotspotShare"
    @Volatile var rootActive = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var startedHttp = false
    @Volatile private var watcher: Thread? = null
    @Volatile private var rootCapable = false

    /** Bring sharing up for the currently running tunnel. Safe to call repeatedly. */
    suspend fun activate(prefs: app.slipnet.data.local.datastore.PreferencesDataStore, vpnMode: Boolean): String? {
        val socksPort = prefs.proxyListenPort.first()
        val httpPort = prefs.httpProxyPort.first()
        // The HTTP front-end is needed for PAC/HTTP clients even if the user disabled it.
        if (!app.slipnet.tunnel.HttpProxyServer.isRunning()) {
            val authOn = prefs.proxyAuthEnabled.first()
            startedHttp = app.slipnet.tunnel.HttpProxyServer.start(
                socksHost = "127.0.0.1", socksPort = socksPort,
                listenHost = "127.0.0.1", listenPort = httpPort,
                socksAuthUsername = if (authOn) prefs.proxyAuthUsername.first().ifEmpty { null } else null,
                socksAuthPassword = if (authOn) prefs.proxyAuthPassword.first().ifEmpty { null } else null
            ).isSuccess
        }
        val r = HotspotRelay.start(socksPort, httpPort)
        lastError = r.exceptionOrNull()?.message
        rootActive = false
        rootCapable = vpnMode && HotspotRelay.hasRoot()
        if (rootCapable) {
            val err = HotspotRelay.applyRootRouting()
            rootActive = err == null
            if (err != null) android.util.Log.w(TAG, err)
            startWatcher()
        }
        return lastError
    }

    /**
     * Hotspot is often switched on *after* connecting, and Android can recreate the AP
     * interface (new name/subnet) at any time. Re-apply root routing whenever that changes.
     */
    private fun startWatcher() {
        if (watcher?.isAlive == true) return
        watcher = Thread({
            while (rootCapable && HotspotRelay.isRunning()) {
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
                if (!rootCapable) break
                val hs = HotspotRelay.detectHotspot()?.first
                val applied = HotspotRelay.appliedFor
                if (hs != null && applied?.first != hs) {
                    rootActive = HotspotRelay.applyRootRouting() == null
                } else if (hs == null && applied != null) {
                    HotspotRelay.clearRootRouting(); rootActive = false
                }
            }
        }, "hotspot-root-watch").apply { isDaemon = true; start() }
    }

    fun deactivate() {
        rootCapable = false
        watcher?.interrupt(); watcher = null
        HotspotRelay.stop()
        if (rootActive || HotspotRelay.appliedFor != null) HotspotRelay.clearRootRouting()
        rootActive = false
        if (startedHttp) { startedHttp = false; try { app.slipnet.tunnel.HttpProxyServer.stop() } catch (_: Exception) {} }
    }
}
