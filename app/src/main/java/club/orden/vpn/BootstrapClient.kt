package club.orden.vpn

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Censorship-resistant fallback (audit #3 phase-3). When every backend host in [TunnelConfig.apiHosts]
 * (plus any previously-adopted one) is unreachable — the signature of a DPI block of joinorden.com —
 * the client fetches a fresh, SIGNED list of backend hosts from a DoH TXT record and verifies it against
 * the ECDSA-P256 public key baked into the APK ([TunnelConfig.bootstrapPubKeyB64]). Only the operator's
 * private key can mint a valid blob, so a hostile resolver cannot feed the client attacker-owned hosts.
 *
 * Blob (published as a TXT record at [TunnelConfig.bootstrapDomain]):
 *   base64url(payload) + "." + base64url(sig)
 * where payload = "v1|<https-host-csv>|<unix-ts>" and sig = ECDSA-SHA256 over the payload bytes (DER).
 * Uses only java.security + java.util.Base64 — both available at minSdk 26, no extra dependency.
 */
object BootstrapClient {
    // DoH resolvers addressed by IP + a JSON path, so resolving the TXT never depends on reaching the
    // (possibly blocked) backend domain. Tried in order until one answers.
    private val dohEndpoints = listOf(
        DohEndpoint("https://1.1.1.1/dns-query", cfStyle = true),   // Cloudflare: Accept: application/dns-json
        DohEndpoint("https://8.8.8.8/resolve", cfStyle = false),   // Google: plain JSON
    )

    private data class DohEndpoint(val url: String, val cfStyle: Boolean)

    /** Fetch + verify the signed host list; null if unavailable or invalid. Runs network I/O — off-main. */
    fun fetch(): List<String>? {
        for (ep in dohEndpoints) {
            val blob = queryTxt(ep, TunnelConfig.bootstrapDomain) ?: continue
            val hosts = verify(blob)
            if (hosts != null && hosts.isNotEmpty()) return hosts
        }
        return null
    }

    private fun queryTxt(ep: DohEndpoint, name: String): String? {
        return try {
            val c = URL("${ep.url}?name=$name&type=TXT").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Orden")
            if (ep.cfStyle) c.setRequestProperty("Accept", "application/dns-json")
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            if (body.isNullOrBlank()) return null
            val ans = JSONObject(body).optJSONArray("Answer") ?: return null
            val sb = StringBuilder()
            for (i in 0 until ans.length()) {
                // TXT data arrives quoted (and possibly split into "a" "b"); strip quotes + whitespace.
                sb.append(ans.getJSONObject(i).optString("data", "").replace("\"", "").replace(" ", ""))
            }
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun verify(blob: String): List<String>? {
        return try {
            val dot = blob.indexOf('.')
            if (dot <= 0) return null
            val payload = Base64.getUrlDecoder().decode(blob.substring(0, dot))
            val sig = Base64.getUrlDecoder().decode(blob.substring(dot + 1))

            val spki = Base64.getDecoder().decode(TunnelConfig.bootstrapPubKeyB64)
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub); update(payload); verify(sig)
            }
            if (!ok) return null

            val parts = String(payload, Charsets.UTF_8).split("|")
            if (parts.size != 3 || parts[0] != "v1") return null
            val ts = parts[2].toLongOrNull() ?: return null
            val now = System.currentTimeMillis() / 1000
            // Reject stale (anti-rollback) and far-future (skew/replay) blobs.
            if (ts < now - TunnelConfig.bootstrapMaxAgeDays * 86400L || ts > now + 86400L) return null

            parts[1].split(",")
                .map { it.trim() }
                .filter { it.startsWith("https://") && it.length in 12..200 }
                .distinct()
        } catch (e: Exception) {
            null
        }
    }
}
