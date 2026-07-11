package club.orden.vpn

/**
 * App-level endpoints. The real path is the subscription URL the user pastes
 * (parsed by SubscriptionLoader); the access code is issued by the bot.
 */
object TunnelConfig {
    /** Access hub: the bot issues the personal access code. */
    const val botUrl = "https://t.me/OrdenAccessBot"

    /**
     * Orden backend hosts, tried in order for every control-plane call (redeem / account / version /
     * report). Each host MUST serve the same `/api/` proxy contract that joinorden.com serves
     * (functions/api/redeem, /api/account, /api/version, /api/report/health, /api/report/connect).
     *
     * Primary is joinorden.com: its Pages Functions proxy to the worker INSIDE Cloudflare, so the
     * client never talks to the throttled `*.workers.dev` host directly (and the operator's personal
     * subdomain is no longer baked into the APK). To harden against a single-domain block in RU, add
     * extra hosts on DIFFERENT domains/providers (NOT `*.pages.dev`, NOT `workers.dev`) — when one host
     * is unreachable, [AccountClient] falls through to the next.
     */
    val apiHosts = listOf(
        "https://joinorden.com",
        // "https://api.orden.club",   // пример резервного домена — раскомментировать после привязки
    )

    // --- Signed DoH bootstrap (audit #3 phase-3) -----------------------------------------------------
    // If EVERY apiHost (and any previously-adopted host) is unreachable — the signature of a DPI block
    // of joinorden.com — [BootstrapClient] fetches a fresh, SIGNED list of backend hosts from a DoH TXT
    // record and verifies it against the key below. Only the operator's private key
    // (~/.orden/bootstrap-signing-key.pem, held offline) can mint a valid blob; publish new blobs with
    // orden-web/tools/sign-bootstrap.sh.

    /** ECDSA-P256 public key (X.509 SPKI, base64) that must sign the bootstrap host list. */
    const val bootstrapPubKeyB64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECMbnAhTLN0sl5awYjapdXxKLrrQt7tIjrPYsD0tLVf7C5d04yOkjk4QScWuohxGbahAPbtV2dDj/Oey+rBHZAg=="

    /** Domain whose TXT record holds the signed blob. Keep it on a domain that does NOT share fate with
     *  joinorden.com (different registrar/nameserver), so a block of one doesn't take out both. */
    const val bootstrapDomain = "_ob.orden.club"

    /** Reject bootstrap blobs older than this many days (anti-rollback). Re-sign within this window. */
    const val bootstrapMaxAgeDays = 90L

    /**
     * Optional debug-only fallback node (a `vless://`/`ss://`/`hy2://` share link) used when the
     * subscription field is empty in a DEBUG build. Left blank in source — supply your own via a
     * local build config if you want an offline test node. Never commit real credentials here.
     */
    const val testNodeLink = ""
}
