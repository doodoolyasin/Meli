package app.slipnet.tunnel

import android.content.Context
import app.slipnet.util.AppLog as Log
import stormdns.Stormdns
import stormdns.StormDnsClient
import java.net.ServerSocket

/**
 * Bridge to the Go-based MasterDNS / StormDNS engine.
 * Provides a high-speed SOCKS5 tunnel over DNS queries with XOR/AES encryption.
 */
object MasterDnsBridge {
    private const val TAG = "MasterDnsBridge"

    private var client: StormDnsClient? = null
    private var currentPort: Int = 0
    private var vpnService: android.net.VpnService? = null

    fun setVpnService(service: android.net.VpnService?) {
        vpnService = service
        try {
            if (service != null) {
                Stormdns.setSocketProtector(object : stormdns.SocketProtector {
                    override fun protect(fd: Long): Boolean {
                        return service.protect(fd.toInt())
                    }
                })
            } else {
                Stormdns.setSocketProtector(null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Socket protector hook: ${e.message}")
        }
    }

    fun getClientPort(): Int = currentPort

    /**
     * Start the MasterDNS / StormDNS client.
     *
     * @param context Android context for app files directory
     * @param domainsCsv Comma-separated tunnel domains (e.g. "goi.dhafhdtjdti.site")
     * @param encryptionKey Symmetric 16-byte key (32 hex characters)
     * @param encryptionMethod 0=none, 1=XOR, 2=ChaCha20, 3..5=AES-GCM (default 1)
     * @param resolversCsv Comma-separated resolver IPs (e.g. "8.8.8.8:53, 1.1.1.1:53")
     * @param listenPort Local SOCKS5 listener port (default 19999 or dynamic)
     * @param listenHost Local listen address (default 127.0.0.1)
     * @param logLevel Engine log level ("INFO", "DEBUG", "WARN", "ERROR")
     */
    fun startClient(
        context: Context,
        domainsCsv: String,
        encryptionKey: String,
        encryptionMethod: Long = 1L,
        resolversCsv: String,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        logLevel: String = "INFO"
    ): Result<Unit> {
        Log.i(TAG, "========================================")
        Log.i(TAG, "Starting MasterDNS / StormDNS client")
        Log.i(TAG, "  Domains: $domainsCsv")
        Log.i(TAG, "  Key: ${encryptionKey.take(8)}... (len=${encryptionKey.length})")
        Log.i(TAG, "  Resolvers: $resolversCsv")
        Log.i(TAG, "  Listen: $listenHost:$listenPort")
        Log.i(TAG, "========================================")

        stopClient()

        return try {
            currentPort = listenPort
            val configDir = context.filesDir.absolutePath

            // Ensure resolvers are formatted properly (strip transport prefixes)
            val cleanResolvers = resolversCsv.split(",")
                .map { it.trim().removePrefix("udp://").removePrefix("tcp://") }
                .filter { it.isNotBlank() }
                .joinToString(",")

            val newClient = Stormdns.newClient(
                domainsCsv.trim(),
                encryptionKey.trim(),
                encryptionMethod,
                cleanResolvers,
                configDir,
                listenHost,
                listenPort.toLong(),
                logLevel
            )

            newClient.start()
            client = newClient
            Log.i(TAG, "MasterDNS client started successfully on $listenHost:$listenPort")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MasterDNS client: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun stopClient() {
        try {
            client?.stop()
            Log.i(TAG, "MasterDNS client stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MasterDNS client: ${e.message}")
        } finally {
            client = null
        }
    }

    fun isRunning(): Boolean = client?.isRunning ?: false
    fun isSessionReady(): Boolean = client?.isSessionReady ?: false
    fun lastError(): String = client?.lastError() ?: ""
}
