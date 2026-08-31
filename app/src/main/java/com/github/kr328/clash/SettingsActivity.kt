package com.github.kr328.clash

import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.SettingsDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.migration.MigrationBundle
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.DataBackup
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class SettingsActivity : BaseActivity<SettingsDesign>() {
    override suspend fun main() {
        val design = SettingsDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        SettingsDesign.Request.StartApp ->
                            startActivity(AppSettingsActivity::class.intent)
                        SettingsDesign.Request.StartNetwork ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        SettingsDesign.Request.StartAutomation ->
                            startActivity(AutomationSettingsActivity::class.intent)
                        SettingsDesign.Request.StartOverride ->
                            startActivity(OverrideSettingsActivity::class.intent)
                        SettingsDesign.Request.StartMetaFeature ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                        SettingsDesign.Request.StartAuditReport ->
                            startActivity(AuditReportActivity::class.intent)
                        SettingsDesign.Request.BackupBeforeUninstall -> {
                            // B-18: the export is plaintext and contains subscription credentials.
                            // Warn before the SAF picker so the user knows not to share it.
                            MaterialAlertDialogBuilder(this)
                                .setTitle(DesignR.string.backup_sensitive_title)
                                .setMessage(DesignR.string.backup_sensitive_message)
                                .setPositiveButton(DesignR.string.continue_) { _, _ ->
                                    launch {
                                        val target = startActivityForResult(
                                            ActivityResultContracts.CreateDocument("application/zip"),
                                            "clash-backup-${System.currentTimeMillis()}.zip",
                                        )
                                        if (target != null) exportBackup(design, target)
                                    }
                                }
                                .setNegativeButton(DesignR.string.cancel, null)
                                .show()
                        }
                        SettingsDesign.Request.RestoreAfterReinstall -> {
                            val source = startActivityForResult(
                                ActivityResultContracts.OpenDocument(),
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                            if (source != null) restoreBackup(design, source)
                        }
                    }
                }
            }
        }
    }

    private suspend fun exportBackup(design: SettingsDesign, target: android.net.Uri) {
        try {
            withModelProgressBar {
                configure {
                    isIndeterminate = true
                    text = getString(DesignR.string.backup_exporting)
                }
                DataBackup.export(this@SettingsActivity, target)
            }
            design.showToast(DesignR.string.backup_exported, ToastDuration.Long)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.showToast(
                getString(DesignR.string.backup_operation_failed, e.message ?: getString(DesignR.string.error)),
                ToastDuration.Long,
            )
        }
    }

    private suspend fun restoreBackup(design: SettingsDesign, source: android.net.Uri) {
        try {
            // B-17: restoring while :background holds the old pref cache and reads config files
            // can leave the restore partially overwritten. Stop the core first, then bring it back
            // up so the imported data is the only writer.
            val wasRunning = clashRunning
            if (wasRunning) {
                stopClashService()
                delay(RESTORE_STOP_SETTLE_MILLIS)
            }

            var restored: MigrationBundle.ImportResult? = null
            withModelProgressBar {
                configure {
                    isIndeterminate = true
                    text = getString(DesignR.string.backup_restoring)
                }
                restored = DataBackup.import(this@SettingsActivity, source)
            }
            val result = checkNotNull(restored)

            if (result.skipped) {
                design.showToast(DesignR.string.backup_restore_invalid, ToastDuration.Long)
                return
            }

            // B-85: broadcasting one ProfileChanged per restored uuid is a self-inflicted event
            // storm (each one re-queries the dashboard and re-runs adblock prompts on the home
            // screen). A single coarse-grained ServiceRecreated refreshes every screen that cares.
            sendServiceRecreated()
            design.showToast(
                getString(DesignR.string.backup_restored, result.totalProfiles),
                ToastDuration.Long,
            )

            if (wasRunning) {
                val vpnRequest = startClashService()
                if (vpnRequest != null) {
                    // VPN authorization needs a user-visible screen; surface it so the restored
                    // config actually takes effect.
                    runCatching { startActivity(vpnRequest) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.showToast(
                getString(DesignR.string.backup_operation_failed, e.message ?: getString(DesignR.string.error)),
                ToastDuration.Long,
            )
        }
    }

    private companion object {
        // B-17: how long to let the core tear down before overwriting its prefs/files.
        const val RESTORE_STOP_SETTLE_MILLIS = 500L
    }
}
