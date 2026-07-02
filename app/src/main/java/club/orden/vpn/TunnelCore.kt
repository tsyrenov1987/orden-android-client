package club.orden.vpn

import io.nekohasekai.libbox.Libbox

/**
 * Thin wrapper over the embedded sing-box core (libbox.aar).
 * M1a: only proves the core is linked and callable (version()).
 * M1b will add Libbox.setup() + a PlatformInterface impl + service start/stop.
 */
object TunnelCore {
    fun version(): String = Libbox.version()
}
