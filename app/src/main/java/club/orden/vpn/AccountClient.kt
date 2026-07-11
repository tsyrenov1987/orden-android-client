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
    data class VersionInfo(val code: Int, val version: String, val url: String, val mandatory: Boolean, val notes: String)

    /** Latest published app version from the worker (/version); compare code to BuildConfig.VERSION_CODE. */
    fun latestVersion(): VersionInfo? {
        val body = request("GET", "/api/version", null, null) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return VersionInfo(
            o.optInt("code", 0), o.optString("version", ""),
            o.optString("url", "https://joinorden.com/download"),
            o.optBoolean("mandatory", false), o.optString("notes", ""),
        )
    }

    fun redeem(code: String): Redeemed? {
        val body = request("POST", "/api/redeem", JSONObject().put("code", code).toString(), null) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val config = o.optString("config", "")
        val acct = o.optJSONObject("account") ?: return null
        if (config.isBlank()) return null
        return Redeemed(config, parseAccount(acct))
    }

    fun account(code: String): CabinetInfo? {
        val body = request("GET", "/api/account", null, code) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (o.has("error")) return null
        return parseAccount(o)
    }

    /** Report per-node reachability measured on this device (the RU-vantage IP-burn signal). */
    fun reportHealth(code: String, results: Map<String, Boolean>) {
        val r = JSONObject()
        results.forEach { (k, v) -> r.put(k, v) }
        request("POST", "/api/report/health", JSONObject().put("code", code).put("results", r).toString(), null)
    }

    /** Отправить батч исходов попыток подключения (диагностика). true = принято воркером. */
    fun reportConnect(code: String, attempts: List<JSONObject>): Boolean {
        val arr = org.json.JSONArray()
        attempts.forEach { arr.put(it) }
        return request("POST", "/api/report/connect",
            JSONObject().put("code", code).put("attempts", arr).toString(), null) != null
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
            usedBytes = o.optLong("used", up + down), // байты в счёт капа (месячные для платных); фолбэк — старый воркер
            totalBytes = o.optLong("total", 0),       // кап в байтах из /account; 0 = безлимит (comp/близкие)
            expireEpoch = o.optLong("expire", 0),
            ref = o.optString("ref", ""),
            refCount = o.optInt("refCount", 0),
            refDays = o.optInt("refDays", 0),
            servers = servers,
        )
    }

    private fun request(method: String, path: String, json: String?, bearer: String?): String? {
        // Bind to the underlying (non-VPN) network so control-plane calls (self-heal redeem, /report/*,
        // /account) reach the worker even when the tunnel's egress is dead — otherwise the "not working"
        // report and the config re-fetch that would fix it both black-hole through the dead tunnel.
        val net = TunnelController.underlyingNetwork
        // Try each backend host in order (TunnelConfig.apiHosts). We advance to the NEXT host ONLY when a
        // host was never reached (network failure / DPI block) — never after we got an HTTP status, since a
        // received status means the side-effect (/redeem, /report/*) already happened and retrying it on
        // another host would double it. Within a host we still retry on the default network on a stale
        // underlyingNetwork (wifi↔cellular switch), with the same "reachedServer" guard.
        for (base in TunnelConfig.apiHosts) {
            val url = URL(base + path)
            val r = attempt(net, url, method, json, bearer)
            if (r.reachedServer) return r.body
            if (net != null) {
                val r2 = attempt(null, url, method, json, bearer)
                if (r2.reachedServer) return r2.body
            }
            // Neither the underlying nor the default network reached this host -> try the next host.
        }
        return null
    }

    private data class Attempt(val reachedServer: Boolean, val body: String?)

    private fun attempt(net: android.net.Network?, url: URL, method: String, json: String?, bearer: String?): Attempt {
        val c = try {
            (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        } catch (e: Exception) {
            return Attempt(false, null)  // не смогли даже открыть соединение -> сетевой сбой
        }
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Orden")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { os -> os.write(json.toByteArray()) }
            }
            val code = c.responseCode  // дошли до сервера (получили HTTP-статус)
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return Attempt(true, if (code in 200..299) text else null)
        } catch (e: Exception) {
            return Attempt(false, null)  // сетевой сбой/таймаут ДО HTTP-статуса -> можно ретрайнуть
        } finally {
            runCatching { c.disconnect() }
        }
    }
}
