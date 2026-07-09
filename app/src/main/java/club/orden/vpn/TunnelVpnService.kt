package club.orden.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.system.OsConstants
import android.util.Log
import club.orden.BuildConfig
import club.orden.MainActivity
import club.orden.data.SettingsRepository
import club.orden.widget.OrdenWidgetProvider
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.Notification as LibboxNotification
import java.net.NetworkInterface as JNetworkInterface
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The VPN tunnel. Owns a libbox CommandServer (which runs the sing-box instance) and implements
 * PlatformInterface (tun + socket protection) and CommandServerHandler (lifecycle callbacks).
 */
class TunnelVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "club.orden.START"
        const val ACTION_STOP = "club.orden.STOP"
        private const val TAG = "TunnelVpn"
        private const val CHANNEL = "tunnel"
        private const val NOTIF_ID = 1
    }

    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var attemptStartMs = 0L
    // Bumped on every start/stop. The egress-probe captures its value and exits when it changes, so a
    // probe from a previous session can't keep running against (and hot-reloading) a newer tunnel.
    private val tunnelGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** Set tunnel state and mirror it to the home-screen widget (widget failures never break the tunnel). */
    private fun setVpnState(state: VpnState) {
        TunnelController.setState(state)
        runCatching { OrdenWidgetProvider.push(applicationContext, state) }
        runCatching { club.orden.widget.OrdenTileService.refreshTile(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTunnel()
            else -> stopTunnel()   // ACTION_STOP, or a null sticky-restart after process death
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        if (worker != null) return
        setVpnState(VpnState.Connecting)
        attemptStartMs = System.currentTimeMillis()
        val gen = tunnelGen.incrementAndGet()
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        worker = thread(name = "tunnel-box") {
            try {
                val config = buildConfig()
                Libbox.checkConfig(config)
                val server = Libbox.newCommandServer(this, this)
                server.start()
                server.startOrReloadService(config, OverrideOptions())
                commandServer = server
                // TUN is up — but NOT "Защищено" until real data actually flows through it.
                setVpnState(VpnState.Verifying)
                updateNotification("Проверка соединения…")
                Log.i(TAG, "tunnel up, verifying connectivity")
                verifyConnectivity(gen)
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                val cls = ConnectReporter.classify(e)
                // no_config = нет загруженного конфига (юзер ещё не ввёл код) — это ОНБОРДИНГ, а не
                // сетевой сбой/ТСПУ. Не засоряем connect-телеметрию (иначе ложные «блокировки» в
                // аналитике) и показываем понятный статус вместо «нет связи через сервер».
                if (cls == "no_config") {
                    updateNotification("Нет доступа — введите код в приложении")
                } else {
                    ConnectReporter.report(this, false, cls, System.currentTimeMillis() - attemptStartMs)
                }
                worker = null
                setVpnState(VpnState.Disconnected)
                stopTunnel()
            }
        }
    }

    // A green "Защищено" is only honest once real data actually flows THROUGH the tunnel — the server
    // can be reachable while TSPU throttles the stream to nothing. So we probe the egress (short timeout)
    // and only then go Connected; a dead channel surfaces as NoServer instead of a fake green. Keeps
    // re-probing so the status self-heals if urltest later finds a working path.
    private fun verifyConnectivity(gen: Int) {
        thread(name = "egress-probe") {
            var attempt = 0
            var connected = false
            var everConnected = false   // после первого успеха warmup-грейс больше не применяется
            var warmup = 0              // краткий грейс на ПЕРВОМ подключении, пока urltest выбирает ноду
            // Runs for the LIFE of the session (gen-guarded), NOT just until the first success — TSPU can
            // start throttling minutes after connect, so we keep re-probing to catch a green that went
            // dead (was: return@thread on success -> "fake green" frozen forever + no self-heal).
            while (worker != null && commandServer != null && gen == tunnelGen.get()) {
                val (ip, probeErr) = probeEgressOnce(7000)
                if (worker == null || gen != tunnelGen.get()) return@thread
                if (ip != null) {
                    if (!connected) {                       // report/flip only on the transition
                        TunnelController.setEgressIp(ip)
                        setVpnState(VpnState.Connected)
                        updateNotification("Защищено")
                        ConnectReporter.report(this, true, "", System.currentTimeMillis() - attemptStartMs)
                        if (BuildConfig.DEBUG) Log.i(TAG, "EGRESS=$ip")
                    }
                    connected = true
                    everConnected = true
                    attempt = 0
                    // Doze-friendly re-check cadence once healthy (don't wake the radio every few s).
                    runCatching { Thread.sleep(60_000L) }
                    continue
                }
                // Egress failed. Announce NoServer on the transition: either the first failure, or a loss
                // AFTER we were Connected (the throttled-after-connect case this fix exists for).
                val wasConnected = connected
                connected = false
                // Warmup-грейс на ПЕРВОМ подключении: urltest+тоннель устаканиваются пару секунд после
                // подъёма — не флипаем в NoServer и НЕ шлём ложный провал на первых пробах (это и есть
                // «первая проба таймаутит → ретрай ок» из телеметрии), просто ждём и пробуем снова.
                // Статус остаётся «Проверка соединения…». Кап 2, чтобы реально мёртвый канал не завис.
                if (!everConnected && warmup < 2) {
                    warmup++
                    Log.i(TAG, "egress warmup $warmup/2 — urltest settling, no false NoServer")
                    runCatching { Thread.sleep(1500L) }
                    continue
                }
                if (wasConnected || attempt == 0) {
                    if (wasConnected) attemptStartMs = System.currentTimeMillis()  // fresh attempt clock
                    setVpnState(VpnState.NoServer)
                    updateNotification("Нет связи через сервер")
                    // timeout = похоже на null-route/дроп, reset = похоже на активный RST (ТСПУ)
                    ConnectReporter.report(this, false, "egress_$probeErr",
                        System.currentTimeMillis() - attemptStartMs)
                }
                Log.w(TAG, "connectivity probe failed (#${++attempt}) — tunnel up, no egress")
                // SEAMLESS SELF-HEAL: a dead egress often means the Reality dest/SNI got blocked and the
                // server-side monitor rotated it. Re-fetch the config by code (bound to the underlying
                // network in AccountClient, so it reaches the worker even while the tunnel is dead); if
                // the SNI/nodes changed, hot-reload the tunnel onto the new config with NO user action.
                if (selfHealConfig()) {
                    runCatching { commandServer?.startOrReloadService(buildConfig(), OverrideOptions()) }
                    attempt = 0
                    attemptStartMs = System.currentTimeMillis() // новая логическая попытка после hot-reload
                    continue
                }
                // Exponential backoff capped at 5 min: still self-heals if urltest later finds a path,
                // but a stable-dead server no longer wakes the radio every 15s (was breaking Doze).
                val backoffMs = minOf(5_000L shl minOf(attempt - 1, 6), 300_000L)
                runCatching { Thread.sleep(backoffMs) }
            }
        }
    }

    /**
     * Pull a fresh config by access code and persist it if it changed. Returns true when the served
     * config differs (e.g. the server rotated the Reality SNI after a block) so the caller hot-reloads
     * the tunnel. Returns false when there's no code (debug-seeded field), the worker is unreachable,
     * or the config is unchanged — then the caller just retries the existing tunnel.
     */
    private fun selfHealConfig(): Boolean {
        val repo = SettingsRepository(applicationContext)
        val code = runBlocking { repo.accessCode.first() }
        if (code.isBlank()) return false
        val fresh = AccountClient.redeem(code) ?: return false
        val current = runBlocking { repo.subscriptionUrl.first() }
        if (fresh.config == current) return false
        runBlocking { repo.setAccess(code, fresh.config) }
        Log.i(TAG, "self-heal: server rotated config → hot-reloading tunnel onto new SNI")
        return true
    }

    /** One egress probe through the tunnel: egress IP to "" on success, null to error class on failure.
     *  Бьём по IP-АДРЕСУ (1.1.1.1 Cloudflare trace), НЕ по хостнейму: проба не зависит от DNS-резолва
     *  через тоннель. Раньше дёргали api.ipify.org (нужен DNS) → если DNS через тоннель ещё не готов
     *  (первые секунды после подъёма) или ТСПУ дропает его — ложный egress_dns + ложный статус «нет
     *  связи через сервер», хотя тоннель РАБОТАЕТ. egress-IP берём из строки ip= в /cdn-cgi/trace. */
    private fun probeEgressOnce(timeoutMs: Int): Pair<String?, String> = try {
        val c = java.net.URL("https://1.1.1.1/cdn-cgi/trace").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val ip = body.lineSequence().firstOrNull { it.startsWith("ip=") }
            ?.substringAfter("ip=")?.trim()?.takeIf { it.isNotBlank() }
        if (ip != null) ip to "" else null to "empty"
    } catch (e: Throwable) {
        null to ConnectReporter.classify(e)
    }

    /** Build the sing-box config from the subscription field, falling back to the test node. */
    private fun buildConfig(): String {
        val field = runBlocking { SettingsRepository(applicationContext).subscriptionUrl.first() }
        var nodes = runCatching { SubscriptionLoader.load(field) }.getOrDefault(emptyList())
        if (nodes.isEmpty() && BuildConfig.DEBUG && TunnelConfig.testNodeLink.isNotBlank()) {
            nodes = SubscriptionParser.parse(TunnelConfig.testNodeLink) // debug-only test fallback
        }
        require(nodes.isNotEmpty()) { "no access link — get an invite from the club" }
        nodes = preferVisionOverUdp(nodes)
        Log.i(TAG, "config: ${nodes.size} node(s)")
        // Pass the RU rule-set dir only if ALL bundled .srs installed (TunnelApp copies them from
        // assets). If any is missing, ConfigBuilder omits the rule-set refs and routes via the legacy
        // suffix list — a missing/half-copied .srs must never make sing-box reject the whole config.
        val work = java.io.File(filesDir, "work")
        val ruleSetsReady = ConfigBuilder.ruleSetFiles.all { java.io.File(work, it).exists() }
        return ConfigBuilder.build(nodes, if (ruleSetsReady) work.path else null)
    }

    /**
     * RU fixed-line (Rostelecom/TSPU) throttles UDP *throughput* while TCP:443 stays open — but the
     * client's urltest races candidates by the latency of a tiny probe, so QUIC/hy2 wins the race
     * and then crawls. Fix: while the tunnel is still DOWN (this runs before start, on the real
     * network), if any VLESS(:443/TCP) node is reachable, drop the Hysteria2(UDP) outbounds so the
     * race can't land on a throttled transport. If TCP is fully blocked (mobile DPI) or the probe
     * can't run, keep hy2 as the lifeline. Runs on the tunnel-box thread (off main) — see caller.
     */
    private fun preferVisionOverUdp(nodes: List<JSONObject>): List<JSONObject> {
        val vless = nodes.filter { it.optString("type") == "vless" }
        val hasUdp = nodes.any { it.optString("type") == "hysteria2" }
        if (vless.isEmpty() || !hasUdp) return nodes            // nothing to gate
        val visionReachable = vless.any { n ->
            runCatching {
                Socket().use {
                    it.connect(InetSocketAddress(n.optString("server"), n.optInt("server_port", 443)), 1500)
                    true
                }
            }.getOrDefault(false)
        }
        if (!visionReachable) return nodes                       // TCP dead → keep hy2 lifeline
        Log.i(TAG, "vision reachable — dropping hy2 (UDP) from the race")
        return nodes.filter { it.optString("type") != "hysteria2" }
    }

    private fun stopTunnel() {
        worker = null
        val myGen = tunnelGen.incrementAndGet()  // invalidate the egress-probe; also gate this teardown
        val serverToClose = commandServer // close THIS session's server, not one a racing start() installed
        thread(name = "tunnel-stop") {
            serverToClose?.let { runCatching { it.closeService() }; runCatching { it.close() } }
            // Если между stop и сюда прошёл новый start (он делает ++tunnelGen) — НЕ трогаем его
            // состояние: иначе медленный closeService (>reconnect delay) обнулил бы уже поднятый
            // commandServer нового сеанса, затёр бы Verifying и убил только что запущенный сервис.
            if (myGen != tunnelGen.get()) return@thread
            commandServer = null
            setVpnState(VpnState.Disconnected)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        tunnelGen.incrementAndGet()   // invalidate any probe/teardown tied to this instance
        commandServer?.let { runCatching { it.closeService() }; runCatching { it.close() } }
        commandServer = null
        runCatching { monitorThread?.quitSafely() }  // не течь HandlerThread на каждый connect/disconnect
        monitorThread = null
        TunnelController.underlyingNetwork = null
        setVpnState(VpnState.Disconnected)
        super.onDestroy()
    }

    // ---------- CommandServerHandler (5) ----------
    override fun serviceStop() { stopTunnel() }
    override fun serviceReload() {
        runCatching { commandServer?.startOrReloadService(buildConfig(), OverrideOptions()) }
    }
    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
    override fun writeDebugMessage(message: String?) { Log.d("sing-box", message ?: "") }

    // ---------- PlatformInterface (15) ----------
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun useProcFS(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
    override fun clearDNSCache() {}
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int
    ): ConnectionOwner = throw UnsupportedOperationException("findConnectionOwner not supported")
    override fun readWIFIState(): WIFIState? = null
    override fun systemCertificates(): StringIterator = StrIter(emptyList())
    override fun sendNotification(notification: LibboxNotification) {
        Log.i("sing-box", "notify: ${notification.title}")
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("missing VPN permission")
        val b = Builder().setSession("Orden").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)

        options.inet4Address.let { while (it.hasNext()) it.next().let { p -> b.addAddress(p.address(), p.prefix()) } }
        options.inet6Address.let { while (it.hasNext()) it.next().let { p -> b.addAddress(p.address(), p.prefix()) } }

        if (options.autoRoute) {
            runCatching { options.dnsServerAddress.value }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { b.addDnsServer(it) }
            options.inet4RouteAddress.let {
                if (it.hasNext()) while (it.hasNext()) it.next().let { p -> b.addRoute(p.address(), p.prefix()) }
                else b.addRoute("0.0.0.0", 0)
            }
            options.inet6RouteAddress.let {
                if (it.hasNext()) while (it.hasNext()) it.next().let { p -> b.addRoute(p.address(), p.prefix()) }
                else b.addRoute("::", 0)
            }
            options.includePackage.let { while (it.hasNext()) runCatching { b.addAllowedApplication(it.next()) } }
            options.excludePackage.let { while (it.hasNext()) runCatching { b.addDisallowedApplication(it.next()) } }
        }

        val pfd = b.establish() ?: error("VPN establish failed")
        return pfd.detachFd()
    }

    private val cm get() = getSystemService(ConnectivityManager::class.java)
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var monitorThread: HandlerThread? = null
    private val monitorHandler by lazy {
        HandlerThread("tunnel-net").apply { start(); monitorThread = this }.let { Handler(it.looper) }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // Track the UNDERLYING (non-VPN) default network. registerDefaultNetworkCallback reports
        // the VPN itself once tun0 is up (Android P+), which would make sing-box dial the node
        // through its own tunnel ("no available network interface"). Request INTERNET + NOT_VPN so
        // we only ever see the real underlying network (wlan0 / cellular). Report once synchronously
        // to avoid racing the first dial.
        reportDefault(listener, underlyingNetwork())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reportDefault(listener, network)
            override fun onLost(network: Network) = reportDefault(listener, underlyingNetwork())
        }
        netCallback = cb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cm.registerBestMatchingNetworkCallback(request, cb, monitorHandler)
        } else {
            cm.requestNetwork(request, cb, monitorHandler)
        }
    }

    private fun underlyingNetwork(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.let { c ->
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
    }

    private fun reportDefault(listener: InterfaceUpdateListener, network: Network?) {
        // Control-plane (worker) calls bind to this so they bypass the tunnel — see AccountClient.
        TunnelController.underlyingNetwork = network
        if (network == null) { listener.updateDefaultInterface("", -1, false, false); return }
        repeat(10) {
            val name = cm.getLinkProperties(network)?.interfaceName
            val idx = name?.let { runCatching { JNetworkInterface.getByName(it).index }.getOrNull() }
            if (name != null && idx != null) {
                listener.updateDefaultInterface(name, idx, false, false)
                return
            }
            Thread.sleep(100)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
        // Сбросить stale-ссылку: без монитора её некому обновить, а привязка control-plane к мёртвой
        // сети (после смены wifi↔cellular при выключенном VPN) ломала бы кабинет. null -> fallback на default.
        TunnelController.underlyingNetwork = null
    }

    // Enumerate only real connectivity networks (NOT tun0/dummy0/loopback junk), with
    // type/flags/dns — sing-box needs the default interface present here, marked UP+RUNNING
    // with INTERNET, to bind outbound sockets to it.
    override fun getInterfaces(): NetworkInterfaceIterator {
        val cmgr = cm
        val javaIfaces = JNetworkInterface.getNetworkInterfaces().toList()
        val out = ArrayList<io.nekohasekai.libbox.NetworkInterface>()
        for (network in cmgr.allNetworks) {
            val lp = cmgr.getLinkProperties(network) ?: continue
            val caps = cmgr.getNetworkCapabilities(network) ?: continue
            val ji = javaIfaces.find { it.name == lp.interfaceName } ?: continue
            val bi = io.nekohasekai.libbox.NetworkInterface()
            bi.name = lp.interfaceName
            bi.index = ji.index
            runCatching { bi.mtu = ji.mtu }
            bi.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            bi.dnsServer = StrIter(lp.dnsServers.mapNotNull { it.hostAddress })
            bi.addresses = StrIter(ji.interfaceAddresses.mapNotNull {
                // strip IPv6 zone (e.g. "%dummy0") — sing-box's netip.ParsePrefix rejects zones
                it.address.hostAddress?.substringBefore('%')?.let { h -> "$h/${it.networkPrefixLength}" }
            })
            var f = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                f = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (ji.isLoopback) f = f or OsConstants.IFF_LOOPBACK
            if (ji.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
            if (ji.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
            bi.flags = f
            bi.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            out.add(bi)
        }
        return IfaceIter(out)
    }

    private class StrIter(list: List<String>) : StringIterator {
        private val it = list.iterator()
        override fun hasNext() = it.hasNext()
        override fun next() = it.next()
        override fun len() = 0
    }

    private class IfaceIter(list: List<io.nekohasekai.libbox.NetworkInterface>) : NetworkInterfaceIterator {
        private val it = list.iterator()
        override fun hasNext() = it.hasNext()
        override fun next() = it.next()
    }

    // ---------- notification ----------
    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Orden", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Orden")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
