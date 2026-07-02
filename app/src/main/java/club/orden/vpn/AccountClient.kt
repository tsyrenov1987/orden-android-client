package club.orden.vpn

import club.orden.data.CabinetInfo
import club.orden.data.ServerOption
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Orden backend using the member's access CODE (not a subscription URL):
 *  - redeem(code) exchanges the code for the tunnel config + account snapshot,
 *  - account(code) refreshes just the snapshot for the cabinet.
 * Runs network I/O — call off the main thread.
 */
object AccountClient {
    data class Redeemed(val config: String, val info: CabinetInfo)

    fun redeem(code: String): Redeemed? {
        val body = request("POST", "/redeem", JSONObject().put("code", code).toString(), null) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val config = o.optString("config", "")
        val acct = o.optJSONObject("account") ?: return null
        if (config.isBlank()) return null
        return Redeemed(config, parseAccount(acct))
    }

    fun account(code: String): CabinetInfo? {
        val body = request("GET", "/account", null, code) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (o.has("error")) return null
        return parseAccount(o)
    }

    /** Report per-node reachability measured on this device (the RU-vantage IP-burn signal). */
    fun reportHealth(code: String, results: Map<String, Boolean>) {
        val r = JSONObject()
        results.forEach { (k, v) -> r.put(k, v) }
        request("POST", "/report/health", JSONObject().put("code", code).put("results", r).toString(), null)
    }

    private fun parseAccount(o: JSONObject): CabinetInfo {
        val up = o.optLong("up", 0)
        val down = o.optLong("down", 0)
        val servers = mutableListOf<ServerOption>()
        o.optJSONArray("servers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                servers += ServerOption(e.optString("id"), e.optString("label"), e.optString("ip"))
            }
        }
        return CabinetInfo(
            plan = o.optString("plan", "").ifBlank { null },
            usedBytes = up + down,
            totalBytes = 0, // this model is unlimited
            expireEpoch = o.optLong("expire", 0),
            ref = o.optString("ref", ""),
            refCount = o.optInt("refCount", 0),
            refDays = o.optInt("refDays", 0),
            servers = servers,
        )
    }

    private fun request(method: String, path: String, json: String?, bearer: String?): String? = runCatching {
        val c = (URL(TunnelConfig.apiBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Orden")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        json?.let { c.outputStream.use { os -> os.write(it.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code in 200..299) text else null
    }.getOrNull()
}
