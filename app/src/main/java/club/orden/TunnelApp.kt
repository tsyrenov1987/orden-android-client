package club.orden

import android.app.Application
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
    }
}
