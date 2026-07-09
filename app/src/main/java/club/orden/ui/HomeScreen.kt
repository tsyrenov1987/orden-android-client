package club.orden.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import club.orden.HomeViewModel
import club.orden.R
import club.orden.data.CabinetInfo
import club.orden.vpn.TunnelConfig
import club.orden.vpn.TunnelController
import club.orden.vpn.VpnState

// "Protected" green — pairs with the text label so status is never color-only (a11y).
private val ProtectedGreen = Color(0xFF3FB950)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by TunnelController.state.collectAsState()
    val egressIp by TunnelController.egressIp.collectAsState()
    val connectedSince by TunnelController.connectedSince.collectAsState()
    val code by vm.accessCode.collectAsState()
    val cabinet by vm.cabinet.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val update by vm.update.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    // Resolve the measured egress IP to a location label via the account's server list.
    val location = egressIp?.let { ip ->
        cabinet?.servers?.firstOrNull { it.ip.isNotBlank() && it.ip == ip }?.label
    }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) TunnelController.start(context)
    }

    fun onConnectClick() {
        if (state == VpnState.Disconnected) {
            val prep = VpnService.prepare(context)
            if (prep != null) vpnPermLauncher.launch(prep) else TunnelController.start(context)
        } else {
            TunnelController.stop(context)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.orden_shield),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("ORDEN")
                }
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            update?.let { u ->
                UpdateBanner(
                    version = u.version,
                    notes = u.notes,
                    mandatory = u.mandatory,
                    onUpdate = { openUrl(context, u.url) },
                    onDismiss = vm::dismissUpdate,
                )
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.height(24.dp))
            ConnectButton(state = state, onClick = ::onConnectClick)
            Spacer(Modifier.height(20.dp))
            ConnectionStatus(state)
            Spacer(Modifier.height(24.dp))

            if (state == VpnState.Connected) {
                ConnectionDetailsCard(
                    location = location,
                    ip = egressIp,
                    connectedSince = connectedSince,
                )
                Spacer(Modifier.height(16.dp))
            }

            if (state == VpnState.NoServer) {
                NoServerCard(onReconnect = {
                    TunnelController.stop(context)
                    scope.launch {
                        delay(800)
                        val prep = VpnService.prepare(context)
                        if (prep != null) vpnPermLauncher.launch(prep) else TunnelController.start(context)
                    }
                })
                Spacer(Modifier.height(16.dp))
            }

            val info = cabinet
            when {
                info != null && code.isNotBlank() -> CabinetCard(
                    info = info,
                    busy = busy,
                    onRefresh = vm::refresh,
                    // Open the bot WITH this account's code (start=web-<code>) so the bot adopts the
                    // account automatically (adopt_web_code) — the user doesn't re-enter anything. This
                    // is the seamless web→app→bot handoff: one code carries through site, app, and bot.
                    onRenew = { openUrl(context, "${TunnelConfig.botUrl}?start=web-$code") },
                    onInvite = { shareInvite(context, info.ref) },
                    onSignOut = vm::signOut,
                )
                else -> CodeEntryCard(
                    busy = busy,
                    error = error,
                    onActivate = vm::redeem,
                    onGetCode = { openUrl(context, TunnelConfig.botUrl) },
                    onClearError = vm::clearError,
                )
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    version: String,
    notes: String,
    mandatory: Boolean,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Доступно обновление $version", style = MaterialTheme.typography.titleSmall)
            if (notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(notes, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!mandatory) {
                    TextButton(onClick = onDismiss) { Text("Позже") }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onUpdate) { Text("Обновить") }
            }
        }
    }
}

@Composable
private fun CabinetCard(
    info: CabinetInfo,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRenew: () -> Unit,
    onInvite: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "ЛИЧНЫЙ КАБИНЕТ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(14.dp))

            info.plan?.let {
                InfoRow("План", it)
                Spacer(Modifier.height(8.dp))
            }

            // Access + renew.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Доступ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(accessText(info.expireEpoch), style = MaterialTheme.typography.bodyMedium)
                    if (info.expireEpoch > 0) {
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = onRenew, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("Продлить")
                        }
                    }
                }
            }

            if (info.usedBytes >= 0) {
                Spacer(Modifier.height(8.dp))
                if (info.totalBytes > 0) {
                    InfoRow("Трафик", "${formatBytes(info.usedBytes)} / ${formatBytes(info.totalBytes)}")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (info.usedBytes.toFloat() / info.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    InfoRow("Трафик", "${formatBytes(info.usedBytes)} · безлимит")
                }
            }

            Spacer(Modifier.height(8.dp))
            InfoRow("Сервер", "Авто · быстрейший узел")

            // Referral program.
            if (info.ref.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "РЕФЕРАЛЬНАЯ ПРОГРАММА",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow("Ваш код", info.ref)
                Spacer(Modifier.height(4.dp))
                InfoRow("Приглашено", "${info.refCount} · +${info.refDays} дн.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onInvite, modifier = Modifier.fillMaxWidth()) {
                    Text("👥 Пригласить друга")
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RefreshButton(busy, onRefresh)
                TextButton(onClick = onSignOut) { Text("Сменить код") }
            }
        }
    }
}

/** Colored status pill under the connect button — always paired with a text label (a11y). */
@Composable
private fun ConnectionStatus(state: VpnState) {
    val (label, color) = when (state) {
        VpnState.Connected -> "Защищено" to ProtectedGreen
        VpnState.Connecting -> "Подключение…" to MaterialTheme.colorScheme.secondary
        VpnState.Verifying -> "Проверка соединения…" to MaterialTheme.colorScheme.secondary
        VpnState.NoServer -> "Нет связи через сервер" to MaterialTheme.colorScheme.error
        VpnState.Disconnected -> "Не защищено" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/**
 * Ticking session-uptime row, isolated into its own leaf composable so the 1s tick recomposes ONLY
 * this row — not the whole HomeScreen content block (was re-running every child + the heavy cards
 * once per second). The LaunchedEffect is scoped to this composable, so it starts/stops with the
 * card's presence (card shows only while Connected).
 */
@Composable
private fun SessionUptime(connectedSince: Long) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(1000) }
    }
    InfoRow("Время сессии", formatUptime(if (connectedSince > 0) nowMs - connectedSince else 0))
}

/** Connection-details surface shown while the tunnel is up (Proton "telemetry-on-tap" pattern). */
@Composable
private fun ConnectionDetailsCard(location: String?, ip: String?, connectedSince: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "СОЕДИНЕНИЕ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(14.dp))
            InfoRow("Локация", location ?: "Определяется…")
            Spacer(Modifier.height(8.dp))
            InfoRow("IP-адрес", ip ?: "Определяется…")
            Spacer(Modifier.height(8.dp))
            InfoRow("Протокол", "VLESS · Reality")
            Spacer(Modifier.height(8.dp))
            SessionUptime(connectedSince)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Банки и госуслуги РФ идут напрямую — вне туннеля.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Honest error surface: TUN is up but no data flows through the server (dead/throttled channel). */
@Composable
private fun NoServerCard(onReconnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "Нет соединения через сервер",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Приложение подключилось, но данные через сервер не идут — вероятно, " +
                    "провайдер ограничивает канал. Переподключитесь; если не помогает — " +
                    "попробуйте позже или напишите в бот.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Переподключиться")
            }
        }
    }
}

@Composable
private fun CodeEntryCard(
    busy: Boolean,
    error: String?,
    onActivate: (String) -> Unit,
    onGetCode: () -> Unit,
    onClearError: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Активация", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Введите код доступа из бота клуба.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; if (error != null) onClearError() },
                label = { Text("Код доступа") },
                placeholder = { Text("ORDEN-XXXXX-XXXXX") },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onActivate(code) },
                enabled = !busy && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (busy) "Активация…" else "Активировать")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onGetCode, modifier = Modifier.fillMaxWidth()) {
                Text("Получить код · @OrdenAccessBot")
            }
        }
    }
}

@Composable
private fun RefreshButton(refreshing: Boolean, onRefresh: () -> Unit) {
    TextButton(onClick = onRefresh, enabled = !refreshing) {
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(if (refreshing) "Обновление…" else "Обновить")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Human access status from the account expiry (unix seconds; 0 = never, -1 = unknown). */
private fun accessText(expireEpoch: Long): String {
    if (expireEpoch < 0) return "активен"
    if (expireEpoch == 0L) return "активен · бессрочно"
    val now = System.currentTimeMillis() / 1000
    if (expireEpoch <= now) return "истёк"
    val days = (expireEpoch - now) / 86_400
    return if (days >= 1) "активен · $days дн." else "активен · <1 дн."
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) String.format("%.1f ГБ", gb) else String.format("%.0f МБ", bytes / 1_048_576.0)
}

/** Session uptime as H:MM:SS (or MM:SS under an hour). */
private fun formatUptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun shareInvite(context: Context, ref: String) {
    val link = "https://t.me/OrdenAccessBot?start=$ref"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Заходи в Orden — приватный VPN по приглашению: $link")
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Пригласить друга").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun ConnectButton(state: VpnState, onClick: () -> Unit) {
    val connected = state == VpnState.Connected
    val connecting = state == VpnState.Connecting || state == VpnState.Verifying
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        // Immediate feedback on tap: a spinning ring around the button while connecting.
        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(200.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (connected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            border = if (connected) BorderStroke(3.dp, ProtectedGreen) else null,
            modifier = Modifier.size(180.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = if (connected) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = if (connected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
