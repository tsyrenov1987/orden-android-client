package club.orden.vpn

/**
 * App-level endpoints. The real path is the subscription URL the user pastes
 * (parsed by SubscriptionLoader); the access code is issued by the bot.
 */
object TunnelConfig {
    /** Access hub: the bot issues the personal access code. */
    const val botUrl = "https://t.me/OrdenAccessBot"

    /** Orden backend: redeems access codes and serves account state. */
    const val apiBase = "https://orden.tsyrenov1987.workers.dev"

    /**
     * Optional debug-only fallback node (a `vless://`/`ss://`/`hy2://` share link) used when the
     * subscription field is empty in a DEBUG build. Left blank in source — supply your own via a
     * local build config if you want an offline test node. Never commit real credentials here.
     */
    const val testNodeLink = ""
}
