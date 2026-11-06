package app.slipnet.tunnel

import android.net.VpnService
import app.slipnet.util.AppLog as Log

/**
 * Global registry for active VpnService instance.
 * Ensures JNI socket protection hooks (Slipstream, Meli, MasterDNS, NativeBridge)
 * always resolve a valid VpnService reference and never fail with "Failed to protect UDP socket"
 * during tunnel transitions, reconnections, or between different native bridge calls.
 */
object VpnServiceRegistry {
    private const val TAG = "VpnServiceRegistry"

    @Volatile
    var activeVpnService: VpnService? = null

    fun register(service: VpnService?) {
        if (service != null) {
            activeVpnService = service
            Log.d(TAG, "Active VpnService registered: ${service.javaClass.simpleName}")
        }
    }

    fun unregister(service: VpnService?) {
        if (activeVpnService === service) {
            activeVpnService = null
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
