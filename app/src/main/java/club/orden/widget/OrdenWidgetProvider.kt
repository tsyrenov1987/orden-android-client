package club.orden.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.widget.RemoteViews
import club.orden.MainActivity
import club.orden.R
import club.orden.vpn.TunnelController
import club.orden.vpn.VpnState

private const val PREFS = "orden_widget"
private const val KEY_STATE = "state"
private const val ACTION_TOGGLE = "club.orden.WIDGET_TOGGLE"

/**
 * Home-screen widget: glanceable protection state + one-tap connect. The VPN service is the source
 * of truth and calls [push] on every transition; the last state is persisted so the widget renders
 * correctly even when the app process is dead (Android calls onUpdate on its own schedule).
 */
class OrdenWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val state = readState(context)
        for (id in ids) mgr.updateAppWidget(id, buildViews(context, state))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        // Consent already granted -> toggle in place. Otherwise open the app to grant VPN permission
        // (a background receiver can't show the system VPN-consent dialog).
        if (VpnService.prepare(context) == null) {
            if (readState(context) == "connected") TunnelController.stop(context)
            else TunnelController.start(context)
        } else {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        /** Persist the latest state and repaint every widget instance. Called by the VPN service. */
        fun push(context: Context, state: VpnState) {
            val s = when (state) {
                VpnState.Connected -> "connected"
                VpnState.Connecting, VpnState.Verifying -> "connecting"
                VpnState.Disconnected, VpnState.NoServer -> "disconnected"
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, s).apply()
            val mgr = AppWidgetManager.getInstance(context)
            for (id in mgr.getAppWidgetIds(ComponentName(context, OrdenWidgetProvider::class.java))) {
                mgr.updateAppWidget(id, buildViews(context, s))
            }
        }

        private fun readState(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, "disconnected")
                ?: "disconnected"

        private fun buildViews(context: Context, state: String): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.orden_widget)
            val (label, color) = when (state) {
                "connected" -> "Защищено" to Color.parseColor("#3FB950")
                "connecting" -> "Подключение…" to Color.parseColor("#3FA9E0")
                else -> "Не защищено" to Color.parseColor("#8A909C")
            }
            v.setTextViewText(R.id.widget_status, label)
            v.setTextColor(R.id.widget_status, color)
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, OrdenWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            return v
        }
    }
}
