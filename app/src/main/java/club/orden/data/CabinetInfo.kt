package club.orden.data

/** A server location the app surfaces. `ip` (node egress IP) lets the app map its measured
 *  egress back to this location's label in the connected-details card. */
data class ServerOption(val id: String, val label: String, val ip: String = "")

/**
 * Snapshot of the user's account, shown in the in-app cabinet. Populated from the Orden
 * backend's `/account` (and `/redeem`) JSON — no more subscription-header parsing.
 * -1 means "unknown / not advertised"; 0 keeps its literal meaning (unlimited / never).
 */
data class CabinetInfo(
    val plan: String?,          // tier name, or null
    val usedBytes: Long,        // upload + download, -1 if unknown
    val totalBytes: Long,       // quota, 0 = unlimited, -1 if unknown
    val expireEpoch: Long,      // unix seconds, 0 = never, -1 if unknown
    val ref: String,            // this account's referral code ("" if none)
    val refCount: Int,          // activated referrals
    val refDays: Int,           // bonus days earned from referrals
    val servers: List<ServerOption>,
)
