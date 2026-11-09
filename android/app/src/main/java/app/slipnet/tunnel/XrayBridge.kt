package app.slipnet.tunnel

import android.content.Context
import app.slipnet.util.AppLog as Log
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded Xray-core (VLESS/Reality, VMess, Trojan, Shadowsocks, Hysteria2,
 * WireGuard, XHTTP, MASQUE ...). The core exposes one local SOCKS5 inbound that
 * hev-socks5-tunnel feeds exactly like every other tunnel type.
 */
object XrayBridge {
    private const val TAG = "XrayBridge"
    const val INBOUND_TAG = "aryana-in"

    private var controller: CoreController? = null
    private val running = AtomicBoolean(false)
    private val txTotal = AtomicLong(0)
    private val rxTotal = AtomicLong(0)
    private var envReady = false

    private val callback = object : CoreCallbackHandler {
        override fun onEmitStatus(p0: Long, p1: String?): Long {
            Log.d(TAG, "core status: $p1")
            return 0
        }
        override fun shutdown(): Long = 0
        override fun startup(): Long = 0
    }

    /** sing-box configs use "type"/"endpoints"; Xray configs use "protocol". */
    fun isSingBoxJson(json: String): Boolean = try {
        val r = JSONObject(json)
        if (r.has("endpoints")) true else {
            val ob = r.optJSONArray("outbounds")
            var sb = false
            if (ob != null) for (i in 0 until ob.length()) {
                val o = ob.optJSONObject(i) ?: continue
                if (o.has("type") && !o.has("protocol")) { sb = true; break }
            }
            sb
        }
    } catch (_: Exception) { false }

    fun isRunning(): Boolean =
        SingBoxBridge.isRunning() || NativeCore.isRunning() || (running.get() && controller?.getIsRunning() == true)
    fun getTxBytes(): Long { if (SingBoxBridge.isRunning() || NativeCore.isRunning()) return 0L; pollStats(); return txTotal.get() }
    fun getRxBytes(): Long { if (SingBoxBridge.isRunning() || NativeCore.isRunning()) return 0L; pollStats(); return rxTotal.get() }

    private fun pollStats() {
        val c = controller ?: return
        if (!running.get()) return
        try {
            val s = c.queryAllOutboundTrafficStats()
            if (s.isNullOrBlank()) return
            for (item in s.split(';')) {
                val p = item.split(',')
                if (p.size != 3) continue
                // Only the proxy outbounds count as tunnel traffic, never direct/block/dns.
                if (p[0] == "direct" || p[0].equals("DIRECT", true) || p[0].equals("BLOCK", true) ||
                    p[0] == "dns-out" || p[0] == "block") continue
                val v = p[2].toLongOrNull() ?: continue
                if (p[1] == "uplink") txTotal.addAndGet(v) else if (p[1] == "downlink") rxTotal.addAndGet(v)
            }
        } catch (_: Throwable) {}
    }

    private fun prepareEnv(context: Context) {
        if (envReady) return
        val dir = context.filesDir
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val f = File(dir, name)
            if (!f.exists() || f.length() < 1024) {
                try {
                    context.assets.open("xray/$name").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                } catch (e: Exception) {
                    Log.w(TAG, "bundled $name missing: ${e.message}")
                }
            }
        }
        Libv2ray.initCoreEnv(dir.absolutePath, "")
        envReady = true
    }

    /**
     * Rewrites the user's config so that the only inbound is our local SOCKS5
     * listener, leaving outbounds, routing and dns exactly as the user wrote them.
     * Returns the final JSON.
     */
    fun buildConfig(userJson: String, listenPort: Int, listenHost: String): String {
        val root = JSONObject(userJson)
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", INBOUND_TAG)
            put("listen", listenHost)
            put("port", listenPort)
            put("protocol", "socks")
            put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                put("routeOnly", true)
            })
        })
        root.put("inbounds", inbounds)
        if (!root.has("log")) root.put("log", JSONObject().put("loglevel", "warning"))
        if (!root.has("stats")) root.put("stats", JSONObject())
        val policy = root.optJSONObject("policy") ?: JSONObject().also { root.put("policy", it) }
        val system = policy.optJSONObject("system") ?: JSONObject().also { policy.put("system", it) }
        system.put("statsOutboundUplink", true).put("statsOutboundDownlink", true)
        // A config that defines no routing/outbound "freedom" still needs a catch-all.
        val ob = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        var hasDirect = false
        for (i in 0 until ob.length()) {
            val o = ob.optJSONObject(i) ?: continue
            if (o.optString("protocol") == "freedom") hasDirect = true
        }
        if (!hasDirect) ob.put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
        return root.toString()
    }

    fun start(context: Context, userJson: String, listenPort: Int, listenHost: String = "127.0.0.1"): Result<Unit> {
        if (NativeCore.engineOf(userJson) != null) return NativeCore.start(context, userJson, listenPort, listenHost)
        if (isSingBoxJson(userJson)) return SingBoxBridge.start(context, userJson, listenPort, listenHost)
        return try {
            stop()
            prepareEnv(context)
            val cfg = buildConfig(userJson, listenPort, listenHost)
            txTotal.set(0); rxTotal.set(0)
            val c = Libv2ray.newCoreController(callback)
            c.startLoop(cfg, 0)
            controller = c
            running.set(true)
            Log.i(TAG, "Xray core started on $listenHost:$listenPort")
            Result.success(Unit)
        } catch (t: Throwable) {
            running.set(false)
            Log.e(TAG, "Xray start failed: ${t.message}")
            Result.failure(Exception("Xray: ${t.message}"))
        }
    }

    fun stop() {
        SingBoxBridge.stop()
        NativeCore.stop()
        val c = controller
        controller = null
        running.set(false)
        try { c?.stopLoop() } catch (t: Throwable) { Log.w(TAG, "stop: ${t.message}") }
    }

    /**
     * Real end-to-end check: fetch a tiny page through the local SOCKS5 inbound.
     * Only a successful exchange counts, so "Connected" is never shown for a
     * tunnel that cannot carry traffic.
     */
    fun probe(listenPort: Int, timeoutMs: Int = 8000): Result<Long> {
        val targets = listOf(
            "connectivitycheck.gstatic.com" to "/generate_204",
            "cp.cloudflare.com" to "/generate_204",
            "www.google.com" to "/generate_204"
        )
        var last: Throwable? = null
        for ((host, path) in targets) {
            val t0 = System.currentTimeMillis()
            try {
                Socket().use { s ->
                    s.soTimeout = timeoutMs
                    s.connect(InetSocketAddress("127.0.0.1", listenPort), 2000)
                    val o = s.getOutputStream(); val i = s.getInputStream()
                    o.write(byteArrayOf(5, 1, 0)); o.flush()
                    val r = ByteArray(2); if (i.readNBytes(r, 0, 2) != 2 || r[1].toInt() != 0) error("socks greeting rejected")
                    val hb = host.toByteArray()
                    val req = ByteArray(7 + hb.size)
                    req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3; req[4] = hb.size.toByte()
                    System.arraycopy(hb, 0, req, 5, hb.size)
                    req[5 + hb.size] = 0; req[6 + hb.size] = 80
                    o.write(req); o.flush()
                    val h = ByteArray(4); if (i.readNBytes(h, 0, 4) != 4 || h[1].toInt() != 0) error("socks connect failed (${h.getOrNull(1)})")
                    when (h[3].toInt()) { 1 -> i.skip(6); 4 -> i.skip(18); 3 -> { val l = i.read(); i.skip((l + 2).toLong()) } }
                    o.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
                    val buf = ByteArray(32); val n = i.read(buf)
                    val line = if (n > 0) String(buf, 0, n) else ""
                    if (!line.startsWith("HTTP/1.")) error("no HTTP reply")
                }
                return Result.success(System.currentTimeMillis() - t0)
            } catch (t: Throwable) { last = t }
        }
        return Result.failure(last ?: Exception("probe failed"))
    }
}
