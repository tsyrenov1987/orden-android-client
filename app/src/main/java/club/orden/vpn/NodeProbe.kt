package club.orden.vpn

import club.orden.data.ServerOption
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP-reachability of each node's :443 from THIS device's current network. Run only while the
 * tunnel is DOWN (so the socket uses the real underlying network, not the tunnel) and off the main
 * thread. The result is the only signal that can see an RU-side IP block — the edge/server can't.
 */
object NodeProbe {
    fun probe(servers: List<ServerOption>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        for (s in servers) {
            if (s.ip.isBlank()) continue
            out[s.id] = runCatching {
                Socket().use { it.connect(InetSocketAddress(s.ip, 443), 3000); true }
            }.getOrDefault(false)
        }
        return out
    }
}
