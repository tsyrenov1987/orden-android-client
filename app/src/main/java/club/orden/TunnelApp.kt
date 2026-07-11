package club.orden

import android.app.Application
import android.content.Context
import club.orden.vpn.AccountClient
import club.orden.vpn.ConfigBuilder
import club.orden.vpn.VpnState
import club.orden.widget.OrdenWidgetProvider
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/** Initializes the sing-box core once per process before any tunnel use. */
class TunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val work = File(filesDir, "work").apply { mkdirs() }
        installRuleSets(work)
        Libbox.setup(SetupOptions().apply {
            basePath = filesDir.path
            workingPath = work.path
            tempPath = cacheDir.path
            fixAndroidStack = false
            logMaxLines = 3000L
        })
        // Capture sing-box stderr (incl. Go panics) to a file readable via adb.
        runCatching {
            getExternalFilesDir(null)?.let { Libbox.redirectStderr(File(it, "stderr.log").path) }
        }
        // A fresh process has no active tunnel (the VPN service dies with the process). Reconcile the
        // widget so it never shows a stale "connected" left over from a killed process.
        runCatching { OrdenWidgetProvider.push(this, VpnState.Disconnected) }

        // Wire the signed DoH bootstrap (audit #3 phase-3): load previously-adopted backend hosts and
        // persist any new ones the client verifies when the primary domain gets blocked.
        val bp = getSharedPreferences("orden_bootstrap", Context.MODE_PRIVATE)
        bp.getString("hosts", null)?.takeIf { it.isNotBlank() }?.let {
            AccountClient.setExtraHosts(it.split(",").filter(String::isNotBlank))
        }
        AccountClient.onBootstrap = { hosts ->
            runCatching { bp.edit().putString("hosts", hosts.joinToString(",")).apply() }
        }
    }

    // Copy the bundled RU rule-sets from assets into sing-box's working dir so route.rule_set
    // (type:local) can read them by path. Overwrites each launch — the .srs ship with the APK, so
    // this keeps them fresh across updates; ~60 KB, negligible. Best-effort: if a copy fails, the
    // .srs is absent, ConfigBuilder omits the rule-set refs and falls back to the legacy suffix list.
    private fun installRuleSets(work: File) {
        ConfigBuilder.ruleSetFiles.forEach { name ->
            runCatching {
                assets.open(name).use { input ->
                    File(work, name).outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}
