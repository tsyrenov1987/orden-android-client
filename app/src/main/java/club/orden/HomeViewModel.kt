package club.orden

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import club.orden.data.CabinetInfo
import club.orden.data.SettingsRepository
import club.orden.vpn.AccountClient
import club.orden.vpn.NodeProbe
import club.orden.vpn.TunnelController
import club.orden.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)

    val accessCode: StateFlow<String> =
        repo.accessCode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val cabinet: StateFlow<CabinetInfo?> =
        repo.cabinet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // null = still loading from disk (avoids flashing the EULA for users who already accepted).
    val eulaAccepted: StateFlow<Boolean?> =
        repo.eulaAccepted.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun acceptEula() {
        viewModelScope.launch { repo.setEulaAccepted() }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    /** Redeem an access code: fetch config + account, persist both. */
    fun redeem(code: String) {
        val c = code.trim().uppercase()
        if (c.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val res = withContext(Dispatchers.IO) { AccountClient.redeem(c) }
            if (res != null) {
                repo.setAccess(c, res.config)
                repo.setCabinet(res.info)
                _error.value = null
            } else {
                _error.value = "Неверный код или нет связи. Проверьте код."
            }
            _busy.value = false
        }
    }

    /** Refresh: re-redeem so the tunnel config picks up a rotated node IP, and update the cabinet. */
    fun refresh() {
        if (_busy.value) return
        viewModelScope.launch {
            val code = repo.accessCode.first()
            if (code.isBlank()) return@launch
            _busy.value = true
            val res = withContext(Dispatchers.IO) { AccountClient.redeem(code) }
            if (res != null) {
                repo.setAccess(code, res.config)  // keeps the node IP current for the next connect
                repo.setCabinet(res.info)
                // Burn signal: probe node reachability from this device, but only while OFF the
                // tunnel (else the probe rides the tunnel and measures nothing useful).
                if (TunnelController.state.value == VpnState.Disconnected) {
                    withContext(Dispatchers.IO) {
                        val results = NodeProbe.probe(res.info.servers)
                        if (results.isNotEmpty()) AccountClient.reportHealth(code, results)
                    }
                }
            }
            _busy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch { repo.clearAccess() }
    }
}
