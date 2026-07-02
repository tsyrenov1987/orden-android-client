package club.orden

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import club.orden.data.SettingsRepository
import club.orden.ui.AppRoot
import club.orden.ui.theme.TunnelTheme
import club.orden.vpn.AccountClient
import club.orden.vpn.TunnelCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M1a smoke check: loads libbox.so and confirms the core links.
        Log.i("TunnelCore", "libbox ${TunnelCore.version()}")
        if (BuildConfig.DEBUG) {
            // Debug test hooks: seed a raw config blob (`--es sub`) or redeem a code (`--es code`).
            intent?.getStringExtra("sub")?.let { sub ->
                runBlocking { SettingsRepository(this@MainActivity).setSubscriptionUrl(sub) }
            }
            intent?.getStringExtra("code")?.let { redeemCode(it) }
        }
        handleActivation(intent)
        enableEdgeToEdge()
        setContent {
            TunnelTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleActivation(intent)
    }

    /**
     * One-tap activation from the bot: an https://…/activate?code=<CODE> App Link. Redeems the
     * code (fetches config + account) so the user only has to tap Connect.
     */
    private fun handleActivation(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.host !in ALLOWED_HOSTS) return
        if (uri.path != "/activate") return
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        if (code.isNotEmpty()) redeemCode(code)
    }

    private fun redeemCode(code: String) {
        val c = code.trim().uppercase()
        if (c.isEmpty()) return
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { AccountClient.redeem(c) }
            if (res != null) {
                val repo = SettingsRepository(this@MainActivity)
                repo.setAccess(c, res.config)
                repo.setCabinet(res.info)
                Toast.makeText(this@MainActivity, "Доступ активирован — нажмите замок", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Не удалось активировать код", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        val ALLOWED_HOSTS = setOf("orden.tsyrenov1987.workers.dev", "orden.club")
    }
}
