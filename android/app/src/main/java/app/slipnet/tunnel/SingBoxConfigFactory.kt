package app.slipnet.tunnel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds sing-box configurations: OpenVPN (.ovpn), TUIC, ShadowTLS, AnyTLS, Hysteria(1/2),
 * and passes complete sing-box JSON through untouched. The inbound is replaced at start time
 * by [SingBoxBridge.buildConfig].
 */
object SingBoxConfigFactory {

    private fun wrapOutbound(ob: JSONObject): String = JSONObject()
        .put("log", JSONObject().put("level", "warn"))
        .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local"))))
        .put("outbounds", JSONArray().put(ob.put("tag", "proxy")).put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("route", JSONObject().put("final", "proxy").put("default_domain_resolver", "local"))
        .toString()

    fun wrapPublic(ob: JSONObject): String = wrapOutbound(ob)

    private fun tls(sni: String, p: Map<String, String>): JSONObject {
        val t = JSONObject().put("enabled", true).put("server_name", sni)
        p["alpn"]?.takeIf { it.isNotBlank() }?.let { t.put("alpn", JSONArray(it.split(',').map { a -> a.trim() })) }
        if (p["allowinsecure"] in listOf("1", "true") || p["insecure"] in listOf("1", "true")) t.put("insecure", true)
        return t
    }

    fun tuic(uuid: String, password: String, host: String, port: Int, p: Map<String, String>): String {
        val tlsObj = tls(p["sni"] ?: host, p)
        // TUIC requires ALPN h3; default if not set by the user
        if (!tlsObj.has("alpn")) tlsObj.put("alpn", JSONArray(listOf("h3")))
        return wrapOutbound(JSONObject().put("type", "tuic").put("server", host).put("server_port", port)
            .put("uuid", uuid).put("password", password)
            .put("congestion_control", p["congestion_control"] ?: p["congestion"] ?: "bbr")
            .put("udp_relay_mode", p["udp_relay_mode"] ?: "native")
            .put("tls", tlsObj))
    }

    fun anytls(password: String, host: String, port: Int, p: Map<String, String>): String =
        wrapOutbound(JSONObject().put("type", "anytls").put("server", host).put("server_port", port)
            .put("password", password).put("tls", tls(p["sni"] ?: host, p)))

    fun hysteria2(password: String, host: String, port: Int, p: Map<String, String>): String {
        val ob = JSONObject().put("type", "hysteria2").put("server", host).put("server_port", port)
            .put("password", password).put("tls", tls(p["sni"] ?: host, p))
        p["obfs"]?.takeIf { it.isNotBlank() }?.let {
            ob.put("obfs", JSONObject().put("type", it).put("password", p["obfs-password"] ?: ""))
        }
        return wrapOutbound(ob)
    }

    /** ShadowTLS v3 in front of a Shadowsocks outbound (the common deployment). */
    fun shadowTls(ssMethod: String, ssPassword: String, tlsPassword: String, host: String, port: Int, sni: String): String {
        val ss = JSONObject().put("type", "shadowsocks").put("tag", "proxy").put("method", ssMethod)
            .put("password", ssPassword).put("detour", "shadowtls-out")
        val st = JSONObject().put("type", "shadowtls").put("tag", "shadowtls-out").put("server", host).put("server_port", port)
            .put("version", 3).put("password", tlsPassword).put("tls", JSONObject().put("enabled", true).put("server_name", sni))
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local"))))
            .put("outbounds", JSONArray().put(ss).put(st).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "proxy").put("default_domain_resolver", "local"))
            .toString()
    }

    // ── OpenVPN (.ovpn) ─────────────────────────────────────────────

    private fun inline(text: String, tag: String): String? =
        Regex("<$tag>\\s*(.*?)\\s*</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()

    /** Converts an .ovpn file into a sing-box openvpn-client endpoint. Unsupported directives are ignored. */
    fun fromOvpn(ovpn: String, username: String = "", password: String = ""): String {
        val text = ovpn.replace("\r", "")
        val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
        // Strip inline blocks before reading plain directives.
        val noBlocks = Regex("<([a-z0-9-]+)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        val dirs = noBlocks.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
        fun arg(name: String): List<String>? = dirs.firstOrNull { it == name || it.startsWith("$name ") || it.startsWith("$name\t") }
            ?.split(Regex("\\s+"))?.drop(1)
        fun has(name: String) = dirs.any { it == name || it.startsWith("$name ") || it.startsWith("$name\t") }

        val remotes = dirs.filter { it.startsWith("remote ") || it.startsWith("remote\t") }.map { it.split(Regex("\\s+")) }
        require(remotes.isNotEmpty()) { "No 'remote' line in the .ovpn file" }
        val proto = (arg("proto")?.firstOrNull() ?: "udp").lowercase()
        val network = if (proto.startsWith("tcp")) "tcp" else "udp"

        val ep = JSONObject().put("type", "openvpn-client").put("tag", "proxy").put("mode", "tls")
        val servers = JSONArray()
        for (r in remotes) {
            val s = JSONObject().put("server", r[1]).put("server_port", r.getOrNull(2)?.toIntOrNull() ?: 1194)
            r.getOrNull(3)?.let { pr -> s.put("network", if (pr.startsWith("tcp")) "tcp" else "udp") }
            servers.put(s)
        }
        if (servers.length() == 1) {
            val s0 = servers.getJSONObject(0)
            ep.put("server", s0.getString("server")).put("server_port", s0.getInt("server_port"))
        } else {
            ep.put("servers", servers)
        }
        ep.put("network", network)
        if (has("remote-random")) ep.put("remote_random", true)

        val t = JSONObject()
        inline(text, "ca")?.let { t.put("certificate", JSONArray(listOf(it))) }
        inline(text, "cert")?.let { t.put("client_certificate", JSONArray(listOf(it))) }
        inline(text, "key")?.let { t.put("client_key", JSONArray(listOf(it))) }
        val wrapTypes = listOf("tls-crypt-v2" to "tls_crypt_v2", "tls-crypt" to "tls_crypt", "tls-auth" to "tls_auth")
        for ((tag, type) in wrapTypes) {
            val key = inline(text, tag) ?: continue
            val cw = JSONObject().put("type", type).put("key", JSONArray(listOf(key)))
            arg("key-direction")?.firstOrNull()?.let { cw.put("direction", if (it == "1") "client" else "server") }
            t.put("control_wrap", cw); break
        }
        arg("verify-x509-name")?.firstOrNull()?.let { t.put("server_name", it) }
        if (arg("remote-cert-tls")?.firstOrNull() == "server") t.put("remote_certificate_tls", "server")
        if (arg("tls-version-min")?.firstOrNull() != null) t.put("version_min", arg("tls-version-min")!!.first())
        if (t.length() > 0) ep.put("tls", t)

        // Data channel
        val dataCiphers = arg("data-ciphers")?.firstOrNull()?.split(':')
        if (dataCiphers != null) ep.put("data_ciphers", JSONArray(dataCiphers))
        arg("data-ciphers-fallback")?.firstOrNull()?.let { ep.put("data_ciphers_fallback", it) }
        // Legacy 'cipher' is only valid in static-key mode in sing-box; in TLS mode it becomes the fallback.
        if (dataCiphers == null) arg("cipher")?.firstOrNull()?.let { if (ep.optString("data_ciphers_fallback").isBlank()) ep.put("data_ciphers_fallback", it) }
        arg("auth")?.firstOrNull()?.let { ep.put("auth", it) }
        if (has("comp-lzo")) ep.put("compression_lzo", arg("comp-lzo")?.firstOrNull() ?: "adaptive")
        arg("compress")?.let { ep.put("compression", it.firstOrNull() ?: "stub-v2") }
        arg("tun-mtu")?.firstOrNull()?.toIntOrNull()?.let { ep.put("mtu", it) }
        arg("fragment")?.firstOrNull()?.toIntOrNull()?.let { ep.put("fragment", it) }
        arg("mssfix")?.firstOrNull()?.toIntOrNull()?.let { ep.put("mss_fix", it) }
        arg("reneg-sec")?.firstOrNull()?.toLongOrNull()?.let { ep.put("renegotiate_interval", "${it}s") }
        arg("auth-retry")?.firstOrNull()?.let { ep.put("auth_retry", it) }
        if (has("redirect-gateway")) ep.put("redirect_gateway", true)

        // auth-user-pass: inline block, file reference is not readable here, or the credentials we were given.
        var user = username; var pass = password
        inline(text, "auth-user-pass")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.let {
            if (it.size >= 2) { user = it[0]; pass = it[1] }
        }
        if (user.isNotBlank()) ep.put("username", user).put("password", pass)

        // Tunnel addressing comes from the server's PUSH reply; pull is the default.
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local"))))
            .put("endpoints", JSONArray().put(ep))
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "proxy").put("default_domain_resolver", "local"))
            .toString()
    }

    /** Real protocol name shown in parentheses after "Custom". */
    fun protocolOf(json: String): String = try {
        val r = JSONObject(json)
        val eps = r.optJSONArray("endpoints")
        var found: String? = null
        if (eps != null) for (i in 0 until eps.length()) {
            val t = eps.getJSONObject(i).optString("type")
            if (t.isNotBlank()) { found = t.removeSuffix("-client"); break }
        }
        if (found == null) {
            val obs = r.optJSONArray("outbounds")
            if (obs != null) for (i in 0 until obs.length()) {
                val t = obs.getJSONObject(i).optString("type")
                if (t !in listOf("direct", "block", "dns", "selector", "urltest", "shadowtls", "")) { found = t; break }
            }
        }
        found ?: "sing-box"
    } catch (_: Exception) { "sing-box" }
}
