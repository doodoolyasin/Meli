package app.slipnet.util

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotManager {
    private const val TAG = "HotspotManager"

    data class ShareInfo(
        val ip: String,
        val isHotspot: Boolean,
        val socksPort: Int,
        val httpPort: Int
    )

    fun detectShareableIp(): Pair<String, Boolean>? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val hotspotPrefixes = listOf("wlan1", "ap0", "swlan0", "softap", "wlan-ap", "rndis")

            // 1. Hotspot interface
            for (iface in interfaces) {
                if (!iface.isUp) continue
                if (hotspotPrefixes.any { iface.name.startsWith(it) }) {
                    val ip = iface.inetAddresses.toList()
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                        ?.hostAddress
                    if (ip != null) return ip to true
                }
            }

            // 2. Wi-Fi interface
            for (iface in interfaces) {
                if (!iface.isUp) continue
                if (iface.name.startsWith("wlan0") || iface.name.startsWith("wlan")) {
                    val ip = iface.inetAddresses.toList()
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                        ?.hostAddress
                    if (ip != null) return ip to false
                }
            }

            // 3. Fallback to any non-loopback IPv4 interface
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val ip = iface.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                if (ip != null) return ip to false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting IP: ${e.message}")
        }
        return null
    }

    /**
     * Attempts transparent TCP traffic redirect via iptables if root (su) is available.
     */
    fun enableRootTransparentTether(targetPort: Int): Boolean {
        return try {
            val cmds = arrayOf(
                "su", "-c",
                "sysctl -w net.ipv4.ip_forward=1; " +
                "iptables -t nat -D PREROUTING -p tcp -j REDIRECT --to-ports $targetPort 2>/dev/null; " +
                "iptables -t nat -A PREROUTING -p tcp -j REDIRECT --to-ports $targetPort"
            )
            val p = Runtime.getRuntime().exec(cmds)
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun disableRootTransparentTether(targetPort: Int) {
        try {
            val cmds = arrayOf(
                "su", "-c",
                "iptables -t nat -D PREROUTING -p tcp -j REDIRECT --to-ports $targetPort 2>/dev/null"
            )
            Runtime.getRuntime().exec(cmds).waitFor()
        } catch (_: Exception) {}
    }
}
