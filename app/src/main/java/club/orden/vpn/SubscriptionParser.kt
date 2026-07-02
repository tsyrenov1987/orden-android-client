package club.orden.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Parses VPN share links into sing-box outbound JSON objects.
 * Supported: vless+reality+vision (primary), shadowsocks SIP002 + hysteria2 (fallbacks).
 * Each outbound gets a unique "tag" (from the link's #name, deduped).
 */
object SubscriptionParser {

    /** Parse newline-separated share links. Unparseable lines are skipped. */
    fun parse(text: String): List<JSONObject> {
        val seen = HashMap<String, Int>()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexedNotNull { i, line ->
                runCatching {
                    when {
                        line.startsWith("vless://") -> parseVless(line, i)
                        line.startsWith("ss://") -> parseSs(line, i)
                        line.startsWith("hysteria2://") -> parseHy2(line, i)
                        else -> null
                    }
                }.getOrNull()
            }
            .map { ob ->                       // dedupe tags so urltest references are unique
                val base = ob.getString("tag")
                val n = seen.getOrDefault(base, 0)
                seen[base] = n + 1
                if (n > 0) ob.put("tag", "$base-$n") else ob
            }
            .toList()
    }

    private fun parseVless(uri: String, index: Int): JSONObject {
        val u = URI(uri)
        val uuid = requireNotNull(u.userInfo) { "vless: missing uuid" }
        val host = requireNotNull(u.host) { "vless: missing host" }
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.rawQuery)
        val name = u.fragment?.takeIf { it.isNotBlank() }?.let { dec(it) } ?: "node-$index"

        val ob = JSONObject()
            .put("type", "vless")
            .put("tag", name)
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
        q["flow"]?.takeIf { it.isNotBlank() }?.let { ob.put("flow", it) }

        when (q["security"]) {
            "reality", "tls" -> {
                val tls = JSONObject().put("enabled", true)
                (q["sni"] ?: q["host"])?.let { tls.put("server_name", it) }
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", q["fp"] ?: "chrome"))
                if (q["security"] == "reality") {
                    tls.put(
                        "reality",
                        JSONObject().put("enabled", true)
                            .put("public_key", q["pbk"].orEmpty())
                            .put("short_id", q["sid"].orEmpty()),
                    )
                }
                ob.put("tls", tls)
            }
        }
        // transport (type=ws/grpc/http) is left for a later milestone; MVP nodes use tcp.
        return ob
    }

    /**
     * Hysteria2 (QUIC/UDP) fallback with Salamander obfuscation. The server cert is self-signed,
     * so we PIN its public-key SHA-256 (`pinsha256`) instead of trusting a CA — no insecure TLS.
     * Link shape (Orden's own): hysteria2://<user:pass>@host:port?obfs=salamander&obfs-password=..&sni=..&pinsha256=..#name
     */
    private fun parseHy2(uri: String, index: Int): JSONObject {
        val u = URI(uri)
        val auth = requireNotNull(u.userInfo) { "hy2: missing auth" } // "<uuid>:<psk>"
        val host = requireNotNull(u.host) { "hy2: missing host" }
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.rawQuery)
        val name = u.fragment?.takeIf { it.isNotBlank() }?.let { dec(it) } ?: "hy2-$index"

        val tls = JSONObject().put("enabled", true)
        q["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
        q["pinsha256"]?.takeIf { it.isNotBlank() }?.let {
            tls.put("certificate_public_key_sha256", org.json.JSONArray().put(it))
        }

        val ob = JSONObject()
            .put("type", "hysteria2")
            .put("tag", name)
            .put("server", host)
            .put("server_port", port)
            .put("password", auth)
            .put("tls", tls)
        val obfsPw = q["obfs-password"]
        if (q["obfs"] == "salamander" && !obfsPw.isNullOrBlank()) {
            ob.put("obfs", JSONObject().put("type", "salamander").put("password", obfsPw))
        }
        return ob
    }

    /** Shadowsocks SIP002 / legacy. CODED PER SPEC BUT UNTESTED (no ss node yet). */
    private fun parseSs(uri: String, index: Int): JSONObject {
        val noScheme = uri.removePrefix("ss://")
        val name = noScheme.substringAfter('#', "").takeIf { it.isNotBlank() }?.let { dec(it) } ?: "ss-$index"
        var body = noScheme.substringBefore('#').substringBefore('?')

        val method: String
        val password: String
        val host: String
        val port: Int
        if (body.contains('@')) {                       // SIP002: base64(method:pass)@host:port
            val cred = b64(body.substringBefore('@'))
            method = cred.substringBefore(':')
            password = cred.substringAfter(':')
            val hp = body.substringAfter('@')
            host = hp.substringBeforeLast(':')
            port = hp.substringAfterLast(':').toInt()
        } else {                                        // legacy: base64(method:pass@host:port)
            val full = b64(body)
            method = full.substringBefore(':')
            password = full.substringAfter(':').substringBefore('@')
            val hp = full.substringAfter('@')
            host = hp.substringBeforeLast(':')
            port = hp.substringAfterLast(':').toInt()
        }
        return JSONObject()
            .put("type", "shadowsocks").put("tag", name)
            .put("server", host).put("server_port", port)
            .put("method", method).put("password", password)
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull {
            val k = it.substringBefore('=')
            if (k.isEmpty()) null else k to dec(it.substringAfter('=', ""))
        }.toMap()

    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun b64(s: String): String =
        String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
}
