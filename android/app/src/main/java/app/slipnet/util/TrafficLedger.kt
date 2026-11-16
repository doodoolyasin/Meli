package app.slipnet.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Real, locally measured traffic, bucketed per scope so the user can compare it with what
 * a seller's panel reports (and detect traffic multipliers).
 *
 * Scopes: "all", "sub:<group>", "cfg:<profileId>".
 * Storage: 5-minute buckets for the last 48 h + daily buckets for 400 days, per scope.
 */
object TrafficLedger {
    private const val TAG = "TrafficLedger"
    private const val FILE = "traffic_ledger.json"
    private const val SLOT_MS = 5 * 60_000L
    private const val SLOT_KEEP_MS = 48 * 3_600_000L
    private const val DAY_KEEP = 400
    private const val FLUSH_MS = 30_000L
    private const val MAX_SCOPES = 300

    data class Usage(val up: Long = 0, val down: Long = 0) {
        val total get() = up + down
        operator fun plus(o: Usage) = Usage(up + o.up, down + o.down)
    }

    data class Report(
        val scope: String,
        val lastHour: Usage,
        val today: Usage,
        val last7Days: Usage,
        val last30Days: Usage,
        val thisMonth: Usage,
        val allTime: Usage
    )

    /** Snapshot of panel usage at a subscription refresh, used to estimate a multiplier. */
    data class PanelCheck(
        val group: String,
        val panelDelta: Long,
        val measuredDelta: Long,
        val ratio: Double?,
        val since: Long
    )

    private class Scope {
        val slots = sortedMapOf<Long, LongArray>()   // slotStart -> [up, down]
        val days = sortedMapOf<Int, LongArray>()     // yyyymmdd -> [up, down]
        var allUp = 0L
        var allDown = 0L
    }

    private val scopes = HashMap<String, Scope>()
    private val panelSnap = HashMap<String, Triple<Long, Long, Long>>() // group -> (panelUsed, measuredAll, time)
    private val lastCheck = HashMap<String, PanelCheck>()
    private var file: File? = null
    private var dirty = false
    private var lastFlush = 0L

    private val _version = MutableStateFlow(0L)
    /** Bumps whenever data changes, so the UI can recompute reports. */
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, FILE)
        load()
    }

    private fun dayKey(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Record measured bytes for the given profile (null = unknown profile, counted only in "all"). */
    @Synchronized
    fun record(up: Long, down: Long, profileId: Long?, group: String?) {
        if (up <= 0 && down <= 0) return
        val u = up.coerceAtLeast(0); val d = down.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val keys = buildList {
            add("all")
            if (!group.isNullOrBlank()) add("sub:$group")
            if (profileId != null && profileId > 0) add("cfg:$profileId")
        }
        for (k in keys) {
            val s = scopes.getOrPut(k) { Scope() }
            val slot = now - now % SLOT_MS
            s.slots.getOrPut(slot) { LongArray(2) }.let { it[0] += u; it[1] += d }
            s.days.getOrPut(dayKey(now)) { LongArray(2) }.let { it[0] += u; it[1] += d }
            s.allUp += u; s.allDown += d
        }
        dirty = true
        if (now - lastFlush > FLUSH_MS) flush()
    }

    @Synchronized
    fun report(scope: String): Report {
        val s = scopes[scope] ?: Scope()
        val now = System.currentTimeMillis()
        fun slotsSince(t: Long) = s.slots.tailMap(t - t % SLOT_MS).values.fold(Usage()) { a, v -> a + Usage(v[0], v[1]) }
        fun daysSince(n: Int): Usage {
            val from = dayKey(dayStart(now) - (n - 1) * 86_400_000L)
            return s.days.tailMap(from).values.fold(Usage()) { a, v -> a + Usage(v[0], v[1]) }
        }
        val monthFrom = Calendar.getInstance().let { it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + 1 }
        return Report(
            scope = scope,
            lastHour = slotsSince(now - 3_600_000L),
            today = s.days[dayKey(now)]?.let { Usage(it[0], it[1]) } ?: Usage(),
            last7Days = daysSince(7),
            last30Days = daysSince(30),
            thisMonth = s.days.tailMap(monthFrom).values.fold(Usage()) { a, v -> a + Usage(v[0], v[1]) },
            allTime = Usage(s.allUp, s.allDown)
        )
    }

    @Synchronized
    fun scopeNames(): List<String> = scopes.keys.sorted()

    /**
     * Called after each subscription refresh with the panel's "used" counter.
     * Ratio = Δpanel / Δmeasured since the previous refresh. > 1 means the seller
     * deducts more than the traffic that actually passed through this device.
     */
    @Synchronized
    fun onPanelUsage(group: String, panelUsedBytes: Long): PanelCheck? {
        if (group.isBlank() || panelUsedBytes < 0) return lastCheck[group]
        val measured = scopes["sub:$group"]?.let { it.allUp + it.allDown } ?: 0L
        val now = System.currentTimeMillis()
        val prev = panelSnap[group]
        panelSnap[group] = Triple(panelUsedBytes, measured, now)
        if (prev != null && panelUsedBytes >= prev.first) {
            val dp = panelUsedBytes - prev.first
            val dm = measured - prev.second
            // Below ~20 MB measured, rounding on the panel side dominates — no verdict.
            val ratio = if (dm >= 20L * 1024 * 1024) dp.toDouble() / dm else null
            lastCheck[group] = PanelCheck(group, dp, dm, ratio, prev.third)
        }
        dirty = true
        flush()
        return lastCheck[group]
    }

    @Synchronized
    fun panelCheck(group: String): PanelCheck? = lastCheck[group]

    @Synchronized
    fun reset(scope: String? = null) {
        if (scope == null) { scopes.clear(); panelSnap.clear(); lastCheck.clear() } else {
            scopes.remove(scope)
            if (scope.startsWith("sub:")) scope.removePrefix("sub:").let { panelSnap.remove(it); lastCheck.remove(it) }
        }
        dirty = true
        flush()
    }

    @Synchronized
    fun flush() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        lastFlush = now
        _version.value = now
        if (!dirty) return
        prune(now)
        try {
            val root = JSONObject()
            val sc = JSONObject()
            for ((k, s) in scopes) {
                sc.put(k, JSONObject().apply {
                    put("u", s.allUp); put("d", s.allDown)
                    put("s", JSONArray().apply { s.slots.forEach { (t, v) -> put(JSONArray().put(t).put(v[0]).put(v[1])) } })
                    put("y", JSONArray().apply { s.days.forEach { (t, v) -> put(JSONArray().put(t).put(v[0]).put(v[1])) } })
                })
            }
            root.put("scopes", sc)
            root.put("panel", JSONObject().apply { panelSnap.forEach { (g, t) -> put(g, JSONArray().put(t.first).put(t.second).put(t.third)) } })
            root.put("checks", JSONObject().apply {
                lastCheck.forEach { (g, c) -> put(g, JSONArray().put(c.panelDelta).put(c.measuredDelta).put(c.ratio ?: -1.0).put(c.since)) }
            })
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
            dirty = false
        } catch (e: Exception) {
            Log.w(TAG, "flush failed: ${e.message}")
        }
    }

    private fun prune(now: Long) {
        val slotCut = now - SLOT_KEEP_MS
        val dayCut = dayKey(now - DAY_KEEP * 86_400_000L)
        for (s in scopes.values) {
            s.slots.headMap(slotCut).clear()
            s.days.headMap(dayCut).clear()
        }
        if (scopes.size > MAX_SCOPES) {
            scopes.entries.filter { it.key.startsWith("cfg:") }
                .sortedBy { it.value.allUp + it.value.allDown }
                .take(scopes.size - MAX_SCOPES)
                .forEach { scopes.remove(it.key) }
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val sc = root.optJSONObject("scopes") ?: JSONObject()
            for (k in sc.keys()) {
                val o = sc.getJSONObject(k)
                val s = Scope()
                s.allUp = o.optLong("u"); s.allDown = o.optLong("d")
                o.optJSONArray("s")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { s.slots[it.getLong(0)] = longArrayOf(it.getLong(1), it.getLong(2)) } }
                o.optJSONArray("y")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { s.days[it.getInt(0)] = longArrayOf(it.getLong(1), it.getLong(2)) } }
                scopes[k] = s
            }
            root.optJSONObject("panel")?.let { p -> for (g in p.keys()) p.getJSONArray(g).let { panelSnap[g] = Triple(it.getLong(0), it.getLong(1), it.getLong(2)) } }
            root.optJSONObject("checks")?.let { p ->
                for (g in p.keys()) p.getJSONArray(g).let {
                    val r = it.getDouble(2)
                    lastCheck[g] = PanelCheck(g, it.getLong(0), it.getLong(1), if (r < 0) null else r, it.getLong(3))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }
}
