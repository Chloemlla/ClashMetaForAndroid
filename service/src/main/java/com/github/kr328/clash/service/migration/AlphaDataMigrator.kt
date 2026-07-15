package com.github.kr328.clash.service.migration

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.github.kr328.clash.common.constants.Migration
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AlphaDataMigrator {
    private const val PREFS_NAME = "migration"
    private const val KEY_ATTEMPTED = "alpha_import_attempted"
    private const val KEY_COMPLETED = "alpha_import_completed"
    private const val KEY_SOURCE = "alpha_import_source"
    private const val KEY_IMPORTED = "alpha_import_count"
    private const val KEY_RETRIES = "alpha_import_retries"
    private const val MAX_RETRIES = 5

    private val lock = Mutex()

    data class Result(
        val status: Status,
        val sourcePackage: String? = null,
        val importedProfiles: Int = 0,
        val pendingProfiles: Int = 0,
        val message: String? = null,
    ) {
        val totalProfiles: Int
            get() = importedProfiles + pendingProfiles
    }

    enum class Status {
        SkippedNotMeta,
        SkippedAlreadyDone,
        SkippedHasLocalData,
        SkippedNoAlpha,
        Imported,
        Failed,
    }

    suspend fun maybeImportFromAlpha(context: Context): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            if (!Migration.isMetaPackage(app.packageName)) {
                return@withLock Result(Status.SkippedNotMeta)
            }

            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_COMPLETED, false) || prefs.getBoolean(KEY_ATTEMPTED, false)) {
                return@withLock Result(
                    Status.SkippedAlreadyDone,
                    sourcePackage = prefs.getString(KEY_SOURCE, null),
                    importedProfiles = prefs.getInt(KEY_IMPORTED, 0),
                )
            }

            // Ensure DB is initialized before counting local profiles.
            Database.database
            val localCount = ImportedDao().queryAllUUIDs().size + PendingDao().queryAllUUIDs().size
            if (localCount > 0) {
                prefs.edit()
                    .putBoolean(KEY_ATTEMPTED, true)
                    .putBoolean(KEY_COMPLETED, true)
                    .apply()
                return@withLock Result(Status.SkippedHasLocalData)
            }

            prefs.edit().putBoolean(KEY_ATTEMPTED, true).apply()

            val sourcePackage = findMigratableAlphaPackage(app)
            if (sourcePackage == null) {
                prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
                return@withLock Result(Status.SkippedNoAlpha)
            }

            val temp = app.cacheDir.resolve("alpha-migration-import.zip")
            try {
                if (!copyBundleFromPackage(app, sourcePackage, temp)) {
                    // Alpha is installed but cannot export yet; retry after Alpha updates.
                    prefs.edit().putBoolean(KEY_ATTEMPTED, false).apply()
                    return@withLock Result(
                        Status.Failed,
                        sourcePackage = sourcePackage,
                        message = "export_unavailable",
                    )
                }

                val imported = MigrationBundle.importFromZip(app, temp)
                if (imported.skipped && imported.totalProfiles == 0) {
                    prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
                    return@withLock Result(
                        Status.Failed,
                        sourcePackage = sourcePackage,
                        message = imported.reason,
                    )
                }

                prefs.edit()
                    .putBoolean(KEY_COMPLETED, true)
                    .putString(KEY_SOURCE, imported.sourcePackage ?: sourcePackage)
                    .putInt(KEY_IMPORTED, imported.totalProfiles)
                    .apply()

                // Notify UI processes that profiles are available after cross-package import.
                ImportedDao().queryAllUUIDs().forEach { app.sendProfileChanged(it) }
                PendingDao().queryAllUUIDs().forEach { app.sendProfileChanged(it) }

                Result(
                    Status.Imported,
                    sourcePackage = imported.sourcePackage ?: sourcePackage,
                    importedProfiles = imported.importedProfiles,
                    pendingProfiles = imported.pendingProfiles,
                )
            } catch (e: Exception) {
                // Do not mark migration permanently complete on an unexpected/transient
                // failure (low storage, DB init, IO). Reset KEY_ATTEMPTED so the next
                // launch retries, bounded by KEY_RETRIES to avoid an infinite retry loop.
                Log.w("Alpha migration failed: $e", e)
                val retries = prefs.getInt(KEY_RETRIES, 0) + 1
                if (retries >= MAX_RETRIES) {
                    prefs.edit()
                        .putBoolean(KEY_COMPLETED, true)
                        .putInt(KEY_RETRIES, retries)
                        .apply()
                } else {
                    prefs.edit()
                        .putBoolean(KEY_ATTEMPTED, false)
                        .putInt(KEY_RETRIES, retries)
                        .apply()
                }
                Result(Status.Failed, sourcePackage = sourcePackage, message = e.message)
            } finally {
                temp.delete()
            }
        }
    }

    private fun findMigratableAlphaPackage(context: Context): String? {
        val pm = context.packageManager
        return Migration.alphaPackageCandidates(context.packageName).firstOrNull { packageName ->
            try {
                pm.getPackageInfo(packageName, 0)
                pm.checkSignatures(context.packageName, packageName) >= 0
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun copyBundleFromPackage(context: Context, packageName: String, output: File): Boolean {
        val uri = Uri.parse(Migration.bundleUri(packageName))
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { out -> input.copyTo(out) }
            } != null && output.isFile && output.length() > 0L
        } catch (e: Exception) {
            Log.w("Unable to read migration bundle from $packageName: $e", e)
            false
        }
    }
}
