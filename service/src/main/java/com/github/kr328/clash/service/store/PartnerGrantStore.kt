package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider

/**
 * One device-owner decision about a partner app, keyed by the certificate it presented when the
 * decision was made. Re-signing the app changes [sha256] and therefore silently invalidates the
 * decision instead of carrying trust over to a different key.
 *
 * [expiresAtMillis] is 0 for a remembered decision and a wall-clock deadline for a one-shot one.
 */
data class PartnerGrant(
    val packageName: String,
    val sha256: String,
    val expiresAtMillis: Long = 0L,
) {
    fun isValidAt(nowMillis: Long): Boolean = expiresAtMillis == 0L || nowMillis < expiresAtMillis

    internal fun encode(): String = "$packageName|$sha256|$expiresAtMillis"

    internal companion object {
        fun decode(raw: String): PartnerGrant? {
            val parts = raw.split('|')
            if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                return null
            }
            return PartnerGrant(
                packageName = parts[0],
                sha256 = parts[1],
                expiresAtMillis = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
            )
        }
    }
}

enum class PartnerGrantDecision { Unknown, Allowed, Denied }

/**
 * Cross-process record of which partner apps the device owner let read Clash status, plus the
 * queue of apps still waiting for an answer.
 *
 * This is the trust source that replaces "must share CMFA's signing key": every suite app is
 * signed with its own key, so a signature match never holds in practice, and pinning digests
 * couples every new partner to a CMFA release. An explicit grant needs neither, and it is
 * strictly narrower than the hardcoded allowlist because it is bound to one certificate.
 */
class PartnerGrantStore(context: Context) {
    private val store = Store(
        PreferenceProvider.createSharedPreferencesFromContext(context).asStoreProvider()
    )

    private var allowedEntries by store.stringSet(
        key = "partner_grants_allowed",
        defaultValue = emptySet(),
    )

    private var deniedEntries by store.stringSet(
        key = "partner_grants_denied",
        defaultValue = emptySet(),
    )

    private var pendingEntries by store.stringSet(
        key = "partner_grants_pending",
        defaultValue = emptySet(),
    )

    private var tunneledEntries by store.stringSet(
        key = "partner_tunneled",
        defaultValue = emptySet(),
    )

    /**
     * Partner apps whose traffic the *running* tunnel actually carries, as decided when the VPN was
     * established. Empty while the tunnel is down, so the partner list can say "接管中" from a fact
     * rather than re-deriving it from access-control settings that may have changed since.
     */
    var tunneledPackages: Set<String>
        get() = tunneledEntries
        set(value) {
            tunneledEntries = value
        }

    fun decisionOf(packageName: String, sha256: String): PartnerGrantDecision {
        val now = System.currentTimeMillis()
        if (matches(allowedEntries, packageName, sha256, now)) {
            return PartnerGrantDecision.Allowed
        }
        if (matches(deniedEntries, packageName, sha256, now)) {
            return PartnerGrantDecision.Denied
        }
        return PartnerGrantDecision.Unknown
    }

    /**
     * Records the owner's answer. [remember] false keeps it for [TRANSIENT_GRANT_MILLIS] only, so
     * declining to remember still lets the partner finish what it was doing.
     */
    fun decide(packageName: String, sha256: String, allow: Boolean, remember: Boolean) {
        val expiresAt = if (remember) 0L else System.currentTimeMillis() + TRANSIENT_GRANT_MILLIS
        val grant = PartnerGrant(packageName, sha256, expiresAt)
        allowedEntries = allowedEntries.without(packageName).let {
            if (allow) it + grant.encode() else it
        }
        deniedEntries = deniedEntries.without(packageName).let {
            if (allow) it else it + grant.encode()
        }
        clearPending(packageName)
    }

    /** Drops every decision about [packageName], returning it to "not yet asked". */
    fun revoke(packageName: String) {
        allowedEntries = allowedEntries.without(packageName)
        deniedEntries = deniedEntries.without(packageName)
        clearPending(packageName)
    }

    /** Valid grants, expired entries pruned from storage as a side effect. */
    fun grants(): List<PartnerGrant> {
        val now = System.currentTimeMillis()
        val valid = allowedEntries.mapNotNull(PartnerGrant::decode).filter { it.isValidAt(now) }
        val encoded = valid.mapTo(mutableSetOf()) { it.encode() }
        if (encoded != allowedEntries) {
            allowedEntries = encoded
        }
        return valid
    }

    fun grantedPackages(): Set<String> = grants().mapTo(mutableSetOf()) { it.packageName }

    /**
     * Queues [packageName] for an approval prompt. Returns true only when this is a new request,
     * so a partner polling status every few seconds does not re-notify the owner each time.
     */
    fun requestPairing(packageName: String, sha256: String): Boolean {
        val entry = PartnerGrant(packageName, sha256).encode()
        if (entry in pendingEntries) {
            return false
        }
        pendingEntries = pendingEntries.without(packageName) + entry
        return true
    }

    fun pendingRequests(): List<PartnerGrant> = pendingEntries.mapNotNull(PartnerGrant::decode)

    fun clearPending(packageName: String) {
        val remaining = pendingEntries.without(packageName)
        if (remaining != pendingEntries) {
            pendingEntries = remaining
        }
    }

    private fun matches(
        entries: Set<String>,
        packageName: String,
        sha256: String,
        nowMillis: Long,
    ): Boolean = entries.mapNotNull(PartnerGrant::decode).any {
        it.packageName == packageName && it.sha256 == sha256 && it.isValidAt(nowMillis)
    }

    private fun Set<String>.without(packageName: String): Set<String> =
        filterNotTo(mutableSetOf()) { PartnerGrant.decode(it)?.packageName == packageName }

    companion object {
        /** Lifetime of a "just this once" answer. */
        const val TRANSIENT_GRANT_MILLIS = 10 * 60 * 1000L
    }
}
