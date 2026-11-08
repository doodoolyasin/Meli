package app.slipnet.tunnel

import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import org.json.JSONArray
import org.json.JSONObject

/** Builds Xray-core configs from share-links and legacy profile fields. */
object XrayConfigFactory {

    private fun wrap(outbound: JSONObject): String {
        outbound.put("tag", "proxy")
        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("outbounds", JSONArray().put(outbound))
        }.toString()
    }

    /** vless:// share link (any transport, tls / reality / none). */
    fun fromVlessParams(uuid: String, host: String, port: Int, p: Map<String, String>): String {
        val net = when (val t = (p["type"] ?: "tcp").lowercase()) { "raw" -> "tcp"; "websocket" -> "ws"; "splithttp" -> "xhttp"; else -> t }
        val sec = (p["security"] ?: "none").lowercase()
        val user = JSONObject().put("id", uuid).put("encryption", p["encryption"] ?: "none")
        p["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val ob = JSONObject().put("protocol", "vless").put("settings", JSONObject().put("vnext",
            JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
        ob.put("streamSettings", stream(net, sec, p, host))
        return wrap(ob)
    }

    fun fromTrojanParams(password: String, host: String, port: Int, p: Map<String, String>): String {
        val net = (p["type"] ?: "tcp").lowercase().let { if (it == "raw") "tcp" else it }
        val sec = (p["security"] ?: "tls").lowercase()
        val ob = JSONObject().put("protocol", "trojan").put("settings", JSONObject().put("servers",
            JSONArray().put(JSONObject().put("address", host).put("port", port).put("password", password))))
        ob.put("streamSettings", stream(net, sec, p, host))
        return wrap(ob)
    }

    private fun stream(net: String, sec: String, p: Map<String, String>, host: String): JSONObject {
        val s = JSONObject().put("network", net).put("security", sec)
        val sni = p["sni"]?.takeIf { it.isNotBlank() } ?: p["host"]?.takeIf { it.isNotBlank() } ?: host
        val alpn = p["alpn"]?.split(',')?.filter { it.isNotBlank() }
        when (sec) {
            "tls" -> {
                val t = JSONObject().put("serverName", sni).put("fingerprint", p["fp"]?.takeIf { it.isNotBlank() } ?: "chrome")
                if (!alpn.isNullOrEmpty()) t.put("alpn", JSONArray(alpn))
                if (p["allowInsecure"] == "1" || p["insecure"] == "1") t.put("allowInsecure", true)
                s.put("tlsSettings", t)
            }
            "reality" -> s.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", p["fp"]?.takeIf { it.isNotBlank() } ?: "chrome")
                .put("publicKey", p["pbk"] ?: "")
                .put("shortId", p["sid"] ?: "")
                .put("spiderX", p["spx"] ?: ""))
        }
        val path = p["path"]?.takeIf { it.isNotBlank() } ?: "/"
        val hostHdr = p["host"]?.takeIf { it.isNotBlank() }
        when (net) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", path).apply { if (hostHdr != null) put("headers", JSONObject().put("Host", hostHdr)) })
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", p["serviceName"] ?: p["path"] ?: "").put("multiMode", p["mode"] == "multi"))
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", path).apply { if (hostHdr != null) put("host", hostHdr) })
            "xhttp" -> s.put("xhttpSettings", JSONObject().put("path", path).put("mode", p["mode"] ?: "auto").apply { if (hostHdr != null) put("host", hostHdr) })
            "kcp", "mkcp" -> s.put("network", "mkcp")
            "tcp" -> if (p["headerType"] == "http") s.put("rawSettings", JSONObject().put("header", JSONObject().put("type", "http")
                .put("request", JSONObject().put("path", JSONArray(listOf(path))).put("headers", JSONObject().put("Host", JSONArray(listOf(hostHdr ?: host)))))))
        }
        return s
    }

    /** Builds a config for legacy profile types that used to hit the broken SOCKS5 fallback. */
    fun fromProfile(profile: ServerProfile): String? {
        val host = profile.domain.ifBlank { profile.cdnIp }
        val port = if (profile.cdnPort > 0) profile.cdnPort else profile.socks5ServerPort
        if (host.isBlank() || port <= 0) return null
        return when (profile.tunnelType) {
            TunnelType.SHADOWSOCKS -> wrap(JSONObject().put("protocol", "shadowsocks").put("settings", JSONObject().put("servers",
                JSONArray().put(JSONObject().put("address", host).put("port", port)
                    .put("method", profile.socksUsername.orEmpty().ifBlank { "2022-blake3-aes-128-gcm" })
                    .put("password", profile.socksPassword.orEmpty())))))
            TunnelType.VMESS -> {
                val server = profile.cdnIp.ifBlank { host }
                val net = profile.vlessTransport.ifBlank { "tcp" }
                val sec = profile.vlessSecurity.ifBlank { "none" }
                val ob = JSONObject().put("protocol", "vmess").put("settings", JSONObject().put("vnext",
                    JSONArray().put(JSONObject().put("address", server).put("port", port).put("users",
                        JSONArray().put(JSONObject().put("id", profile.vlessUuid).put("security", "auto").put("alterId", 0))))))
                ob.put("streamSettings", stream(net, sec, mapOf("path" to profile.vlessWsPath, "host" to profile.domain, "sni" to profile.vlessSni.ifBlank { profile.domain }), server))
                wrap(ob)
            }
            TunnelType.HYSTERIA2 -> wrap(JSONObject().put("protocol", "hysteria").put("settings",
                JSONObject().put("version", 2).put("address", host).put("port", port))
                .put("streamSettings", JSONObject().put("network", "hysteria").put("security", "tls")
                    .put("tlsSettings", JSONObject().put("serverName", profile.vlessSni.ifBlank { host }).put("alpn", JSONArray(listOf("h3"))))
                    .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", profile.socksPassword.orEmpty()))))
            else -> null
        }
    }

    /** Short label of the real protocol inside a full Xray config, e.g. "shadowsocks". */
    fun protocolOf(json: String): String {
        return try {
            val obs = JSONObject(json).optJSONArray("outbounds") ?: return "xray"
            for (i in 0 until obs.length()) {
                val o = obs.optJSONObject(i) ?: continue
                val p = o.optString("protocol")
                if (p !in listOf("freedom", "blackhole", "dns", "loopback", "direct", "block")) {
                    val st = o.optJSONObject("streamSettings")
                    val sec = st?.optString("security").orEmpty()
                    return if (p == "vless" && sec == "reality") "vless-reality" else p
                }
            }
            "xray"
        } catch (_: Exception) { "xray" }
    }
}
