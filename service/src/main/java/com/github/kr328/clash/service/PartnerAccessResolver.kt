package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.common.constants.PartnerSignerDigests
import com.github.kr328.clash.common.constants.PartnerTrust
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.PartnerGrantDecision
import com.github.kr328.clash.service.store.PartnerGrantStore
import java.util.concurrent.ConcurrentHashMap

/** How much of the exported Clash status one caller may read; ordered by increasing privilege. */
enum class PartnerAccessTier { Denied, Basic, Full }

/**
 * Outcome of one access check, including the machine-readable [reason] handed back to the caller
 * so a partner app can tell "this Clash build has no partner surface" apart from "your key is not
 * registered" and "the owner has not answered yet".
 */
data class PartnerAccess(
    val tier: PartnerAccessTier,
    val reason: String?,
    val packageName: String?,
    val digests: PartnerSignerDigests?,
)

/**
 * Decides what a caller of [StatusProvider] may read.
 *
 * Two trust sources are combined: the pinned release certificate in [PartnerApps] and the device
 * owner's own answer recorded in [PartnerGrantStore]. The owner always has the last word — an
 * explicit denial outranks the pinned certificate. An app that merely claims a known partner
 * applicationId without the pinned certificate is denied until the owner explicitly approves it on
 * the pairing page (B-175); claiming partner status is for the partner-list UI only.
 */
object PartnerAccessResolver {
    const val REASON_NOT_PARTNER = "not_partner"
    const val REASON_PENDING_APPROVAL = "pending_user_approval"
    const val REASON_DENIED_BY_USER = "denied_by_user"
    const val REASON_NO_SIGNATURE = "no_signature"

    /**
     * A short TTL keeps repeated callers (a partner polling on a timer, or a hostile app calling
     * in a loop) from re-running the PackageManager certificate queries and the pairing prompt on
     * every call (B-174). Grants can change via decide/revoke and packages can be reinstalled, so a
     * broadcast-keyed invalidation would be tighter; the TTL bounds how long a stale decision is
     * served.
     */
    private const val RESOLVE_CACHE_TTL_MILLIS = 10_000L

    private data class CachedAccess(val access: PartnerAccess, val timestampMillis: Long)

    private val resolveCache = ConcurrentHashMap<String, CachedAccess>()

    fun resolve(context: Context, callingPackages: Iterable<String>): PartnerAccess {
        val cacheKey = callingPackages.sorted().joinToString("|")
        val now = System.currentTimeMillis()
        resolveCache[cacheKey]?.let { cached ->
            if (now - cached.timestampMillis < RESOLVE_CACHE_TTL_MILLIS) {
                return cached.access
            }
        }

        val access = resolveUncached(context, callingPackages)
        resolveCache[cacheKey] = CachedAccess(access, now)
        return access
    }

    private fun resolveUncached(context: Context, callingPackages: Iterable<String>): PartnerAccess {
        var best = PartnerAccess(PartnerAccessTier.Denied, REASON_NOT_PARTNER, null, null)
        for (packageName in callingPackages) {
            val access = resolveOne(context, packageName)
            if (access.tier > best.tier) {
                best = access
            }
            if (best.tier == PartnerAccessTier.Full) {
                break
            }
        }
        return best
    }

    private fun resolveOne(context: Context, packageName: String): PartnerAccess {
        val trust = PartnerApps.trustOf(context, packageName)
        if (trust == PartnerTrust.NotPartner) {
            return PartnerAccess(PartnerAccessTier.Denied, REASON_NOT_PARTNER, packageName, null)
        }

        val digests = PartnerApps.signerDigestsOf(context, packageName)
            ?: return PartnerAccess(
                // A package with no observable certificate can never be a verified partner,
                // whatever its trust classification claims (B-189).
                PartnerAccessTier.Denied,
                REASON_NO_SIGNATURE,
                packageName,
                null,
            )

        return when (PartnerGrantStore(context).decisionOf(packageName, digests.sha256)) {
            PartnerGrantDecision.Allowed ->
                PartnerAccess(PartnerAccessTier.Full, null, packageName, digests)
            PartnerGrantDecision.Denied ->
                PartnerAccess(
                    PartnerAccessTier.Denied,
                    REASON_DENIED_BY_USER,
                    packageName,
                    digests,
                )
            PartnerGrantDecision.Unknown -> {
                PartnerPairingNotifier.request(context, packageName, digests)
                when (trust) {
                    // The pinned key already proves identity, so the prompt only asks the owner to
                    // confirm; access is not withheld while it is unanswered.
                    PartnerTrust.Verified ->
                        PartnerAccess(PartnerAccessTier.Full, null, packageName, digests)
                    // Claiming a partner applicationId without the pinned certificate grants
                    // nothing (B-175): the prompt above queued the request, so the caller is told
                    // the owner has yet to answer rather than that its key is simply unknown.
                    else ->
                        PartnerAccess(
                            PartnerAccessTier.Denied,
                            REASON_PENDING_APPROVAL,
                            packageName,
                            digests,
                        )
                }
            }
        }
    }

    /**
     * Explains a non-full outcome in logcat. Certificate digests are public (anyone can read them
     * out of an APK), and printing the observed one is what turns "reads no partner status" from a
     * guess into a one-line diagnosis.
     */
    fun logDecision(method: String, access: PartnerAccess) {
        if (access.tier == PartnerAccessTier.Full) {
            return
        }
        Log.d(
            "PartnerAccess: $method tier=${access.tier} reason=${access.reason} " +
                "package=${access.packageName} sha256=${access.digests?.sha256} " +
                "sha1=${access.digests?.sha1}"
        )
    }
}
