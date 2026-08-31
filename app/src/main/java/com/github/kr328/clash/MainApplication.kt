package com.github.kr328.clash

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.chloemlla.lumen.crash.CrashBreadcrumbs
import com.chloemlla.lumen.crash.LumenCrash
import com.chloemlla.lumen.crash.CrashReport
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
import com.github.kr328.clash.util.onLumenCrashSaved
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
        // throw out of attachBaseContext (author integrity is fail-closed in the SDK).
        //
        // Published AARs expose LumenCrash.install(...). installSafely() is only on newer
        // unreleased source; always wrap install in runCatching so cold start survives.
        val alreadyInstalled = runCatching { LumenCrash.isInstalled() }.getOrDefault(false)
        if (alreadyInstalled) return

        runCatching {
            val appName = runCatching {
                getString(DesignR.string.application_name)
            }.getOrDefault("Clash Meta for Android")
            val versionName = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("unknown")
            val versionCode = runCatching { BuildConfig.VERSION_CODE }.getOrDefault(0)
            val commitHash = runCatching { BuildConfig.COMMIT_HASH }.getOrDefault("unknown")

            // Prefer SDK-owned FileProvider authority so share-as-file works without
            // host path-xml entries. Host ${applicationId}.fileprovider still exists
            // for other app features.
            LumenCrash.install(
                this,
                LumenCrashConfig(
                    appDisplayName = appName,
                    versionName = versionName,
                    versionCode = versionCode,
                    commitHash = commitHash,
                    fileProviderAuthority = "$packageName.lumen.crash.fileprovider",
                    shareSubject = runCatching {
                        getString(DesignR.string.crash_report_share_subject)
                    }.getOrNull(),
                    reportTitle = runCatching {
                        getString(DesignR.string.crash_report_title)
                    }.getOrNull(),
                    reportMessage = runCatching {
                        getString(DesignR.string.crash_report_message)
                    }.getOrNull(),
                    onCrashSaved = { report: CrashReport ->
                        runCatching { onLumenCrashSaved(report.reportId) }
                    },
                ),
            )
        }.onFailure { error ->
            runCatching {
                System.err.println("LumenCrash install failed: ${error.message}")
            }
        }
    }

    private fun recordBreadcrumbSafe(event: String) {
        runCatching {
            if (!LumenCrash.isInstalled()) return
            CrashBreadcrumbs.record(event)
        }
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

                // B-77: persist which lastUpdateTime we have already released. Comparing file mtime
                // to updateDate is wrong: a user-imported database newer than the APK would be
                // treated as "stale built-in asset" and silently replaced on the next update.
                val stampFile = File(clashDir, ASSETS_STAMP_FILE)
                val releasedAt = runCatching { stampFile.readText().trim().toLong() }.getOrDefault(0L)
                val needsRefresh = releasedAt < updateDate

                GEO_ASSETS.forEach { asset -> extractAsset(asset, updateDate, needsRefresh) }

                // Advance the stamp only when every bundled asset is present, so a failed
                // extraction (e.g. low storage) is retried on the next launch instead of being
                // skipped forever. User-imported databases (`.user` marker) count as present.
                val allReleased = GEO_ASSETS.all { asset ->
                    File(clashDir, asset).exists() || File(clashDir, "$asset.user").exists()
                }
                if (needsRefresh && allReleased) {
                    runCatching { stampFile.writeText(updateDate.toString()) }
                }
            }
        }
    }

    /**
     * Extract a bundled asset into [clashDir] atomically.
     *
     * A stale copy (older than the last package update) is refreshed only when [forceRefresh]
     * (i.e. the [ASSETS_STAMP_FILE] says this build has not been released yet). The copy goes to a
     * temporary file first and is renamed into place only after it fully completes, so an
     * interrupted write (process death, low storage) never leaves a truncated file that the
     * `exists()` guard would otherwise treat as valid and never repair.
     */
    private fun extractAsset(name: String, updateDate: Long, forceRefresh: Boolean) {
        val target = File(clashDir, name)

        // A user-imported database carries a `.user` marker; never delete or overwrite it,
        // no matter how old the file mtime looks relative to the package update.
        if (File(clashDir, "$name.user").exists()) return

        if (forceRefresh && target.exists() && target.lastModified() < updateDate) {
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

        // Records the lastUpdateTime whose bundled geo assets were extracted. User-imported
        // databases are marked with a sibling "<name>.user" file and are never overwritten.
        private const val ASSETS_STAMP_FILE = "assets_stamp"
    }
}
