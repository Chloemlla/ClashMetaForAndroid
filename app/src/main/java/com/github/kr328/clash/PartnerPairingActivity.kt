package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.common.constants.PartnerTrust
import com.github.kr328.clash.design.dialog.requestPartnerPairing
import com.github.kr328.clash.service.PartnerPairingNotifier
import com.github.kr328.clash.service.store.PartnerGrantDecision
import com.github.kr328.clash.service.store.PartnerGrantStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.kr328.clash.service.R as ServiceR

/**
 * Floating host for the partner pairing prompt. Deliberately not a [BaseActivity]: its window must
 * stay transparent so the dialog appears centered over whatever the user was looking at, which the
 * opaque day/night theme applied by BaseActivity would defeat.
 *
 * Two ways in:
 * - CMFA itself (service notifier or the pairing notification) launches it with the partner
 *   identity in extras. The launcher is our own package, so those extras are trusted.
 * - A recognized partner app raises the prompt over its own UI when it first needs Clash status.
 *   The activity is exported for this, but the identity under scrutiny is derived from the
 *   launching package — never from extras — so an arbitrary app cannot forge a prompt about
 *   someone else's package.
 */
class PartnerPairingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationManagerCompat.from(this).cancel(ServiceR.id.nf_partner_pairing)

        val request = resolveRequest() ?: run {
            finish()
            return
        }
        val (target, sha256) = request

        lifecycleScope.launch {
            // Cross-process SharedPreferences go through the PreferenceProvider ContentProvider;
            // keep the read and the pending-slot write off the main thread like decide() below.
            val store = withContext(Dispatchers.IO) { PartnerGrantStore(this@PartnerPairingActivity) }
            // The owner already answered this (package, certificate) — nothing to ask about.
            if (store.decisionOf(target, sha256) != PartnerGrantDecision.Unknown) {
                finish()
                return@launch
            }
            // Claim the pending slot so the service-side notifier does not double-raise the prompt.
            store.requestPairing(target, sha256)

            val answer = requestPartnerPairing(
                label = labelOf(target),
                packageName = target,
                certificateSha256 = sha256,
                signerVerified = PartnerApps.trustOf(this@PartnerPairingActivity, target) ==
                    PartnerTrust.Verified,
            )

            if (answer != null) {
                withContext(Dispatchers.IO) {
                    store.decide(target, sha256, allow = answer.allow, remember = answer.remember)
                }
            }

            finish()
        }
    }

    /**
     * Resolves which partner is being asked about. A self-launch (internal notifier / notification
     * tap) carries the identity in extras; a partner-app launch is identified from the caller
     * package and is honoured only for a recognized partner.
     */
    private fun resolveRequest(): Pair<String, String>? {
        val launcher = launchedFromPackage()
        if (launcher == null || launcher == packageName) {
            val target = intent?.getStringExtra(PartnerPairingNotifier.EXTRA_PACKAGE)
            val sha256 = intent?.getStringExtra(PartnerPairingNotifier.EXTRA_SHA256)
            return if (target.isNullOrBlank() || sha256.isNullOrBlank()) null else target to sha256
        }
        if (PartnerApps.trustOf(this, launcher) == PartnerTrust.NotPartner) {
            return null
        }
        val digests = PartnerApps.signerDigestsOf(this, launcher) ?: return null
        return launcher to digests.sha256
    }

    @Suppress("DEPRECATION")
    private fun launchedFromPackage(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) getLaunchedFromPackage()
        else getCallingPackage()

    private fun labelOf(target: String): String = runCatching {
        val pm: PackageManager = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
    }.getOrDefault(target)
}
