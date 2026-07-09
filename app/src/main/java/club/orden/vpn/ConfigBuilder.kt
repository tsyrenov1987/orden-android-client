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

    // Apps forced ENTIRELY off the tunnel (keep a real RU IP): domain-split can't help them because
    // they either detect the VPN interface itself (Госуслуги) or break the store/bank flow under a
    // foreign IP (RuStore app-updates, banking). Not-installed packages are skipped safely
    // (TunnelVpnService wraps addDisallowedApplication in runCatching). Per-user override UI = TODO.
    private val EXCLUDE_PKG = listOf(
        "ru.rostel",                              // Госуслуги
        "ru.vk.store",                            // RuStore (обновление ВТБ и др.)
        "ru.sberbankmobile",                      // Сбербанк Онлайн
        "ru.vtb24.mobilebank",                    // ВТБ Онлайн
        "com.idamob.tinkoff.android",             // Т-Банк (Тинькофф)
        "ru.alfabank.mobile.android",             // Альфа-Банк
        "ru.gazprombank.android.mobilebank.app",  // Газпромбанк
        "ru.nspk.mirpay",                         // Mir Pay
    )
    private fun excludePackages() = JSONArray().apply { EXCLUDE_PKG.forEach { put(it) } }

    // sing-box rule-sets that route ALL Russian domains + IP ranges DIRECT (keep a real RU IP) — the
    // scalable way to cover "absolutely everything Russian" instead of a hand list. BUNDLED in assets
    // and installed to sing-box's working dir at startup (TunnelApp.installRuleSets), loaded as
    // type:local. Bundling (NOT type:remote off GitHub) is deliberate: raw.githubusercontent.com is
    // unreliable from RU even through the tunnel, so a remote fetch leaves RU services (MAX voice,
    // Avito/VK CDNs on non-.ru domains) routed through the VPN until/unless it downloads — the exact
    // breakage this fixes. Local .srs are read from disk instantly, offline, every launch. ~60KB.
    private val RU_RULESET = linkedMapOf(
        "geoip-ru" to "geoip-ru.srs",
        "geosite-ru" to "geosite-category-ru.srs",
        "geosite-gov-ru" to "geosite-category-gov-ru.srs",
    )
    val ruleSetFiles: List<String> get() = RU_RULESET.values.toList()
    private fun ruRuleSetTags() = JSONArray().apply { RU_RULESET.keys.forEach { put(it) } }
    private fun ruleSetDefs(rulesetDir: String) = JSONArray().apply {
        RU_RULESET.forEach { (tag, file) ->
            put(
                JSONObject().put("type", "local").put("tag", tag).put("format", "binary")
                    .put("path", "$rulesetDir/$file"),
            )
        }
    }

    /**
     * @param rulesetDir absolute path to the dir holding the bundled .srs, or null if they aren't
     *   installed — then RU routing falls back to the legacy suffix list only (still safe, never
     *   references a missing file that would make sing-box reject the whole config).
     */
    fun build(nodes: List<JSONObject>, rulesetDir: String? = null): String {
        require(nodes.isNotEmpty()) { "no nodes" }
        val tags = nodes.map { it.getString("tag") }
        val useRuleSets = rulesetDir != null

        val outbounds = JSONArray()
        outbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", "proxy")
                .put("outbounds", JSONArray(tags))
                // Health-check по IP-АДРЕСУ (1.1.1.1), НЕ по хостнейму: выбор ноды не зависит от
                // DNS-резолва через тоннель. Раньше был gstatic.com/generate_204 → пока DNS через
                // тоннель не готов первые секунды, urltest не мог оценить ноды → нода не выбиралась →
                // egress-проба падала (кластеры egress_timeout/egress_dns на старте в телеметрии).
                .put("url", "https://1.1.1.1/cdn-cgi/trace")
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
                        .put("mtu", 1280)
                        .put("auto_route", true)
                        .put("strict_route", false)
                        .put("stack", "system")
                        .put("exclude_package", excludePackages()),
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
                            // Reject all tunneled IPv6. The tun is dual-stack (ULA v6 addr + auto_route),
                            // so apps that hardcode IPv6 endpoints — notably Telegram's DCs — pull IPv6
                            // into the tunnel, where it black-holes on a node without v6 egress (e.g.
                            // tokyo) or misbehaves (ULA source → global dst, RFC 6724): Telegram fails
                            // for users on IPv6 networks. DNS is ipv4_only so nothing legit needs v6 here;
                            // a reject makes those apps fall back to IPv4 instantly on every node. NOT the
                            // same as dropping v6 from tun — that would leak v6 past the tunnel (real IP).
                            .put(JSONObject().put("ip_version", 6).put("action", "reject"))
                            .apply {
                                // ALL Russian domains + IP ranges bypass the VPN (real RU IP) via geoip/geosite.
                                if (useRuleSets) put(JSONObject().put("rule_set", ruRuleSetTags()).put("outbound", "direct"))
                            }
                            // Suffix fallback — also the sole RU rule if the bundled .srs failed to install.
                            .put(JSONObject().put("domain_suffix", ruSuffixes()).put("outbound", "direct")),
                    )
                    .apply { if (useRuleSets) put("rule_set", ruleSetDefs(rulesetDir!!)) }
                    // Resolve dial-time domains through the tunnel DNS (required since sing-box 1.12).
                    .put("default_domain_resolver", JSONObject().put("server", "remote"))
                    .put("auto_detect_interface", true)
                    .put("final", "proxy"),
            )
            .toString()
    }
}
