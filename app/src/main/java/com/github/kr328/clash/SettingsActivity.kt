package com.github.kr328.clash

import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.SettingsDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.migration.MigrationBundle
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.DataBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

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
                            val target = startActivityForResult(
                                ActivityResultContracts.CreateDocument("application/zip"),
                                "clash-backup-${System.currentTimeMillis()}.zip",
                            )
                            if (target != null) exportBackup(design, target)
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

            withContext(Dispatchers.IO) {
                (ImportedDao().queryAllUUIDs() + PendingDao().queryAllUUIDs())
                    .distinct()
                    .forEach(this@SettingsActivity::sendProfileChanged)
            }
            sendServiceRecreated()
            design.showToast(
                getString(DesignR.string.backup_restored, result.totalProfiles),
                ToastDuration.Long,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.showToast(
                getString(DesignR.string.backup_operation_failed, e.message ?: getString(DesignR.string.error)),
                ToastDuration.Long,
            )
        }
    }
}
