package app.slipnet.domain.model

/**
 * Aether / PattNG protocol & transport specifications.
 * Supports WARP, MASQUE (HTTP/3), WARP-in-WARP (Gool), WireGuard over MASQUE, and censorship evasion obfs.
 */
enum class AetherProtocol(val type: String, val displayName: String) {
    MASQUE("masque", "MASQUE (HTTP/3 UDP)"),
    WIREGUARD("wg", "WireGuard (WARP)"),
    GOOL("gool", "WARP-in-WARP (Double Hop)"),
    MIM("mim", "MASQUE-in-MASQUE"),
    WG_OVER_MASQUE("wg-over-masque", "WireGuard over MASQUE");

    val overMasque: Boolean get() = this == MASQUE || this == MIM || this == WG_OVER_MASQUE
    val twoHops: Boolean get() = this == GOOL || this == MIM || this == WG_OVER_MASQUE

    companion object {
        fun fromString(type: String?) = entries.find { it.type.equals(type, ignoreCase = true) } ?: WIREGUARD
    }
}

enum class AetherTransport(val type: String, val displayName: String) {
    HTTP3("h3", "HTTP/3 (QUIC)"),
    HTTP2("h2", "HTTP/2 (TCP)");

    companion object {
        fun fromString(type: String?) = entries.find { it.type.equals(type, ignoreCase = true) } ?: HTTP3
    }
}

enum class AetherScanMode(val type: String, val displayName: String) {
    TURBO("turbo", "Turbo (Fastest IP Scan)"),
    BALANCED("balanced", "Balanced"),
    THOROUGH("thorough", "Thorough (Deep Packet Scan)"),
    VERIFIED("verified", "Verified (Stealth Mode)"),
    IRONCLAD("ironclad", "Ironclad (Anti-DPI Resilient)");

    companion object {
        fun fromString(type: String?) = entries.find { it.type.equals(type, ignoreCase = true) } ?: BALANCED
    }
}

enum class AetherObfuscation(val type: String, val displayName: String) {
    AUTO("auto", "Auto (Core Decides)"),
    OFF("off", "Off (Raw WireGuard/QUIC)"),
    LIGHT("light", "Light Padding"),
    FIREWALL("firewall", "Firewall Bypass (MASQUE)"),
    BALANCED("balanced", "Balanced Scramble"),
    GFW("gfw", "GFW / Deep DPI Bypass"),
    AGGRESSIVE("aggressive", "Aggressive Entropy Shuffling");

    companion object {
        fun fromString(type: String?) = entries.find { it.type.equals(type, ignoreCase = true) } ?: AUTO
    }
}
