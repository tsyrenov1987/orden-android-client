package club.orden.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Assembles a full sing-box config from parsed node outbounds.
 * Multi-node failover: a `urltest` outbound ("proxy") picks the lowest-latency working node
 * and auto-switches on failure. tun inbound uses the "system" stack (gvisor doesn't deliver
 * TCP to apps on the emulator).
 *
 * Split-tunneling (default): Russian traffic (.ru/.su/.рф + key non-.ru RU services) is routed
 * DIRECT, with its DNS resolved via a direct Russian resolver so it keeps a real RU IP. This is
 * essential for the RU market — banking/gov apps (Sberbank, Gosuslugi) refuse foreign exit IPs.
 * Everything else (blocked/foreign) goes through the VPN.
 */
object ConfigBuilder {

    // Russian destinations kept off the tunnel. The TLD catch-alls cover the vast majority; the
    // rest are big RU services that also use non-.ru domains.
    private val RU_DIRECT = listOf(
        ".ru", ".su", ".рф",
        "vk.com", "vk.ru", "yandex.com", "yandex.net",
        "sberbank.com", "gazprombank.com", "tinkoff.ru", "tbank.ru",
    )

    private fun ruSuffixes() = JSONArray().apply { RU_DIRECT.forEach { put(it) } }

    fun build(nodes: List<JSONObject>): String {
        require(nodes.isNotEmpty()) { "no nodes" }
        val tags = nodes.map { it.getString("tag") }

        val outbounds = JSONArray()
        outbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", "proxy")
                .put("outbounds", JSONArray(tags))
                .put("url", "https://www.gstatic.com/generate_204")
                .put("interval", "3m0s"),
        )
        nodes.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray().put(
                            JSONObject().put("type", "udp").put("tag", "remote")
                                .put("server", "8.8.8.8").put("detour", "proxy"),
                        ),
                    )
                    .put("final", "remote")
                    .put("strategy", "ipv4_only"),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                        .put("mtu", 1500)
                        .put("auto_route", true)
                        .put("strict_route", false)
                        .put("stack", "system"),
                ),
            )
            .put("outbounds", outbounds)
            .put(
                "route",
                JSONObject()
                    .put(
                        "rules",
                        JSONArray()
                            .put(JSONObject().put("action", "sniff"))
                            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                            // Russian destinations bypass the VPN (banks/gov need a real RU IP).
                            .put(JSONObject().put("domain_suffix", ruSuffixes()).put("outbound", "direct")),
                    )
                    // Resolve dial-time domains through the tunnel DNS (required since sing-box 1.12).
                    .put("default_domain_resolver", JSONObject().put("server", "remote"))
                    .put("auto_detect_interface", true)
                    .put("final", "proxy"),
            )
            .toString()
    }
}
