package app.slipnet.tunnel

import android.content.Context
import app.slipnet.util.AppLog as Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded sing-box (OpenVPN, TUIC, ShadowTLS, AnyTLS, Hysteria, NaiveProxy, ...).
 * Runs as a native executable from nativeLibraryDir (same model as NaiveBridge) and
 * exposes one local SOCKS5 inbound that hev-socks5-tunnel feeds like every other type.
 * The process shares the app UID, so it is excluded from the VPN like the app itself.
 */
object SingBoxBridge {
    private const val TAG = "SingBoxBridge"
    private const val BINARY = "libsingbox.so"
    const val INBOUND_TAG = "aryana-in"

    @Volatile private var process: Process? = null
    private val ready = AtomicBoolean(false)
    @Volatile private var lastError: String = ""

    fun isRunning(): Boolean = process?.isAlive == true && ready.get()

    fun isAvailable(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, BINARY).exists()

    /** Replaces the user's inbounds with our local SOCKS5 listener; everything else is kept. */
    fun buildConfig(userJson: String, port: Int, host: String): String {
        val root = JSONObject(userJson)
        root.put("inbounds", JSONArray().put(JSONObject()
            .put("type", "mixed").put("tag", INBOUND_TAG)
            .put("listen", host).put("listen_port", port)))
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        log.put("level", log.optString("level", "warn")).put("timestamp", false)
        // sing-box would otherwise try to open its own TUN.
        root.remove("ntp")
        return root.toString()
    }

    fun start(context: Context, userJson: String, port: Int, host: String = "127.0.0.1"): Result<Unit> {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, BINARY)
        if (!bin.exists()) return Result.failure(Exception("sing-box core is not in this build (${bin.name})"))
        return try {
            val dir = File(context.filesDir, "singbox").apply { mkdirs() }
            val cfg = File(dir, "config.json")
            cfg.writeText(buildConfig(userJson, port, host))
            ready.set(false); lastError = ""
            val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath, "-D", dir.absolutePath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            Thread({
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                        Log.d(TAG, "sing-box: $line")
                        if (line.contains("FATAL", true) || line.contains("ERROR", true)) lastError = line.take(220)
                    }
                } catch (_: Exception) {}
            }, "singbox-out").also { it.isDaemon = true; it.start() }
            // Wait for the local inbound to accept connections.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    stop()
                    return Result.failure(Exception("sing-box exited: " + lastError.ifBlank { "invalid configuration" }))
                }
                try { Socket().use { it.connect(InetSocketAddress(host, port), 300) }; ready.set(true); break }
                catch (_: Exception) { Thread.sleep(150) }
            }
            if (!ready.get()) { stop(); return Result.failure(Exception("sing-box did not start: $lastError")) }
            Log.i(TAG, "sing-box started on $host:$port")
            Result.success(Unit)
        } catch (t: Throwable) {
            stop()
            Result.failure(Exception("sing-box: ${t.message}"))
        }
    }

    fun stop() {
        ready.set(false)
        val p = process
        process = null
        try { p?.destroy(); Thread.sleep(80); if (p?.isAlive == true) p.destroyForcibly() } catch (_: Throwable) {}
    }

    /** Real traffic probe through the local SOCKS5 inbound (shared with Xray). */
    fun probe(port: Int): Result<Long> = XrayBridge.probe(port)
}
