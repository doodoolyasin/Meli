package app.slipnet.domain.model

data class TrafficStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val rttMs: Long = 0,
    val uploadSpeed: Long = 0,
    val downloadSpeed: Long = 0
) {
    val totalBytes: Long
        get() = bytesSent + bytesReceived

    val totalPackets: Long
        get() = packetsSent + packetsReceived

    val totalSpeed: Long
        get() = uploadSpeed + downloadSpeed

    fun formatBytesSent(): String = formatBytes(bytesSent)
    fun formatBytesReceived(): String = formatBytes(bytesReceived)
    fun formatTotalBytes(): String = formatBytes(totalBytes)
    fun formatUploadSpeed(): String = formatSpeed(uploadSpeed)
    fun formatDownloadSpeed(): String = formatSpeed(downloadSpeed)
    fun formatTotalSpeed(): String = formatSpeed(totalSpeed)

    companion object {
        val EMPTY = TrafficStats()

        fun formatBytes(bytes: Long): String {
            val safeBytes = bytes.coerceAtLeast(0L)
            return when {
                safeBytes < 1024 -> "$safeBytes B"
                safeBytes < 1024 * 1024 -> String.format("%.1f KB", safeBytes / 1024.0)
                safeBytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", safeBytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", safeBytes / (1024.0 * 1024.0 * 1024.0))
            }
        }

        fun formatSpeed(bytesPerSecond: Long): String {
            return "${formatBytes(bytesPerSecond)}/s"
        }
    }
}
