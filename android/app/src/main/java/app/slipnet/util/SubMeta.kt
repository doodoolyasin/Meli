package app.slipnet.util

import org.json.JSONObject

/** Per-subscription options (PattNG "Edit subscription" fields). */
data class SubMeta(
    val userAgent: String = "",
    val headersJson: String = "",
    val remarksFilter: String = "",
    val enabled: Boolean = true,
    val autoUpdate: Boolean = false,
    val intervalMinutes: Int = 1440,
    val allowInsecureHttp: Boolean = false,
    val lastUpdated: Long = 0L,
    val lastCount: Int = 0,
    val usedBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val expireEpochSec: Long = 0L
) {
    fun toJson(): String = JSONObject().apply {
        put("ua", userAgent); put("hdr", headersJson); put("flt", remarksFilter)
        put("en", enabled); put("au", autoUpdate); put("iv", intervalMinutes)
        put("http", allowInsecureHttp); put("lu", lastUpdated); put("lc", lastCount); put("ub", usedBytes); put("tb", totalBytes); put("ex", expireEpochSec)
    }.toString()

    /** Parsed header map; null when [headersJson] is non-blank but not a JSON object. */
    fun parseHeaders(): Map<String, String>? {
        if (headersJson.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(headersJson)
            o.keys().asSequence().associateWith { o.optString(it, "") }
        } catch (_: Exception) { null }
    }

    companion object {
        const val MIN_INTERVAL = 15
        fun fromJson(raw: String?): SubMeta {
            if (raw.isNullOrBlank()) return SubMeta()
            return try {
                val o = JSONObject(raw)
                SubMeta(
                    o.optString("ua"), o.optString("hdr"), o.optString("flt"),
                    o.optBoolean("en", true), o.optBoolean("au", false),
                    o.optInt("iv", 1440).coerceAtLeast(MIN_INTERVAL),
                    o.optBoolean("http", false), o.optLong("lu", 0L), o.optInt("lc", 0),
                    o.optLong("ub", -1L), o.optLong("tb", -1L), o.optLong("ex", 0L)
                )
            } catch (_: Exception) { SubMeta() }
        }
    }
}

/** The three switches of the "Update subscription" dialog. */
data class UpdateFlags(val autoTest: Boolean = false, val deleteInvalid: Boolean = false, val autoSort: Boolean = false)
