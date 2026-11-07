package app.slipnet.util

/**
 * Result of parsing free text into resolvers.
 *
 * @property valid normalized `host:port`, de-duplicated, input order kept
 * @property invalid raw tokens that were rejected
 * @property duplicates tokens repeated inside the input itself
 */
data class ParsedResolvers(
    val valid: List<String>,
    val invalid: List<String>,
    val duplicates: Int
)

/**
 * Parses pasted text or the contents of a `.txt` file into resolver entries.
 *
 * Accepted: one or more entries per line, separated by whitespace, comma or semicolon.
 * `#` and `//` start a comment. Entries are `ip`, `ip:port`, `host` or `host:port`;
 * a missing port becomes [defaultPort]. IPv6 is rejected (the tunnel stack has no IPv6 resolvers).
 * Tokens in parentheses, such as `(23ms)` from scanner exports, are ignored.
 */
object ResolverListParser {
    const val DEFAULT_PORT = 53

    private val SEPARATORS = Regex("[\\s,;]+")

    fun parse(text: String, defaultPort: Int = DEFAULT_PORT): ParsedResolvers {
        val valid = LinkedHashSet<String>()
        val invalid = ArrayList<String>()
        var duplicates = 0
        // Windows Notepad saves UTF-8 files with a BOM; it would glue itself to the first address.
        for (rawLine in text.removePrefix("\uFEFF").lineSequence()) {
            for (rawToken in stripComment(rawLine).split(SEPARATORS)) {
                val token = rawToken.trim().trim('"', '\'')
                if (token.isEmpty() || token.startsWith("(")) continue
                val normalized = normalize(token, defaultPort)
                when {
                    normalized == null -> invalid += token
                    !valid.add(normalized) -> duplicates++
                }
            }
        }
        return ParsedResolvers(valid.toList(), invalid, duplicates)
    }

    /** Splits the profile's stored resolver string (comma / newline separated) without altering entries. */
    fun splitStored(stored: String): List<String> =
        stored.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** `host[:port]` -> lower-case `host:port`, or null when it is not a usable resolver. */
    fun normalize(token: String, defaultPort: Int = DEFAULT_PORT): String? {
        val t = token.trim()
        if (t.isEmpty() || t.startsWith("[")) return null
        val colons = t.count { it == ':' }
        if (colons > 1) return null
        val host: String
        val port: Int
        if (colons == 1) {
            host = t.substringBefore(':')
            port = t.substringAfter(':').toIntOrNull() ?: return null
        } else {
            host = t
            port = defaultPort
        }
        if (port !in 1..65535) return null
        if (!isValidHost(host)) return null
        return "${host.lowercase()}:$port"
    }

    private fun stripComment(line: String): String {
        var cut = line.length
        val hash = line.indexOf('#')
        if (hash >= 0) cut = minOf(cut, hash)
        val slashes = line.indexOf("//")
        if (slashes >= 0) cut = minOf(cut, slashes)
        return line.substring(0, cut)
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.all { it.isDigit() || it == '.' }) return isValidIPv4(host)
        if (!host.contains('.') || host.length > 253) return false
        return host.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first().isAsciiAlnum() &&
                label.last().isAsciiAlnum() &&
                label.all { it.isAsciiAlnum() || it == '-' }
        }
    }

    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                (p == "0" || !p.startsWith("0")) && p.toInt() in 0..255
        }
    }

    private fun Char.isAsciiAlnum(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

/**
 * Result of appending entries to a bounded list.
 *
 * @property merged resulting list (existing entries first, kept verbatim)
 * @property appended incoming entries that were actually added, in input order
 * @property duplicates incoming entries already present
 * @property overLimit incoming entries not added because the list was full
 */
data class MergeResult(
    val merged: List<String>,
    val appended: List<String>,
    val duplicates: Int,
    val overLimit: List<String>
) {
    val added: Int get() = appended.size
}

object ResolverMerge {
    /**
     * Appends [incoming] to [existing] without duplicates, up to [limit] entries.
     * Entries are compared by normalized `host:port`, so `8.8.8.8` equals `8.8.8.8:53`.
     * Existing entries are never reordered or truncated, even if the list is already over [limit].
     */
    fun merge(
        existing: List<String>,
        incoming: List<String>,
        limit: Int,
        defaultPort: Int = ResolverListParser.DEFAULT_PORT
    ): MergeResult {
        val seen = HashSet<String>()
        val out = ArrayList<String>(existing.size + incoming.size)
        for (e in existing) if (seen.add(key(e, defaultPort))) out += e
        val appended = ArrayList<String>()
        var dups = 0
        val over = ArrayList<String>()
        for (i in incoming) {
            val k = key(i, defaultPort)
            when {
                k in seen -> dups++
                out.size >= limit -> over += i
                else -> {
                    seen += k
                    out += i
                    appended += i
                }
            }
        }
        return MergeResult(out, appended, dups, over)
    }

    /** Comparison key of an entry: normalized `host:port`, or the lower-cased raw text if unparsable. */
    fun key(entry: String, defaultPort: Int = ResolverListParser.DEFAULT_PORT): String =
        ResolverListParser.normalize(entry, defaultPort) ?: entry.trim().lowercase()

    /** Removes [entry] (compared by [key]) from [list]. */
    fun remove(list: List<String>, entry: String, defaultPort: Int = ResolverListParser.DEFAULT_PORT): List<String> {
        val k = key(entry, defaultPort)
        return list.filterNot { key(it, defaultPort) == k }
    }
}

/**
 * Edits for the Standby pool, which is stored as free text (one or more comma-separated entries
 * per line, `#` lines are comments). Every edit leaves comments and unrelated entries untouched.
 */
object ResolverPoolText {
    /** All entries of [text] in file order; comment lines are skipped. */
    fun entries(text: String): List<String> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { line -> line.split(',').map { it.trim() }.filter { it.isNotEmpty() } }

    /** Appends [entries], one per line, after everything already in [text]. */
    fun append(text: String, entries: List<String>): String {
        if (entries.isEmpty()) return text
        val base = text.trimEnd('\n', '\r', ' ', '\t')
        return (if (base.isEmpty()) "" else base + "\n") + entries.joinToString("\n")
    }

    /** Removes every occurrence of [entry] (compared by [ResolverMerge.key]); a line left empty is dropped. */
    fun remove(text: String, entry: String, defaultPort: Int = ResolverListParser.DEFAULT_PORT): String {
        val target = ResolverMerge.key(entry, defaultPort)
        val out = ArrayList<String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) {
                out += line
                continue
            }
            val kept = t.split(',').map { it.trim() }
                .filter { it.isNotEmpty() && ResolverMerge.key(it, defaultPort) != target }
            if (kept.isNotEmpty()) out += kept.joinToString(", ")
        }
        return out.joinToString("\n")
    }
}
