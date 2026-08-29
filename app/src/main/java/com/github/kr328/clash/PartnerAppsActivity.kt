package com.github.kr328.clash

import android.content.pm.PackageManager
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.common.constants.PartnerTrust
import com.github.kr328.clash.design.PartnerAppsDesign
import com.github.kr328.clash.design.model.PartnerAppInfo
import com.github.kr328.clash.design.model.PartnerAuthorization
import com.github.kr328.clash.service.store.PartnerGrantDecision
import com.github.kr328.clash.service.store.PartnerGrantStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class PartnerAppsActivity : BaseActivity<PartnerAppsDesign>() {
    override suspend fun main() {
        val design = PartnerAppsDesign(this)

        setContentDesign(design)

        val grants = PartnerGrantStore(this)

        refresh(design, grants)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ClashStart, Event.ClashStop ->
                            refresh(design, grants)
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is PartnerAppsDesign.Request.Allow ->
                            decide(grants, it.packageName, allow = true)
                        is PartnerAppsDesign.Request.Deny ->
                            decide(grants, it.packageName, allow = false)
                        is PartnerAppsDesign.Request.Revoke ->
                            withContext(Dispatchers.IO) { grants.revoke(it.packageName) }
                    }

                    refresh(design, grants)
                }
            }
        }
    }

    private suspend fun refresh(design: PartnerAppsDesign, grants: PartnerGrantStore) {
        val apps = loadApps(grants)

        design.patchApps(apps)
        design.setTunnelSummary(clashRunning, apps.count { it.tunneled })
    }

    /**
     * Binds the decision to the certificate the app presents right now, so re-signing it drops the
     * grant instead of carrying the owner's trust over to a different key.
     */
    private suspend fun decide(grants: PartnerGrantStore, target: String, allow: Boolean) {
        withContext(Dispatchers.IO) {
            val sha256 = PartnerApps.signerDigestsOf(this@PartnerAppsActivity, target)?.sha256
                ?: return@withContext

            grants.decide(target, sha256, allow = allow, remember = true)
        }
    }

    private suspend fun loadApps(grants: PartnerGrantStore): List<PartnerAppInfo> =
        withContext(Dispatchers.IO) {
            val pm = packageManager
            val tunneled = grants.tunneledPackages
            val pending = grants.pendingRequests().mapTo(mutableSetOf()) { it.packageName }

            PartnerApps.installedCandidatePackages(this@PartnerAppsActivity)
                .asSequence()
                .filter { it != packageName }
                .map { target ->
                    val digests = PartnerApps.signerDigestsOf(this@PartnerAppsActivity, target)
                    val decision = digests
                        ?.let { grants.decisionOf(target, it.sha256) }
                        ?: PartnerGrantDecision.Unknown

                    PartnerAppInfo(
                        packageName = target,
                        label = labelOf(pm, target),
                        certificateSha256 = digests?.sha256,
                        signerVerified = PartnerApps.trustOf(this@PartnerAppsActivity, target) ==
                            PartnerTrust.Verified,
                        authorization = when {
                            decision == PartnerGrantDecision.Allowed -> PartnerAuthorization.Allowed
                            decision == PartnerGrantDecision.Denied -> PartnerAuthorization.Denied
                            target in pending -> PartnerAuthorization.Pending
                            else -> PartnerAuthorization.Undecided
                        },
                        tunneled = target in tunneled,
                    )
                }
                .sortedWith(
                    compareByDescending<PartnerAppInfo> {
                        it.authorization == PartnerAuthorization.Pending
                    }
                        .thenByDescending { it.tunneled }
                        .thenBy { it.label }
                )
                .toList()
        }

    private fun labelOf(pm: PackageManager, target: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
    }.getOrDefault(target)
}
