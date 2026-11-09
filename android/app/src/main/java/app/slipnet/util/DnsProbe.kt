package app.slipnet.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import kotlin.coroutines.resume
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Reachability probe for DNS-tunnel profiles ("ping").
 *
 * A DNS tunnel works when a recursive resolver can relay a query for the profile's
 * domain to the tunnel server and bring the answer back. That chain is tested directly:
 * a TXT query for `<random>.<domain>` (and for the bare domain) is sent through each
 * resolver, and the reply is classified by what produced it:
 *
 *  - NOERROR / NXDOMAIN **without** an SOA in the authority section: a tunnel server
 *    answered (tunnel servers do not carry a zone, so they never send an SOA) = REACHED.
 *  - NXDOMAIN / NOERROR **with** an SOA: an ordinary authoritative server answered,
 *    i.e. the name is not delegated to a tunnel server = NOT_DELEGATED.
 *  - SERVFAIL: the resolver could not reach the delegated server.
 *  - REFUSED: the resolver does not serve this client.
 *  - no reply: TIMEOUT / UNREACHABLE.
 *
 * Pure JVM (no Android APIs) so it can be exercised from a plain JVM harness.
 */
object DnsProbe {

    enum class Transport { UDP, TCP, DOT, DOH }

    enum class Verdict { REACHED, NOT_DELEGATED, SERVFAIL, REFUSED, REJECTED, TIMEOUT, UNREACHABLE }

    data class Outcome(val verdict: Verdict, val rttMs: Long) {
        val reached: Boolean get() = verdict == Verdict.REACHED

        /** Short text that fits a list-row badge. */
        val label: String
            get() = when (verdict) {
                Verdict.REACHED -> "OK"
                Verdict.NOT_DELEGATED -> "No tunnel"
                Verdict.SERVFAIL -> "SERVFAIL"
                Verdict.REFUSED -> "Refused"
                Verdict.REJECTED -> "Bad reply"
                Verdict.TIMEOUT -> "Timeout"
                Verdict.UNREACHABLE -> "Unreachable"
            }
    }

    data class Target(val host: String, val port: Int)

    /** [best] is the lowest-RTT reached outcome, or the most informative failure. */
    data class ProfileOutcome(val best: Outcome, val reachedCount: Int, val total: Int) {
        val reached: Boolean get() = reachedCount > 0
    }

    const val QTYPE_NS = 2
    const val QTYPE_TXT = 16
    private const val DEFAULT_TIMEOUT_MS = 3000

    private val rng = SecureRandom()
    private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?$")

    // ── Wire format ─────────────────────────────────────────────────────

    internal fun randomLabel(len: Int = 10): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(len) { repeat(len) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    /** Lower-cased ASCII form of [domain] without scheme/port/trailing dot, or null if unusable. */
    internal fun normalizeDomain(domain: String): String? {
        var d = domain.trim().trim('.')
        if (d.isEmpty()) return null
        d = d.substringAfter("://").substringBefore('/').substringBefore(':')
        if (d.isEmpty()) return null
        return try {
            val ascii = IDN.toASCII(d).lowercase()
            val labels = ascii.split('.')
            val ok = labels.all { it.length in 1..63 && LABEL.matches(it) } && ascii.length <= 253
            if (ok) ascii else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildQuery(id: Int, name: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun w16(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        w16(id)
        w16(0x0100) // RD
        w16(1)      // QDCOUNT
        w16(0)
        w16(0)
        w16(1)      // ARCOUNT: EDNS0 OPT
        if (name != ".") {
            for (label in name.trim('.').split('.')) {
                val b = label.toByteArray(Charsets.US_ASCII)
                out.write(b.size)
                out.write(b)
            }
        }
        out.write(0)
        w16(qtype)
        w16(1) // IN
        // OPT pseudo-RR: root name, TYPE 41, UDP payload 1232, ext-rcode/version/flags 0, RDLEN 0
        out.write(0)
        w16(41)
        w16(1232)
        w16(0)
        w16(0)
        w16(0)
        return out.toByteArray()
    }

    internal data class Reply(val id: Int, val rcode: Int, val truncated: Boolean, val hasSoa: Boolean, val soaOwnerLabels: Int = -1)

    internal fun parseReply(buf: ByteArray, len: Int): Reply? {
        if (len < 12) return null
        fun u16(o: Int) = ((buf[o].toInt() and 0xFF) shl 8) or (buf[o + 1].toInt() and 0xFF)
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null // not a response
        val qd = u16(4)
        val an = u16(6)
        val ns = u16(8)
        var off = 12

        fun skipName(): Boolean {
            while (true) {
                if (off >= len) return false
                val b = buf[off].toInt() and 0xFF
                when {
                    b == 0 -> { off += 1; return true }
                    b and 0xC0 == 0xC0 -> { off += 2; return off <= len }
                    b and 0xC0 != 0 -> return false
                    else -> off += 1 + b
                }
            }
        }

        repeat(qd) {
            if (!skipName()) return null
            off += 4
        }
        var soa = false
        var soaLabels = -1
        fun countLabels(start: Int): Int {
            var o = start; var n = 0; var hops = 0
            while (o < len && hops < 16) {
                val b = buf[o].toInt() and 0xFF
                when {
                    b == 0 -> return n
                    b and 0xC0 == 0xC0 -> { if (o + 1 >= len) return n; o = ((b and 0x3F) shl 8) or (buf[o + 1].toInt() and 0xFF); hops++ }
                    else -> { n++; o += 1 + b }
                }
            }
            return n
        }
        for (i in 0 until (an + ns)) {
            val nameStart = off
            if (!skipName()) return null
            if (off + 10 > len) return null
            val type = u16(off)
            val rdlen = u16(off + 8)
            if (i >= an && type == 6) { soa = true; soaLabels = countLabels(nameStart) }
            off += 10 + rdlen
            if (off > len) return null
        }
        return Reply(u16(0), flags and 0x000F, flags and 0x0200 != 0, soa, soaLabels)
    }

    /**
     * [zoneLabels]: label count of the tunnel zone. A tunnel server answers junk names with
     * its own SOA (owner = the zone), which proves delegation works. Only an SOA owned by
     * a strict ancestor of the zone (the parent's authority) means "not delegated".
     */
    internal fun classify(r: Reply, zoneLabels: Int = 0): Verdict = when (r.rcode) {
        0, 3 -> if (r.hasSoa && (zoneLabels <= 0 || r.soaOwnerLabels < 0 || r.soaOwnerLabels < zoneLabels)) Verdict.NOT_DELEGATED else Verdict.REACHED
        2 -> Verdict.SERVFAIL
        5 -> Verdict.REFUSED
        else -> Verdict.REJECTED
    }

    /** Resolver liveness only: any well-formed NOERROR/NXDOMAIN reply counts. */
    internal fun classifyLiveness(r: Reply): Verdict = when (r.rcode) {
        0, 3 -> Verdict.REACHED
        2 -> Verdict.SERVFAIL
        5 -> Verdict.REFUSED
        else -> Verdict.REJECTED
    }

    // ── Transports (blocking) ───────────────────────────────────────────

    private class Raw(val data: ByteArray, val len: Int, val rttMs: Long)

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun exchangeUdp(addr: InetAddress, port: Int, id: Int, query: ByteArray, timeoutMs: Int): Raw? {
        DatagramSocket().use { s ->
            s.connect(addr, port) // drop datagrams from any other source
            val perAttempt = maxOf(timeoutMs / 2, 500)
            val first = System.nanoTime()
            val buf = ByteArray(4096)
            repeat(2) {
                s.send(DatagramPacket(query, query.size))
                val end = System.nanoTime() + perAttempt * 1_000_000L
                while (true) {
                    val left = ((end - System.nanoTime()) / 1_000_000L).toInt()
                    if (left <= 0) break
                    s.soTimeout = left
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    if (p.length >= 12 && u16(buf, 0) == id) {
                        return Raw(buf.copyOf(p.length), p.length, (System.nanoTime() - first) / 1_000_000L)
                    }
                }
            }
        }
        return null
    }

    private fun exchangeStream(addr: InetAddress, port: Int, id: Int, query: ByteArray, timeoutMs: Int, tls: Boolean): Raw? {
        val plain = Socket()
        plain.connect(InetSocketAddress(addr, port), timeoutMs)
        plain.soTimeout = timeoutMs
        val sock: Socket = if (tls) {
            // Reachability probe only: nothing secret is sent, so the certificate is not pinned.
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, addr.hostAddress, port, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            ssl
        } else {
            plain
        }
        sock.use { s ->
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())
            val t0 = System.nanoTime()
            out.writeShort(query.size)
            out.write(query)
            out.flush()
            val n = inp.readUnsignedShort()
            if (n < 12) return null
            val data = ByteArray(n)
            inp.readFully(data)
            if (u16(data, 0) != id) return null
            return Raw(data, n, (System.nanoTime() - t0) / 1_000_000L)
        }
    }

    private fun exchangeDoh(url: String, id: Int, query: ByteArray, timeoutMs: Int): Raw? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            val t0 = System.nanoTime()
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode != 200) return null
            val data = conn.inputStream.use { it.readBytes() }
            if (data.size < 12 || u16(data, 0) != id) return null
            return Raw(data, data.size, (System.nanoTime() - t0) / 1_000_000L)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * One blocking query. [liveness] selects resolver-liveness semantics instead of
     * delegation semantics.
     */
    fun queryOnce(
        addr: InetAddress,
        port: Int,
        transport: Transport,
        dohUrl: String,
        qname: String,
        qtype: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        liveness: Boolean = false,
        zoneLabels: Int = 0
    ): Outcome {
        val id = rng.nextInt(0x10000)
        val q = buildQuery(id, qname, qtype)
        val t0 = System.nanoTime()
        return try {
            val raw = when (transport) {
                Transport.UDP -> exchangeUdp(addr, port, id, q, timeoutMs)
                Transport.TCP -> exchangeStream(addr, port, id, q, timeoutMs, tls = false)
                Transport.DOT -> exchangeStream(addr, if (port == 53) 853 else port, id, q, timeoutMs, tls = true)
                Transport.DOH -> exchangeDoh(dohUrl.ifBlank { "https://dns.google/dns-query" }, id, q, timeoutMs)
            } ?: return Outcome(Verdict.TIMEOUT, (System.nanoTime() - t0) / 1_000_000L)
            val reply = parseReply(raw.data, raw.len) ?: return Outcome(Verdict.REJECTED, raw.rttMs)
            Outcome(if (liveness) classifyLiveness(reply) else classify(reply, zoneLabels), raw.rttMs)
        } catch (_: SocketTimeoutException) {
            Outcome(Verdict.TIMEOUT, (System.nanoTime() - t0) / 1_000_000L)
        } catch (_: ConnectException) {
            Outcome(Verdict.UNREACHABLE, (System.nanoTime() - t0) / 1_000_000L)
        } catch (_: Exception) {
            Outcome(Verdict.UNREACHABLE, (System.nanoTime() - t0) / 1_000_000L)
        }
    }

    // ── Orchestration ───────────────────────────────────────────────────

    private val FAILURE_RANK = listOf(
        Verdict.NOT_DELEGATED, Verdict.SERVFAIL, Verdict.REFUSED,
        Verdict.REJECTED, Verdict.TIMEOUT, Verdict.UNREACHABLE
    )

    /** Most informative failure among [outs] (an authority saying "no such zone" beats a timeout). */
    internal fun bestFailure(outs: List<Outcome>): Outcome =
        outs.minByOrNull { FAILURE_RANK.indexOf(it.verdict) } ?: Outcome(Verdict.UNREACHABLE, 0)

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * Resolves [host] without ever suspending longer than [limitMs]. The blocking lookup runs
     * on a daemon thread, so a stuck system resolver cannot hold a coroutine (or a ping
     * permit) hostage; the thread simply finishes on its own later.
     */
    suspend fun resolveBounded(host: String, limitMs: Long = 2500): InetAddress? {
        val h = host.trim()
        if (h.isEmpty()) return null
        if (IPV4.matches(h)) {
            return try { InetAddress.getByName(h) } catch (_: Exception) { null }
        }
        return withTimeoutOrNull(limitMs) {
            suspendCancellableCoroutine<InetAddress?> { cont ->
                val t = Thread({
                    val r = try { InetAddress.getByName(h) } catch (_: Exception) { null }
                    cont.resume(r)
                }, "dns-probe-resolve")
                t.isDaemon = true
                t.start()
            }
        }
    }

    /**
     * Probes [domain] through one resolver.
     *
     * Two names are asked in parallel: a random label under the domain and the bare domain
     * (some tunnel servers answer only one of them). The random label is the authority:
     *  - it proves the name is NOT a normal zone when an SOA comes back (NOT_DELEGATED), even
     *    if the bare domain happens to carry TXT data;
     *  - it is always a resolver cache miss, so its round trip is the true tunnel latency.
     * The bare domain only rescues servers that ignore unknown labels.
     */
    suspend fun probeResolver(
        target: Target,
        transport: Transport,
        dohUrl: String,
        domain: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Outcome {
        val zone = normalizeDomain(domain) ?: return Outcome(Verdict.REJECTED, 0)
        val addr: InetAddress
        val port: Int
        if (transport == Transport.DOH) {
            addr = InetAddress.getLoopbackAddress() // unused for DoH
            port = 443
        } else {
            addr = resolveBounded(target.host) ?: return Outcome(Verdict.UNREACHABLE, 0)
            port = target.port
        }
        suspend fun ask(name: String): Outcome =
            withTimeoutOrNull(timeoutMs + 1500L) {
                withContext(Dispatchers.IO) { queryOnce(addr, port, transport, dohUrl, name, QTYPE_TXT, timeoutMs, zoneLabels = zone.trim('.').split('.').size) }
            } ?: Outcome(Verdict.TIMEOUT, timeoutMs.toLong())

        val (apex, rand1) = coroutineScope {
            val a = async { ask(zone) }
            val r = async { ask("${randomLabel()}.$zone") }
            a.await() to r.await()
        }
        if (rand1.verdict == Verdict.NOT_DELEGATED) return rand1
        if (rand1.reached) {
            // Second cold sample: the first one may include the resolver's delegation lookup.
            val rand2 = ask("${randomLabel()}.$zone")
            val best = if (rand2.reached) minOf(rand1.rttMs, rand2.rttMs) else rand1.rttMs
            return Outcome(Verdict.REACHED, best.coerceAtLeast(1))
        }
        if (apex.reached) return Outcome(Verdict.REACHED, apex.rttMs.coerceAtLeast(1))
        return bestFailure(listOf(apex, rand1))
    }

    /** All [resolvers] in parallel; the profile is reachable when any resolver relays the domain. */
    suspend fun probeProfile(
        resolvers: List<Target>,
        transport: Transport,
        dohUrl: String,
        domain: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): ProfileOutcome {
        // DoH ignores the resolver list: the URL is the single endpoint.
        val targets = if (transport == Transport.DOH) listOf(Target("doh", 443)) else resolvers
        if (targets.isEmpty()) return ProfileOutcome(Outcome(Verdict.UNREACHABLE, 0), 0, 0)
        val outs = coroutineScope {
            targets.map { t -> async { probeResolver(t, transport, dohUrl, domain, timeoutMs) } }.awaitAll()
        }
        val ok = outs.filter { it.reached }
        val best = if (ok.isNotEmpty()) ok.minBy { it.rttMs } else bestFailure(outs)
        return ProfileOutcome(best, ok.size, outs.size)
    }

    /** Resolver liveness (root NS query): client-to-resolver round trip, no tunnel involved. */
    suspend fun probeLiveness(
        target: Target,
        transport: Transport,
        dohUrl: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Outcome {
        val addr: InetAddress
        val port: Int
        if (transport == Transport.DOH) {
            addr = InetAddress.getLoopbackAddress()
            port = 443
        } else {
            addr = resolveBounded(target.host) ?: return Outcome(Verdict.UNREACHABLE, 0)
            port = target.port
        }
        suspend fun ask(): Outcome =
            withTimeoutOrNull(timeoutMs + 1500L) {
                withContext(Dispatchers.IO) { queryOnce(addr, port, transport, dohUrl, ".", QTYPE_NS, timeoutMs, liveness = true) }
            } ?: Outcome(Verdict.TIMEOUT, timeoutMs.toLong())

        val a = ask()
        if (!a.reached) return a
        val b = ask()
        return Outcome(Verdict.REACHED, (if (b.reached) minOf(a.rttMs, b.rttMs) else a.rttMs).coerceAtLeast(1))
    }
}
