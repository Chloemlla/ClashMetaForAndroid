package com.github.kr328.clash.design.dialog

import android.content.Context
import android.view.View
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** The owner's answer to a pairing request; [remember] persists it beyond the current attempt. */
data class PartnerPairingAnswer(val allow: Boolean, val remember: Boolean)

/**
 * Asks the owner whether [packageName] may read the detailed Clash status. Returns null when the
 * dialog is dismissed without an answer, which leaves the request pending instead of denying it.
 */
suspend fun Context.requestPartnerPairing(
    label: String,
    packageName: String,
    certificateSha256: String?,
    signerVerified: Boolean,
): PartnerPairingAnswer? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val view = layoutInflater.inflate(R.layout.dialog_partner_pairing, null)
        val remember = view.findViewById<MaterialCheckBox>(R.id.remember_view)
        val certificateView = view.findViewById<TextView>(R.id.certificate_view)

        view.findViewById<TextView>(R.id.message_view).text =
            getString(R.string.format_partner_pairing_message, label)
        view.findViewById<TextView>(R.id.package_view).text = packageName
        certificateView.text = certificateSha256
            ?.let { getString(R.string.format_partner_certificate, it) }
            .orEmpty()
        certificateView.visibility = if (certificateSha256 == null) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.hint_view).visibility =
            if (signerVerified) View.GONE else View.VISIBLE

        var answer: PartnerPairingAnswer? = null

        MaterialAlertDialogBuilder(this@requestPartnerPairing)
            .setTitle(R.string.partner_pairing_title)
            .setView(view)
            .setPositiveButton(R.string.partner_action_allow) { _, _ ->
                answer = PartnerPairingAnswer(allow = true, remember = remember.isChecked)
            }
            .setNegativeButton(R.string.partner_action_deny) { _, _ ->
                answer = PartnerPairingAnswer(allow = false, remember = remember.isChecked)
            }
            .setOnDismissListener {
                if (!continuation.isCompleted) continuation.resume(answer)
            }
            .show()
    }
}
