package com.github.kr328.clash

import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.AuditReportDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.AuditReportImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuditReportActivity : BaseActivity<AuditReportDesign>() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val authorized = it.resultCode == RESULT_OK && VpnService.prepare(this) == null
        if (authorized) recordVpnAuthorization()
        design?.updateAuthorization(authorized)
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        launch {
            design?.updateReport(getString(R.string.audit_importing))
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    withContext(Dispatchers.IO) { AuditReportImporter.import(this@AuditReportActivity, input) }
                } ?: error("Unable to open report")
            }.onSuccess { summary ->
                val gaps = summary.limitations.joinToString(separator = "\n") { "- $it" }
                design?.updateReport(buildString {
                    append(getString(R.string.audit_report_summary, summary.packageName, summary.evidenceFiles.size, summary.limitations.size))
                    if (gaps.isNotBlank()) append("\n\n").append(gaps)
                })
            }.onFailure {
                design?.updateReport(getString(R.string.audit_import_failed, it.message ?: "invalid report"))
            }
        }
    }

    override suspend fun main() {
        val reportDesign = AuditReportDesign(this, hasVpnAuthorization())
        setContentDesign(reportDesign)
        while (isActive) {
            when (reportDesign.requests.receive()) {
                AuditReportDesign.Request.AuthorizeVpn -> confirmVpnAuthorization()
                AuditReportDesign.Request.OpenVpnSettings -> {
                    runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        .onFailure { reportDesign.updateAuthorization(false, settingsUnavailable = true) }
                }
                AuditReportDesign.Request.ImportReport -> picker.launch(
                    arrayOf("application/zip", "application/jsonl", "application/json", "text/plain"),
                )
            }
        }
    }

    private fun confirmVpnAuthorization() {
        AlertDialog.Builder(this)
            .setTitle(R.string.audit_consent_title)
            .setMessage(R.string.audit_consent_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_) { _, _ -> requestVpnAuthorization() }
            .show()
    }

    private fun requestVpnAuthorization() {
        val request = VpnService.prepare(this)
        if (request == null) {
            recordVpnAuthorization()
            design?.updateAuthorization(true)
        } else {
            vpnPermission.launch(request)
        }
    }

    private fun hasVpnAuthorization(): Boolean =
        getPreferences(MODE_PRIVATE).contains(AUTHORIZED_AT) && VpnService.prepare(this) == null

    private fun recordVpnAuthorization() {
        getPreferences(MODE_PRIVATE).edit().putLong(AUTHORIZED_AT, System.currentTimeMillis()).apply()
    }

    companion object {
        private const val AUTHORIZED_AT = "audit_vpn_authorized_at"
    }
}
