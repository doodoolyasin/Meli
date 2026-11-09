package app.slipnet.tunnel

import android.content.Context
import app.slipnet.util.AppLog as Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a bundled native core (libawg.so / libpsiphon.so) as a child process that exposes a
 * local SOCKS5 port. Wrapper JSON: {"aryana_engine":"awg","conf":"<wg-quick text>"} or
 * {"aryana_engine":"psiphon","config":{...Psiphon client config...}}.
 */
object NativeCore {
    private const val TAG = "NativeCore"
    @Volatile private var process: Process? = null
    private val ready = AtomicBoolean(false)
    @Volatile private var lastError = ""

    fun engineOf(json: String): String? = try { JSONObject(json).optString("aryana_engine").ifBlank { null } } catch (_: Exception) { null }

    fun wrapAwg(conf: String): String = JSONObject().put("aryana_engine", "awg").put("conf", conf).toString()
    fun wrapPsiphon(cfg: JSONObject): String = JSONObject().put("aryana_engine", "psiphon").put("config", cfg).toString()

    fun isRunning(): Boolean = process?.isAlive == true && ready.get()

    fun start(context: Context, json: String, port: Int, host: String): Result<Unit> {
        stop()
        val root = try { JSONObject(json) } catch (e: Exception) { return Result.failure(Exception("invalid config")) }
        val dir = File(context.filesDir, "core").apply { mkdirs() }
        val libDir = context.applicationInfo.nativeLibraryDir
        val cmd: List<String>
        var needTunnel = false
        when (root.optString("aryana_engine")) {
            "awg" -> {
                val bin = File(libDir, "libawg.so")
                if (!bin.exists()) return Result.failure(Exception("AmneziaWG core is not in this build"))
                val f = File(dir, "awg.conf"); f.writeText(root.getString("conf"))
                cmd = listOf(bin.absolutePath, "-c", f.absolutePath, "-l", "$host:$port")
            }
            "psiphon" -> {
                val bin = File(libDir, "libpsiphon.so")
                if (!bin.exists()) return Result.failure(Exception("Psiphon core is not in this build"))
                val c = root.getJSONObject("config")
                if (!c.has("PropagationChannelId") || !c.has("SponsorId"))
                    return Result.failure(Exception("Psiphon config needs PropagationChannelId and SponsorId (from Psiphon Inc. or your own server)"))
                val data = File(dir, "psiphon").apply { mkdirs() }
                c.put("LocalSocksProxyPort", port).put("LocalHttpProxyPort", 0).put("DisableLocalHTTPProxy", true)
                    .put("DataRootDirectory", data.absolutePath).put("EmitDiagnosticNotices", false)
                if (!c.has("ClientPlatform")) c.put("ClientPlatform", "Android_aryana")
                val f = File(dir, "psiphon.config"); f.writeText(c.toString())
                cmd = listOf(bin.absolutePath, "-config", f.absolutePath, "-dataRootDirectory", data.absolutePath)
                needTunnel = true
            }
            else -> return Result.failure(Exception("unknown core"))
        }
        return try {
            ready.set(false); lastError = ""
            val tunnelUp = AtomicBoolean(!needTunnel)
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = p
            Thread({
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                        if (line.contains("\"noticeType\":\"Tunnels\"") && Regex("\"count\":([1-9])").containsMatchIn(line)) tunnelUp.set(true)
                        if (line.contains("FATAL") || line.contains("\"noticeType\":\"Alert\"") || line.contains("\"noticeType\":\"InternalError\"")) lastError = line.take(220)
                        Log.d(TAG, "core: ${line.take(300)}")
                    }
                } catch (_: Exception) {}
            }, "native-core-out").also { it.isDaemon = true; it.start() }
            val deadline = System.currentTimeMillis() + if (needTunnel) 60_000 else 12_000
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) { stop(); return Result.failure(Exception("core exited: " + lastError.ifBlank { "invalid configuration" })) }
                if (tunnelUp.get()) {
                    try { Socket().use { it.connect(InetSocketAddress(host, port), 300) }; ready.set(true); break } catch (_: Exception) {}
                }
                Thread.sleep(200)
            }
            if (!ready.get()) { stop(); return Result.failure(Exception("core did not come up: $lastError")) }
            Result.success(Unit)
        } catch (t: Throwable) { stop(); Result.failure(Exception(t.message)) }
    }

    fun stop() {
        ready.set(false)
        val p = process; process = null
        try { p?.destroy(); Thread.sleep(80); if (p?.isAlive == true) p.destroyForcibly() } catch (_: Throwable) {}
    }
}
