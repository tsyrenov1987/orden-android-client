package club.orden.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import club.orden.MainActivity
import club.orden.R
import club.orden.vpn.TunnelController

/**
 * Quick Settings tile: one-tap connect/disconnect from the notification shade — parity with rival
 * clients (Happ, v2RayTun) that RU users pin to the shade. Shares state with the home-screen widget
 * via the same SharedPreferences the VPN service writes; TunnelVpnService.setState asks for a
 * refresh on every transition so the tile repaints even while the shade is open.
 */
class OrdenTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        // Consent already granted -> toggle in place. Otherwise open the app for the system
        // VPN-consent dialog (a tile can't show it directly).
        if (VpnService.prepare(this) == null) {
            if (connected()) TunnelController.stop(this) else TunnelController.start(this)
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
        }
        refresh()
    }

    private fun connected(): Boolean =
        getSharedPreferences("orden_widget", Context.MODE_PRIVATE)
            .getString("state", "disconnected") == "connected"

    private fun refresh() {
        val t = qsTile ?: return
        val on = connected()
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Orden"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Защищено" else "Отключено"
        runCatching { t.icon = Icon.createWithResource(this, R.drawable.orden_shield) }
        t.updateTile()
    }

    companion object {
        /** Re-run onStartListening so the tile repaints on a VPN state transition. */
        fun refreshTile(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, OrdenTileService::class.java))
            }
        }
    }
}
