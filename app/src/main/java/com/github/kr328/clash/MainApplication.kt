package com.github.kr328.clash

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.github.kr328.clash.design.R as DesignR

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
        installLumenCrashSdk()
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName

        Log.d("Process $processName started")

        if (processName == packageName) {
            // Geo assets are consumed by the core, which only runs in the main process.
            // Extracting them here (instead of unconditionally for every process) avoids
            // duplicate main-thread I/O in the :background process on every launch.
            extractGeoFiles()
            maybeMigrateFromAlpha()
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun installLumenCrashSdk() {
        if (LumenCrash.isInstalled()) return

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
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

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
