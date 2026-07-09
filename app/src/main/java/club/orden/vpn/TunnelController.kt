package club.orden.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Verifying: TUN up, checking real connectivity through the tunnel. Connected: verified (data flows).
// NoServer: TUN up but the egress probe failed — the channel is dead (e.g. TSPU throttling), an
// honest error instead of a fake "protected".
enum class VpnState { Disconnected, Connecting, Verifying, Connected, NoServer }

/** Process-wide tunnel state + start/stop entry points. The service is the source of truth. */
object TunnelController {
    private val _state = MutableStateFlow(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** Wall-clock millis when the tunnel last came up (0 when not connected) — powers the session timer. */
    private val _connectedSince = MutableStateFlow(0L)
    val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

    /** Measured egress IP once connected (null until probed) — the app maps it to a location label. */
    private val _egressIp = MutableStateFlow<String?>(null)
    val egressIp: StateFlow<String?> = _egressIp.asStateFlow()

    /** The underlying (non-VPN) network. Control-plane calls to the worker (redeem/self-heal/report)
     * bind to it so they DON'T ride the tunnel — otherwise, when the egress is dead, the "not working"
     * report and the config re-fetch that would fix it both black-hole through the dead tunnel. */
    @Volatile var underlyingNetwork: android.net.Network? = null

    internal fun setState(s: VpnState) {
        if (s == VpnState.Connected && _state.value != VpnState.Connected) {
            _connectedSince.value = System.currentTimeMillis()
        } else if (s != VpnState.Connected) {
            _connectedSince.value = 0L
            _egressIp.value = null
        }
        _state.value = s
    }

    internal fun setEgressIp(ip: String?) { _egressIp.value = ip }

    fun start(context: Context) {
        val intent = Intent(context, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_STOP))
    }
}
