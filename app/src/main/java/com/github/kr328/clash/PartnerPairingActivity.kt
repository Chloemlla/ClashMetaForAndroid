package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.common.constants.PartnerTrust
import com.github.kr328.clash.design.dialog.requestPartnerPairing
import com.github.kr328.clash.service.PartnerPairingNotifier
import com.github.kr328.clash.service.store.PartnerGrantStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.kr328.clash.service.R as ServiceR

/**
 * Floating host for the partner pairing prompt. Deliberately not a [BaseActivity]: its window must
 * stay transparent so the dialog appears centered over whatever the user was looking at, which the
 * opaque day/night theme applied by BaseActivity would defeat.
 */
class PartnerPairingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationManagerCompat.from(this).cancel(ServiceR.id.nf_partner_pairing)

        val target = intent?.getStringExtra(PartnerPairingNotifier.EXTRA_PACKAGE)
        val sha256 = intent?.getStringExtra(PartnerPairingNotifier.EXTRA_SHA256)

        if (target.isNullOrBlank() || sha256.isNullOrBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val answer = requestPartnerPairing(
                label = labelOf(target),
                packageName = target,
                certificateSha256 = sha256,
                signerVerified = PartnerApps.trustOf(this@PartnerPairingActivity, target) ==
                    PartnerTrust.Verified,
            )

            if (answer != null) {
                withContext(Dispatchers.IO) {
                    PartnerGrantStore(this@PartnerPairingActivity)
                        .decide(target, sha256, allow = answer.allow, remember = answer.remember)
                }
            }

            finish()
        }
    }

    private fun labelOf(target: String): String = runCatching {
        val pm: PackageManager = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
    }.getOrDefault(target)
}
