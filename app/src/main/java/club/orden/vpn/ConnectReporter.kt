package club.orden.vpn

import android.content.Context
import club.orden.data.SettingsRepository
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Per-attempt диагностика подключения: исход каждой попытки туннеля (ok / класс ошибки /
 * латентность) уходит на воркер POST /report/connect → connlog:<token>, виден в кабинете и
 * консоли. Только метаданные попытки — НИКАКИХ destinations. Fire-and-forget: неотправленные
 * исходы лежат в памяти до следующего исхода (кап 20, теряются со смертью процесса).
 */
object ConnectReporter {
    private val pending = ArrayList<JSONObject>()

    fun report(context: Context, ok: Boolean, err: String, ms: Long) {
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis() / 1000)
            .put("node", "") // tunnel-level: какую ноду взял urltest, клиент не знает
            .put("proto", "tun")
            .put("ok", ok)
            .put("err", err)
            .put("ms", ms)
        val batch: List<JSONObject>
        synchronized(pending) {
            pending.add(entry)
            while (pending.size > 20) pending.removeAt(0)
            batch = ArrayList(pending)
        }
        val app = context.applicationContext
        thread(name = "connect-report") {
            val code = runBlocking { SettingsRepository(app).accessCode.first() }
            if (code.isBlank()) return@thread
            if (AccountClient.reportConnect(code, batch)) {
                synchronized(pending) { pending.removeAll(batch.toSet()) }
            }
        }
    }

    /** Грубый класс ошибки для телеметрии (воркер режет до 24 символов). */
    fun classify(e: Throwable): String = when {
        e is java.net.SocketTimeoutException -> "timeout"
        e is java.net.UnknownHostException -> "dns"
        e is javax.net.ssl.SSLException -> "tls"
        e is java.net.ConnectException -> "refused"
        e is java.net.SocketException -> "reset"
        e.message?.contains("permission", ignoreCase = true) == true -> "vpn_permission"
        e.message?.contains("no access link", ignoreCase = true) == true -> "no_config"
        else -> e.javaClass.simpleName.take(24)
    }
}
