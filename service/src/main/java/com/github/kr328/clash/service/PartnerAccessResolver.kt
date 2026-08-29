package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.common.constants.PartnerSignerDigests
import com.github.kr328.clash.common.constants.PartnerTrust
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.PartnerGrantDecision
import com.github.kr328.clash.service.store.PartnerGrantStore

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
 * Two trust sources are combined: the static registry in [PartnerApps] (hardcoded applicationIds
 * and pinned certificate digests) and the device owner's own answer recorded in
 * [PartnerGrantStore]. The owner always has the last word — an explicit denial outranks a pinned
 * certificate — and an app holding a known applicationId with an unregistered key is served the
 * low-sensitivity tier instead of nothing, so a stale digest can no longer take a partner's
 * proxy-following feature offline.
 */
object PartnerAccessResolver {
    const val REASON_NOT_PARTNER = "not_partner"
    const val REASON_PENDING_APPROVAL = "pending_user_approval"
    const val REASON_DENIED_BY_USER = "denied_by_user"
    const val REASON_SIGNER_UNVERIFIED = "signer_unverified"
    const val REASON_NO_SIGNATURE = "no_signature"

    fun resolve(context: Context, callingPackages: Iterable<String>): PartnerAccess {
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
                tier = if (trust == PartnerTrust.Verified) {
                    PartnerAccessTier.Full
                } else {
                    PartnerAccessTier.Denied
                },
                reason = REASON_NO_SIGNATURE,
                packageName = packageName,
                digests = null,
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
                    // A pinned or co-signed key already proves identity, so the prompt only asks
                    // the owner to confirm; access is not withheld while it is unanswered.
                    PartnerTrust.Verified ->
                        PartnerAccess(PartnerAccessTier.Full, null, packageName, digests)
                    PartnerTrust.HardcodedUnverified ->
                        PartnerAccess(
                            PartnerAccessTier.Basic,
                            REASON_SIGNER_UNVERIFIED,
                            packageName,
                            digests,
                        )
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
