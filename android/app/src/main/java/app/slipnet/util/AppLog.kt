package app.slipnet.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class LogEntry(val id: Long, val raw: String, val level: Char)

/**
 * High-capacity in-memory diagnostic log buffer for Aryana Core.
 *
 * Forwards to Android Logcat AND maintains a rich 3000-entry ring buffer
 * accessible via the in-app Diagnostic Inspector.
 *
 * Sensitive credentials (passwords, private keys, auth tokens) are automatically
 * sanitized without discarding crucial error context, IPs, ports, or protocols.
 */
object AppLog {
    private const val MAX_LINES = 3000
    private val nextId = AtomicLong(0)
    private val buffer = ArrayDeque<LogEntry>()

    /** When true, credentials and private keys are scrubbed from the in-app log buffer. */
    @Volatile var redactSensitive = true

    // Current connection context for instant diagnostic reporting
    @Volatile var currentProfileName: String = "None"
    @Volatile var currentTunnelType: String = "None"
    @Volatile var lastErrorDetail: String = ""

    // Lazy snapshot — rebuilt when the debug sheet is open (observerCount > 0).
    private val _lines = MutableStateFlow<List<LogEntry>>(emptyList())
    val lines: StateFlow<List<LogEntry>> = _lines.asStateFlow()

    // Real-time active resolvers and packet telemetry
    private val _activeResolvers = MutableStateFlow<List<String>>(emptyList())
    val activeResolvers: StateFlow<List<String>> = _activeResolvers.asStateFlow()

    private val packetBuffer = ArrayDeque<PacketLogItem>()
    private val _packetLogs = MutableStateFlow<List<PacketLogItem>>(emptyList())
    val packetLogs: StateFlow<List<PacketLogItem>> = _packetLogs.asStateFlow()

    val txCount = AtomicLong(0)
    val rxCount = AtomicLong(0)
    val dropCount = AtomicLong(0)

    fun setActiveResolvers(list: List<String>) {
        _activeResolvers.value = list
    }

    fun recordPacket(
        direction: String,
        protocol: String,
        target: String,
        size: Int,
        latencyMs: Long = 0,
        status: String = "SUCCESS"
    ) {
        if (direction == "TX") txCount.incrementAndGet() else rxCount.incrementAndGet()
        if (status != "SUCCESS") dropCount.incrementAndGet()

        val id = nextId.getAndIncrement()
        val ts = dateFormat.get()?.format(Date()) ?: ""
        val item = PacketLogItem(id, ts, direction, protocol, target, size, latencyMs, status)
        synchronized(packetBuffer) {
            packetBuffer.addLast(item)
            while (packetBuffer.size > 500) packetBuffer.removeFirst()
            _packetLogs.value = ArrayList(packetBuffer)
        }
    }

    @Volatile var observerCount = 0
        private set

    private val dirty = AtomicBoolean(false)

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Sanitizes sensitive credentials (passwords, private keys, auth tokens)
     * while preserving IPs, ports, error messages, and operational details.
     */
    fun sanitizeSecrets(msg: String): String {
        var s = msg
        // Redact passwords & tokens: password=xyz, token: abc
        s = s.replace(Regex("""(?i)(password|passwd|pwd|secret|token)\s*[:=]\s*[^\s,;]+"""), "$1=[REDACTED]")
        // Redact full private keys
        s = s.replace(Regex("""-----BEGIN [A-Z ]+PRIVATE KEY-----[\s\S]*?-----END [A-Z ]+PRIVATE KEY-----"""), "[REDACTED_PRIVATE_KEY]")
        // Mask 64-char hex private keys, retaining first 4 and last 4
        s = s.replace(Regex("""\b([a-fA-F0-9]{4})[a-fA-F0-9]{24,56}([a-fA-F0-9]{4})\b""")) { m ->
            "${m.groupValues[1]}...${m.groupValues[2]}"
        }
        return s
    }

    private fun append(level: Char, tag: String, msg: String) {
        val safeMsg = if (redactSensitive) sanitizeSecrets(msg) else msg
        if (level == 'E') {
            lastErrorDetail = safeMsg
        }

        val id = nextId.getAndIncrement()
        val ts = dateFormat.get()!!.format(Date())
        val entry = LogEntry(id, "$ts $level/$tag: $safeMsg", level)

        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        if (observerCount > 0) {
            dirty.set(true)
        }
    }

    fun flushIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            synchronized(buffer) {
                _lines.value = ArrayList(buffer)
            }
        }
    }

    fun addObserver() {
        observerCount++
        synchronized(buffer) {
            _lines.value = ArrayList(buffer)
        }
    }

    fun removeObserver() {
        observerCount = (observerCount - 1).coerceAtLeast(0)
    }

    fun v(tag: String, msg: String): Int {
        append('V', tag, msg)
        return android.util.Log.v(tag, msg)
    }

    fun d(tag: String, msg: String): Int {
        append('D', tag, msg)
        return android.util.Log.d(tag, msg)
    }

    fun i(tag: String, msg: String): Int {
        append('I', tag, msg)
        return android.util.Log.i(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        append('W', tag, msg)
        return android.util.Log.w(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable?): Int {
        append('W', tag, if (tr != null) "$msg\n${tr.stackTraceToString()}" else msg)
        return android.util.Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String): Int {
        append('E', tag, msg)
        return android.util.Log.e(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?): Int {
        append('E', tag, if (tr != null) "$msg\n${tr.stackTraceToString()}" else msg)
        return android.util.Log.e(tag, msg, tr)
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _lines.value = emptyList()
        }
        synchronized(packetBuffer) {
            packetBuffer.clear()
            _packetLogs.value = emptyList()
        }
        txCount.set(0)
        rxCount.set(0)
        dropCount.set(0)
        lastErrorDetail = ""
    }
}

data class PacketLogItem(
    val id: Long,
    val timestamp: String,
    val direction: String,
    val protocol: String,
    val target: String,
    val size: Int,
    val latencyMs: Long,
    val status: String
)
