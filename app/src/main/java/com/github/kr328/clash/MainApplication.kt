package com.github.kr328.clash

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.chloemlla.lumen.crash.CrashBreadcrumbs
import com.chloemlla.lumen.crash.LumenCrash
import com.chloemlla.lumen.crash.LumenCrashConfig
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.constants.Migration
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.migration.AlphaDataMigrator
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.clashDir
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.github.kr328.clash.design.R as DesignR

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        // LumenCrash must be first host startup work: uncaught handler + pending report
        // store must be live before Global/Remote/geo/migration can throw.
        installLumenCrashSdk()
        Global.init(this)
        recordBreadcrumbSafe("Application.attachBaseContext")
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName

        Log.d("Process $processName started")
        recordBreadcrumbSafe("Application.onCreate process=$processName")

        // Geo assets are large; never copy them on the main thread. Both the UI process
        // and :background may need them (core/VPN runs in :background), so extract is
        // idempotent and cross-process locked via a lock file.
        Global.launch(Dispatchers.IO) {
            extractGeoFiles()
        }

        if (processName == packageName) {
            maybeMigrateFromAlpha()
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun installLumenCrashSdk() {
        // First-boot critical path. Must not depend on Global/Remote and must not
        // throw out of attachBaseContext (integrity mismatch is fail-closed in SDK).
        if (LumenCrash.isInstalled()) return

        runCatching {
            val appName = runCatching {
                getString(DesignR.string.application_name)
            }.getOrDefault("Clash Meta for Android")

            LumenCrash.install(
                this,
                LumenCrashConfig(
                    appDisplayName = appName,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    commitHash = "unknown",
                    fileProviderAuthority = "$packageName.fileprovider",
                    shareSubject = runCatching {
                        getString(DesignR.string.crash_report_share_subject)
                    }.getOrNull(),
                    reportTitle = runCatching {
                        getString(DesignR.string.crash_report_title)
                    }.getOrNull(),
                    reportMessage = runCatching {
                        getString(DesignR.string.crash_report_message)
                    }.getOrNull(),
                ),
            )
        }.onFailure { error ->
            // Avoid Log.* here: logging stack may not be ready this early.
            // Best-effort stderr only; never rethrow.
            runCatching {
                System.err.println("LumenCrash install failed: ${error.message}")
            }
        }
    }

    private fun recordBreadcrumbSafe(event: String) {
        if (!LumenCrash.isInstalled()) return
        runCatching { CrashBreadcrumbs.record(event) }
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val lockFile = File(clashDir, ".geo-extract.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                val updateDate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    ).lastUpdateTime
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).lastUpdateTime
                }

                GEO_ASSETS.forEach { asset -> extractAsset(asset, updateDate) }
            }
        }
    }

    /**
     * Extract a bundled asset into [clashDir] atomically.
     *
     * A stale copy (older than the last package update) is refreshed. The copy goes to a
     * temporary file first and is renamed into place only after it fully completes, so an
     * interrupted write (process death, low storage) never leaves a truncated file that the
     * `exists()` guard would otherwise treat as valid and never repair.
     */
    private fun extractAsset(name: String, updateDate: Long) {
        val target = File(clashDir, name)
        if (target.exists() && target.lastModified() < updateDate) {
            target.delete()
        }
        if (target.exists()) return

        val temp = File(clashDir, "$name.tmp")
        try {
            FileOutputStream(temp).use { output ->
                assets.open(name).use { it.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } catch (e: Exception) {
            Log.w("Failed to extract geo asset $name: $e", e)
        } finally {
            temp.delete()
        }
    }

    private fun maybeMigrateFromAlpha() {
        if (!Migration.isMetaPackage(packageName)) return

        Global.launch(Dispatchers.IO) {
            val result = AlphaDataMigrator.maybeImportFromAlpha(this@MainApplication)
            if (result.status == AlphaDataMigrator.Status.Imported && result.totalProfiles > 0) {
                AppStore(this@MainApplication).apply {
                    alphaMigrationToastPending = true
                    alphaMigrationImportedCount = result.totalProfiles
                }
            }
        }
    }

    companion object {
        // Bundled geo assets consumed by the core, extracted on first launch after an update.
        private val GEO_ASSETS = listOf(
            "geoip.metadb",
            "geosite.dat",
            "ASN.mmdb",
            "BundleMRS.7z",
        )
    }
}
