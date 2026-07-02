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

    /** Set tunnel state and mirror it to the home-screen widget (widget failures never break the tunnel). */
    private fun setVpnState(state: VpnState) {
        TunnelController.setState(state)
        runCatching { OrdenWidgetProvider.push(applicationContext, state) }
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
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        worker = thread(name = "tunnel-box") {
            try {
                val config = buildConfig()
                Libbox.checkConfig(config)
                val server = Libbox.newCommandServer(this, this)
                server.start()
                server.startOrReloadService(config, OverrideOptions())
                commandServer = server
                setVpnState(VpnState.Connected)
                updateNotification("Connected")
                Log.i(TAG, "tunnel up")
                probeEgress()
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                worker = null
                setVpnState(VpnState.Disconnected)
                stopTunnel()
            }
        }
    }

    // Probe the egress IP through the tunnel (app-uid HTTPS) and publish it so the UI can resolve
    // the active location. Also logs EGRESS= in debug builds for the connectivity self-test.
    private fun probeEgress() {
        thread(name = "egress-probe") {
            runCatching {
                val c = java.net.URL("https://api.ipify.org")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 12000
                c.readTimeout = 12000
                val ip = c.inputStream.bufferedReader().use { it.readText() }.trim()
                if (BuildConfig.DEBUG) Log.i(TAG, "EGRESS=$ip")
                if (worker != null) TunnelController.setEgressIp(ip)
            }.onFailure { Log.e(TAG, "egress probe failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** Build the sing-box config from the subscription field, falling back to the test node. */
    private fun buildConfig(): String {
        val field = runBlocking { SettingsRepository(applicationContext).subscriptionUrl.first() }
        var nodes = runCatching { SubscriptionLoader.load(field) }.getOrDefault(emptyList())
        if (nodes.isEmpty() && BuildConfig.DEBUG && TunnelConfig.testNodeLink.isNotBlank()) {
            nodes = SubscriptionParser.parse(TunnelConfig.testNodeLink) // debug-only test fallback
        }
        require(nodes.isNotEmpty()) { "no access link — get an invite from the club" }
        Log.i(TAG, "config: ${nodes.size} node(s)")
        return ConfigBuilder.build(nodes)
    }

    private fun stopTunnel() {
        worker = null
        thread(name = "tunnel-stop") {
            commandServer?.let { runCatching { it.closeService() }; runCatching { it.close() } }
            commandServer = null
            setVpnState(VpnState.Disconnected)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        commandServer?.let { runCatching { it.closeService() }; runCatching { it.close() } }
        commandServer = null
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
    private val monitorHandler by lazy { Handler(HandlerThread("tunnel-net").apply { start() }.looper) }

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
