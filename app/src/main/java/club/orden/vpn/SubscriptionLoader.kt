package club.orden.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the tunnel's config field into node outbounds. Since the access-code model, the field
 * holds the config blob returned by /redeem (base64 vless+ss). Still accepts a direct http(s)
 * URL or raw share links for the debug seed hook / legacy paste.
 * Runs network I/O — call off the main thread.
 */
object SubscriptionLoader {

    /** Nodes for the sing-box config. */
    fun load(field: String): List<JSONObject> {
        val value = field.trim()
        if (value.isEmpty()) return emptyList()
        val body = if (value.startsWith("http://") || value.startsWith("https://")) fetch(value) else value
        return SubscriptionParser.parse(decodeMaybeBase64(body))
    }

    private fun fetch(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Orden")
        }
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Subscriptions are conventionally base64; fall back to raw text if it isn't. */
    private fun decodeMaybeBase64(body: String): String {
        val compact = body.trim().replace("\n", "").replace("\r", "")
        return runCatching {
            String(Base64.decode(compact, Base64.DEFAULT)).also {
                require(it.contains("://")) { "not base64 links" }
            }
        }.getOrDefault(body)
    }
}
