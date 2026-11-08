package app.slipnet.presentation.main

import app.slipnet.domain.model.DnsTransport
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import app.slipnet.util.DnsProbe

/**
 * How a profile can be pinged without bringing a tunnel up.
 *
 * - [Dns]: the profile is a DNS tunnel; its domain must be relayed by a recursive resolver
 *   to the tunnel server (see [DnsProbe]).
 * - [Tcp]: the profile's server speaks TCP; a connect measures the round trip.
 * - [Unmeasurable]: UDP/ICMP/raw transports with no handshake that can be probed honestly.
 * - [Invalid]: the profile lacks the field needed to be pinged.
 */
internal sealed interface PingPlan {
    data class Dns(val domain: String, val transport: DnsProbe.Transport, val dohUrl: String) : PingPlan
    data class Tcp(val host: String, val port: Int) : PingPlan
    data object Unmeasurable : PingPlan
    data class Invalid(val message: String) : PingPlan
}

internal object PingPlanner {

    private val DNS_TUNNELS = setOf(
        TunnelType.SLIPSTREAM, TunnelType.SLIPSTREAM_SSH,
        TunnelType.DNSTT, TunnelType.DNSTT_SSH,
        TunnelType.NOIZDNS, TunnelType.NOIZDNS_SSH,
        TunnelType.VAYDNS, TunnelType.VAYDNS_SSH,
        TunnelType.MASTERDNS, TunnelType.MASTERDNS_SSH,
        TunnelType.STORMDNS, TunnelType.STORMDNS_SSH,
        TunnelType.COTTENDNS, TunnelType.COTTENDNS_SSH,
        TunnelType.SOAR, TunnelType.SOAR_SSH,
        TunnelType.MELI, TunnelType.MELI_SSH,
        TunnelType.NOOSHDAROO, TunnelType.NOOSHDAROO_SSH,
        TunnelType.QWDTT, TunnelType.QWDTT_SSH
    )

    private val UNMEASURABLE = setOf(
        TunnelType.SNOWFLAKE,
        TunnelType.WIREGUARD, TunnelType.WARP, TunnelType.AETHER,
        TunnelType.OPENVPN, TunnelType.L2TP,
        TunnelType.HYSTERIA2, TunnelType.TUIC,
        TunnelType.PAQET, TunnelType.SPOOF_TUNNEL, TunnelType.GOLDEN_GRE,
        TunnelType.PINGTUNNEL, TunnelType.BALE
    )

    fun isDnsTunnel(type: TunnelType): Boolean = type in DNS_TUNNELS

    fun plan(profile: ServerProfile): PingPlan {
        val type = profile.tunnelType
        if (type in DNS_TUNNELS) {
            // MasterDNS-style profiles may list several domains; the first one is probed.
            val domain = profile.domain.split(',', ';', ' ', '\n').map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: return PingPlan.Invalid("No domain")
            val transport = when (profile.dnsTransport) {
                DnsTransport.UDP -> DnsProbe.Transport.UDP
                DnsTransport.TCP -> DnsProbe.Transport.TCP
                DnsTransport.DOT -> DnsProbe.Transport.DOT
                DnsTransport.DOH -> DnsProbe.Transport.DOH
            }
            return PingPlan.Dns(domain, transport, profile.dohUrl.trim())
        }
        if (type in UNMEASURABLE) return PingPlan.Unmeasurable

        fun tcp(host: String, port: Int): PingPlan {
            val h = host.trim()
            return if (h.isEmpty()) PingPlan.Invalid("No host")
            else if (port !in 1..65535) PingPlan.Invalid("Bad port")
            else PingPlan.Tcp(h, port)
        }

        return when (type) {
            TunnelType.SSH -> tcp(profile.domain, profile.sshPort)
            TunnelType.NAIVE, TunnelType.NAIVE_SSH -> tcp(profile.domain, profile.naivePort)
            TunnelType.SOCKS5 -> tcp(profile.domain, profile.socks5ServerPort)
            TunnelType.DOH -> {
                val host = try {
                    java.net.URL(profile.dohUrl).host
                } catch (_: Exception) {
                    ""
                }
                tcp(host, 443)
            }
            TunnelType.VLESS -> tcp(
                profile.cdnIp.ifBlank { profile.domain },
                if (profile.cdnPort > 0) profile.cdnPort else 443
            )
            TunnelType.TROJAN, TunnelType.SHADOWSOCKS, TunnelType.SHADOWSOCKSR,
            TunnelType.VMESS, TunnelType.SNELL, TunnelType.VLESS_REALITY ->
                tcp(profile.domain, if (profile.cdnPort > 0) profile.cdnPort else 443)
            else -> if (profile.domain.isBlank()) PingPlan.Invalid("No host") else PingPlan.Unmeasurable
        }
    }
}
