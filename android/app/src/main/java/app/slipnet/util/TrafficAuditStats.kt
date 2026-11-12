package app.slipnet.util

import org.json.JSONObject

data class TrafficAuditStats(
    val pastHourBytes: Long = 0L,
    val todayBytes: Long = 0L,
    val thisWeekBytes: Long = 0L,
    val thisMonthBytes: Long = 0L,
    val allTimeBytes: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("hour", pastHourBytes)
        o.put("today", todayBytes)
        o.put("week", thisWeekBytes)
        o.put("month", thisMonthBytes)
        o.put("all", allTimeBytes)
        o.put("time", lastUpdated)
        return o.toString()
    }

    companion object {
        fun fromJson(str: String): TrafficAuditStats {
            if (str.isBlank()) return TrafficAuditStats()
            return try {
                val o = JSONObject(str)
                TrafficAuditStats(
                    pastHourBytes = o.optLong("hour", 0L),
                    todayBytes = o.optLong("today", 0L),
                    thisWeekBytes = o.optLong("week", 0L),
                    thisMonthBytes = o.optLong("month", 0L),
                    allTimeBytes = o.optLong("all", 0L),
                    lastUpdated = o.optLong("time", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                TrafficAuditStats()
            }
        }
    }
}
