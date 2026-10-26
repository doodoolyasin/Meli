package app.slipnet.tunnel

import android.net.VpnService
import app.slipnet.util.AppLog as Log

/**
 * High-performance Native Rust Meli Protocol Bridge.
 * Communicates with `libslipstream.so` via JNI to run an asynchronous
 * SOCKS5 DNS tunnel engine written in pure Rust.
 */
object MeliBridge {
    private const val TAG = "MeliBridge"
    const val DEFAULT_MELI_PORT = 19080
    const val DEFAULT_LISTEN_HOST = "127.0.0.1"

    private var isLibraryLoaded = false
    private var currentPort = DEFAULT_MELI_PORT

    @Volatile
    private var vpnServiceRef: VpnService? = null

    @Volatile
    var proxyOnlyMode = false

    init {
        try {
            System.loadLibrary("slipstream")
            isLibraryLoaded = true
            Log.d(TAG, "Meli native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load Meli native library", e)
            isLibraryLoaded = false
        }
    }

    fun isLoaded(): Boolean = isLibraryLoaded

    fun setVpnService(service: VpnService?) {
        vpnServiceRef = service
        Log.d(TAG, "VpnService ${if (service != null) "set" else "cleared"}")
    }

    @JvmStatic
    fun protectSocket(fd: Int): Boolean {
        if (proxyOnlyMode) return true
        val service = vpnServiceRef
        if (service == null) {
            Log.e(TAG, "Cannot protect socket: VpnService not available")
            return false
        }
        val result = service.protect(fd)
        Log.d(TAG, "Protected socket fd=$fd, result=$result")
        return result
    }

    fun startClient(
        domain: String,
        resolver: String,
        listenPort: Int = DEFAULT_MELI_PORT,
        listenHost: String = DEFAULT_LISTEN_HOST,
        encryptionKey: String = ""
    ): Result<Unit> {
        if (!isLibraryLoaded) {
            return Result.failure(IllegalStateException("Meli native library not loaded"))
        }

        currentPort = listenPort
        Log.i(TAG, "Starting Meli client: domain=$domain, resolver=$resolver, port=$listenPort")

        val success = nativeStartClient(
            domain = domain,
            resolver = resolver,
            listenPort = listenPort,
            listenHost = listenHost,
            encryptionKey = encryptionKey
        )

        return if (success) {
            Result.success(Unit)
        } else {
            val err = nativeGetLastError()
            Result.failure(RuntimeException(if (err.isNotBlank()) err else "Failed to start Meli client"))
        }
    }

    fun stopClient(): Result<Unit> {
        if (!isLibraryLoaded) {
            return Result.success(Unit)
        }
        val success = nativeStopClient()
        return if (success) Result.success(Unit) else Result.failure(RuntimeException("Failed to stop Meli client"))
    }

    fun isRunning(): Boolean {
        if (!isLibraryLoaded) return false
        return nativeIsClientRunning()
    }

    fun getClientPort(): Int {
        if (!isLibraryLoaded) return currentPort
        val port = nativeGetClientPort()
        return if (port > 0) port else currentPort
    }

    // JNI Native Methods
    @JvmStatic
    private external fun nativeStartClient(
        domain: String,
        resolver: String,
        listenPort: Int,
        listenHost: String,
        encryptionKey: String
    ): Boolean

    @JvmStatic
    private external fun nativeStopClient(): Boolean

    @JvmStatic
    private external fun nativeIsClientRunning(): Boolean

    @JvmStatic
    private external fun nativeGetClientPort(): Int

    @JvmStatic
    private external fun nativeGetLastError(): String
}
