package club.orden.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tunnel_settings")

/**
 * Persists the member's access: the secret access CODE (the account credential) and the
 * resolved config blob the tunnel consumes. The code is what the user holds; the config is
 * an internal artifact fetched by redeeming the code.
 */
class SettingsRepository(private val context: Context) {
    private val codeKey = stringPreferencesKey("access_code")
    private val configKey = stringPreferencesKey("subscription_url") // reused: now holds the config blob
    private val cabinetKey = stringPreferencesKey("cabinet_info")
    private val eulaKey = booleanPreferencesKey("eula_accepted")

    /** First-run terms acceptance (B2C private-use + sovereign/SDN exclusion). */
    val eulaAccepted: Flow<Boolean> = context.dataStore.data.map { it[eulaKey] ?: false }

    suspend fun setEulaAccepted() {
        context.dataStore.edit { it[eulaKey] = true }
    }

    /** The access code the user redeemed ("" if none yet). */
    val accessCode: Flow<String> = context.dataStore.data.map { it[codeKey] ?: "" }

    /** Config blob (base64 vless+ss) the tunnel service parses. Reuses the old sub field. */
    val subscriptionUrl: Flow<String> = context.dataStore.data.map { it[configKey] ?: "" }

    suspend fun setAccess(code: String, config: String) {
        context.dataStore.edit {
            it[codeKey] = code
            it[configKey] = config
        }
    }

    /** Tunnel service uses this directly; kept for the debug seed hook too. */
    suspend fun setSubscriptionUrl(value: String) {
        context.dataStore.edit { it[configKey] = value }
    }

    suspend fun clearAccess() {
        context.dataStore.edit {
            it.remove(codeKey)
            it.remove(configKey)
            it.remove(cabinetKey)
        }
    }

    /** Last-known account snapshot, so the cabinet renders instantly and offline. */
    val cabinet: Flow<CabinetInfo?> = context.dataStore.data.map { prefs ->
        prefs[cabinetKey]?.let(::decodeCabinet)
    }

    suspend fun setCabinet(info: CabinetInfo) {
        context.dataStore.edit { it[cabinetKey] = encodeCabinet(info) }
    }

    private fun encodeCabinet(c: CabinetInfo): String = JSONObject().apply {
        put("plan", c.plan ?: JSONObject.NULL)
        put("used", c.usedBytes)
        put("total", c.totalBytes)
        put("expire", c.expireEpoch)
        put("ref", c.ref)
        put("refCount", c.refCount)
        put("refDays", c.refDays)
        put("servers", JSONArray().apply {
            c.servers.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("ip", it.ip)) }
        })
    }.toString()

    private fun decodeCabinet(s: String): CabinetInfo? = runCatching {
        val o = JSONObject(s)
        val servers = mutableListOf<ServerOption>()
        o.optJSONArray("servers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                servers += ServerOption(e.optString("id"), e.optString("label"), e.optString("ip"))
            }
        }
        CabinetInfo(
            plan = if (o.isNull("plan")) null else o.getString("plan"),
            usedBytes = o.getLong("used"),
            totalBytes = o.getLong("total"),
            expireEpoch = o.getLong("expire"),
            ref = o.optString("ref", ""),
            refCount = o.optInt("refCount", 0),
            refDays = o.optInt("refDays", 0),
            servers = servers,
        )
    }.getOrNull()
}
