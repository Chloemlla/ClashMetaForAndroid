package com.github.kr328.clash.service.migration

import android.content.Context
import android.content.SharedPreferences
import com.github.kr328.clash.common.constants.Migration
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object MigrationBundle {
    private val exportLock = Mutex()
    private val importLock = Mutex()

    // Version of the profiles.json shape; independent of the manifest format version so
    // the records can evolve without bumping the whole-bundle format.
    private const val PROFILES_SCHEMA = 1

    // Bounds for extracting a migration bundle. The bundle comes from a same-signature
    // sibling app (enforced by MigrationProvider.enforceCaller), but a corrupt cache or
    // buggy sibling could still supply a crafted zip. Cap per-entry and total extracted
    // bytes plus entry count to prevent a zip bomb from exhausting cache storage.
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    private val bundleJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    data class ImportResult(
        val importedProfiles: Int,
        val pendingProfiles: Int,
        val sourcePackage: String?,
        val skipped: Boolean = false,
        val reason: String? = null,
    ) {
        val totalProfiles: Int
            get() = importedProfiles + pendingProfiles
    }

    suspend fun exportToZip(context: Context, output: File): Boolean = withContext(Dispatchers.IO) {
        exportLock.withLock {
            runCatching {
                output.parentFile?.mkdirs()
                val staging = File(output.parentFile, "${output.name}.tmp")
                staging.delete()

                ZipOutputStream(BufferedOutputStream(FileOutputStream(staging))).use { zip ->
                    writeTextEntry(
                        zip,
                        Migration.MANIFEST_FILE,
                        JSONObject()
                            .put("format", Migration.FORMAT_VERSION)
                            .put("package", context.packageName)
                            .put("exportedAt", System.currentTimeMillis())
                            .toString(),
                    )

                    writeTextEntry(
                        zip,
                        Migration.SERVICE_PREFS_FILE,
                        // "service" prefs are owned by the :background process and read through
                        // PreferenceProvider; access them via the same channel so the export
                        // reflects the authoritative values (A-32).
                        dumpSharedPreferences(PreferenceProvider.createSharedPreferencesFromContext(context)),
                    )
                    writeTextEntry(
                        zip,
                        Migration.UI_PREFS_FILE,
                        dumpSharedPreferences(context.getSharedPreferences("ui", Context.MODE_PRIVATE)),
                    )
                    writeTextEntry(
                        zip,
                        Migration.APP_PREFS_FILE,
                        dumpSharedPreferences(context.getSharedPreferences("app", Context.MODE_PRIVATE)),
                    )

                    writeTextEntry(zip, Migration.PROFILES_FILE, dumpProfilesJson(context))

                    addDirectory(zip, context.importedDir, Migration.IMPORTED_DIR)
                    addDirectory(zip, context.pendingDir, Migration.PENDING_DIR)
                }

                // Swap in only after the archive is fully written so a concurrent reader or
                // a crash mid-export never sees a truncated bundle at the public path.
                if (!staging.renameTo(output)) {
                    throw IOException("failed to move export into place")
                }
                true
            }.onFailure {
                Log.w("Migration export failed: $it", it)
                File(output.parentFile, "${output.name}.tmp").delete()
            }.getOrDefault(false)
        }
    }

    suspend fun importFromZip(context: Context, input: File): ImportResult = withContext(Dispatchers.IO) {
        importLock.withLock {
            if (!input.isFile || input.length() == 0L) {
                return@withLock ImportResult(0, 0, null, skipped = true, reason = "empty")
            }

            val extractRoot = context.cacheDir.resolve("migration-import-${System.currentTimeMillis()}")
            val stagingRoot = context.cacheDir.resolve("migration-import-staging")
            try {
                extractRoot.deleteRecursively()
                extractRoot.mkdirs()
                stagingRoot.deleteRecursively()
                stagingRoot.mkdirs()
                unzip(input, extractRoot)

                val manifest = extractRoot.resolve(Migration.MANIFEST_FILE)
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.let { JSONObject(it) }
                val format = manifest?.optInt("format", 0) ?: 0
                if (format != Migration.FORMAT_VERSION) {
                    return@withLock ImportResult(
                        0,
                        0,
                        manifest?.optString("package"),
                        skipped = true,
                        reason = "unsupported_format",
                    )
                }

                val profilesFile = extractRoot.resolve(Migration.PROFILES_FILE)
                if (!profilesFile.isFile) {
                    return@withLock ImportResult(
                        0,
                        0,
                        manifest?.optString("package"),
                        skipped = true,
                        reason = "missing_profiles",
                    )
                }

                val root = JSONObject(profilesFile.readText())
                val schema = root.optInt("schema", 0)
                if (schema > PROFILES_SCHEMA) {
                    Log.w("Migration: profiles schema $schema is newer than supported $PROFILES_SCHEMA")
                }
                val importedArray = root.optJSONArray("imported") ?: JSONArray()
                val pendingArray = root.optJSONArray("pending") ?: JSONArray()
                val selectionsArray = root.optJSONArray("selections") ?: JSONArray()
                val activeProfile = root.optString("activeProfile").takeIf { it.isNotBlank() }

                var importedCount = 0
                var pendingCount = 0
                val importedRecords = mutableListOf<Imported>()
                val pendingRecords = mutableListOf<Pending>()
                val selectionRecords = mutableListOf<Selection>()

                val seenImported = HashSet<UUID>()
                for (i in 0 until importedArray.length()) {
                    runCatching {
                        val obj = importedArray.getJSONObject(i)
                        val imported = bundleJson.decodeFromString(Imported.serializer(), obj.toString())
                        if (!seenImported.add(imported.uuid)) return@runCatching false
                        if (ImportedDao().exists(imported.uuid) || PendingDao().exists(imported.uuid)) return@runCatching false

                        val sourceDir = extractRoot.resolve(Migration.IMPORTED_DIR).resolve(imported.uuid.toString())
                        if (!sourceDir.isDirectory) return@runCatching false

                        sourceDir.copyRecursively(stagingRoot.resolve(imported.uuid.toString()), overwrite = true)
                        importedRecords += imported
                        true
                    }.onFailure {
                        Log.w("Migration: skipping imported record $i: $it", it)
                    }.getOrDefault(false).let { if (it) importedCount++ }
                }

                val importedUuids = importedRecords.mapTo(HashSet()) { it.uuid }
                val seenPending = HashSet<UUID>()
                for (i in 0 until pendingArray.length()) {
                    runCatching {
                        val obj = pendingArray.getJSONObject(i)
                        val pending = bundleJson.decodeFromString(Pending.serializer(), obj.toString())
                        if (!seenPending.add(pending.uuid)) return@runCatching false
                        if (pending.uuid in importedUuids) return@runCatching false
                        if (ImportedDao().exists(pending.uuid) || PendingDao().exists(pending.uuid)) return@runCatching false

                        val sourceDir = extractRoot.resolve(Migration.PENDING_DIR).resolve(pending.uuid.toString())
                        val staging = stagingRoot.resolve(pending.uuid.toString())
                        if (sourceDir.isDirectory) {
                            sourceDir.copyRecursively(staging, overwrite = true)
                        } else {
                            staging.mkdirs()
                            staging.resolve("config.yaml").createNewFile()
                            staging.resolve("providers").mkdir()
                        }

                        pendingRecords += pending
                        true
                    }.onFailure {
                        Log.w("Migration: skipping pending record $i: $it", it)
                    }.getOrDefault(false).let { if (it) pendingCount++ }
                }

                for (i in 0 until selectionsArray.length()) {
                    runCatching {
                        val obj = selectionsArray.getJSONObject(i)
                        val uuid = UUID.fromString(obj.getString("uuid"))
                        if (uuid !in importedUuids && !ImportedDao().exists(uuid)) return@runCatching
                        selectionRecords += Selection(
                            uuid = uuid,
                            proxy = obj.getString("proxy"),
                            selected = obj.getString("selected"),
                        )
                    }.onFailure {
                        Log.w("Migration: skipping selection record $i: $it", it)
                    }
                }

                // One transaction commits all rows or none, so an interrupted import can
                // never leave half of the profile set visible.
                Database.database.importAll(importedRecords, pendingRecords, selectionRecords)

                // Move the staged directories into place only after the rows are durable.
                context.importedDir.mkdirs()
                context.pendingDir.mkdirs()
                importedRecords.forEach { rec ->
                    val staging = stagingRoot.resolve(rec.uuid.toString())
                    val target = context.importedDir.resolve(rec.uuid.toString())
                    if (staging.isDirectory && !target.exists() && !staging.renameTo(target)) {
                        Log.w("Migration: failed to move staged directory for imported ${rec.uuid}")
                    }
                }
                pendingRecords.forEach { rec ->
                    val staging = stagingRoot.resolve(rec.uuid.toString())
                    val target = context.pendingDir.resolve(rec.uuid.toString())
                    if (staging.isDirectory && !target.exists() && !staging.renameTo(target)) {
                        Log.w("Migration: failed to move staged directory for pending ${rec.uuid}")
                    }
                }

                // A-32: write the imported "service" prefs through the PreferenceProvider
                // channel (it owns the file in :background). A direct write from the main
                // process would be invisible to :background's in-memory cache or clobbered by
                // its next write — "import succeeded but did not take effect".
                mergeSharedPreferences(
                    PreferenceProvider.createSharedPreferencesFromContext(context),
                    extractRoot.resolve(Migration.SERVICE_PREFS_FILE),
                    preserveKeys = emptySet(),
                )
                mergeSharedPreferences(
                    context.getSharedPreferences("ui", Context.MODE_PRIVATE),
                    extractRoot.resolve(Migration.UI_PREFS_FILE),
                    preserveKeys = setOf("hide_app_icon"),
                )
                mergeSharedPreferences(
                    context.getSharedPreferences("app", Context.MODE_PRIVATE),
                    extractRoot.resolve(Migration.APP_PREFS_FILE),
                    preserveKeys = emptySet(),
                )

                if (!activeProfile.isNullOrBlank()) {
                    val activeUuid = runCatching { UUID.fromString(activeProfile) }.getOrNull()
                    if (activeUuid != null && (activeUuid in importedUuids || ImportedDao().exists(activeUuid))) {
                        ServiceStore(context).activeProfile = activeUuid
                    }
                }

                ImportResult(
                    importedProfiles = importedCount,
                    pendingProfiles = pendingCount,
                    sourcePackage = manifest?.optString("package"),
                )
            } catch (e: Exception) {
                Log.w("Migration import failed: $e", e)
                ImportResult(0, 0, null, skipped = true, reason = e.message)
            } finally {
                extractRoot.deleteRecursively()
                stagingRoot.deleteRecursively()
            }
        }
    }

    private suspend fun dumpProfilesJson(context: Context): String {
        val imported = JSONArray()
        val pending = JSONArray()
        val selections = JSONArray()

        ImportedDao().queryAllUUIDs().forEach { uuid ->
            val item = ImportedDao().queryByUUID(uuid) ?: return@forEach
            imported.put(JSONObject(bundleJson.encodeToString(Imported.serializer(), item)))
            SelectionDao().querySelections(uuid).forEach { selection ->
                selections.put(
                    JSONObject()
                        .put("uuid", selection.uuid.toString())
                        .put("proxy", selection.proxy)
                        .put("selected", selection.selected),
                )
            }
        }

        PendingDao().queryAllUUIDs().forEach { uuid ->
            val item = PendingDao().queryByUUID(uuid) ?: return@forEach
            pending.put(JSONObject(bundleJson.encodeToString(Pending.serializer(), item)))
        }

        return JSONObject()
            .put("schema", PROFILES_SCHEMA)
            .put("activeProfile", ServiceStore(context).activeProfile?.toString() ?: "")
            .put("imported", imported)
            .put("pending", pending)
            .put("selections", selections)
            .toString()
    }

    private fun dumpSharedPreferences(prefs: SharedPreferences): String {
        val root = JSONObject()
        // MultiProcessPreference.getAll() returns null when the owning provider is not up;
        // treat that as "no values" rather than crashing the export.
        prefs.all?.forEach { (key, value) ->
            when (value) {
                is Boolean -> root.put(key, JSONObject().put("t", "b").put("v", value))
                is Int -> root.put(key, JSONObject().put("t", "i").put("v", value))
                is Long -> root.put(key, JSONObject().put("t", "l").put("v", value))
                is Float -> root.put(key, JSONObject().put("t", "f").put("v", value.toDouble()))
                is String -> root.put(key, JSONObject().put("t", "s").put("v", value))
                is Set<*> -> {
                    val arr = JSONArray()
                    value.filterIsInstance<String>().forEach { arr.put(it) }
                    root.put(key, JSONObject().put("t", "ss").put("v", arr))
                }
            }
        }
        return root.toString()
    }

    private fun mergeSharedPreferences(
        prefs: SharedPreferences,
        file: File,
        preserveKeys: Set<String>,
    ) {
        if (!file.isFile) return
        val root = JSONObject(file.readText())
        val editor = prefs.edit()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in preserveKeys) continue
            if (prefs.contains(key)) continue
            val item = root.getJSONObject(key)
            when (item.optString("t")) {
                "b" -> editor.putBoolean(key, item.getBoolean("v"))
                "i" -> editor.putInt(key, item.getInt("v"))
                "l" -> editor.putLong(key, item.getLong("v"))
                "f" -> editor.putFloat(key, item.getDouble("v").toFloat())
                "s" -> editor.putString(key, item.getString("v"))
                "ss" -> {
                    val arr = item.getJSONArray("v")
                    val set = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) set += arr.getString(i)
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.isDirectory) return
        directory.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry("$prefix/$relative"))
            FileInputStream(file).use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun unzip(input: File, outputDir: File) {
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(BufferedInputStream(FileInputStream(input))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val root = outputDir.canonicalFile
                val target = outputDir.resolve(entry.name).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    throw IllegalStateException("zip path traversal: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        throw IllegalStateException("migration bundle has too many entries")
                    }
                    target.parentFile?.mkdirs()
                    // Copy with running caps so a malformed or hostile bundle (zip bomb)
                    // cannot exhaust cache storage even though the sender is same-signature.
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            if (entryBytes > MAX_ENTRY_BYTES) {
                                throw IllegalStateException("migration bundle entry exceeds size limit")
                            }
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw IllegalStateException("migration bundle exceeds size limit")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
