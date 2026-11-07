package app.slipnet.tunnel

import android.net.VpnService
import java.lang.ref.WeakReference
import app.slipnet.util.AppLog as Log

/**
 * Global registry for active VpnService instance.
 * Ensures JNI socket protection hooks (Slipstream, Meli, MasterDNS, NativeBridge)
 * always resolve a valid VpnService reference and never fail with "Failed to protect UDP socket"
 * during tunnel transitions, reconnections, or between different native bridge calls.
 * Uses WeakReference to completely prevent memory leaks if service is killed by OS.
 */
object VpnServiceRegistry {
    private const val TAG = "VpnServiceRegistry"

    @Volatile
    private var serviceRef: WeakReference<VpnService>? = null

    val activeVpnService: VpnService?
        get() = serviceRef?.get()

    fun register(service: VpnService?) {
        if (service != null) {
            serviceRef = WeakReference(service)
            Log.d(TAG, "Active VpnService registered: ${service.javaClass.simpleName}")
        }
    }

    fun unregister(service: VpnService?) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            Log.d(TAG, "Active VpnService unregistered")
        }
    }

    fun protect(fd: Int): Boolean {
        val service = activeVpnService
        if (service == null) {
            Log.e(TAG, "Cannot protect socket fd=$fd: no active VpnService in registry")
            return false
        }
        val res = service.protect(fd)
        Log.d(TAG, "VpnServiceRegistry.protect(fd=$fd) result=$res")
        return res
    }
}
