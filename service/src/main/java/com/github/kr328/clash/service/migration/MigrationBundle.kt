package com.github.kr328.clash.service.migration

import android.content.Context
import android.content.SharedPreferences
import com.github.kr328.clash.common.constants.Migration
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object MigrationBundle {
    private val exportLock = Mutex()
    private val importLock = Mutex()

    // Bounds for extracting a migration bundle. The bundle comes from a same-signature
    // sibling app (enforced by MigrationProvider.enforceCaller), but a corrupt cache or
    // buggy sibling could still supply a crafted zip. Cap per-entry and total extracted
    // bytes plus entry count to prevent a zip bomb from exhausting cache storage.
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

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
                if (output.exists()) output.delete()

                ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
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
                        dumpSharedPreferences(context.getSharedPreferences("service", Context.MODE_PRIVATE)),
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
                true
            }.onFailure {
                Log.w("Migration export failed: $it", it)
                output.delete()
            }.getOrDefault(false)
        }
    }

    suspend fun importFromZip(context: Context, input: File): ImportResult = withContext(Dispatchers.IO) {
        importLock.withLock {
            if (!input.isFile || input.length() == 0L) {
                return@withLock ImportResult(0, 0, null, skipped = true, reason = "empty")
            }

            val extractRoot = context.cacheDir.resolve("migration-import-${System.currentTimeMillis()}")
            try {
                extractRoot.deleteRecursively()
                extractRoot.mkdirs()
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
                val importedArray = root.optJSONArray("imported") ?: JSONArray()
                val pendingArray = root.optJSONArray("pending") ?: JSONArray()
                val selectionsArray = root.optJSONArray("selections") ?: JSONArray()
                val activeProfile = root.optString("activeProfile").takeIf { it.isNotBlank() }

                var importedCount = 0
                var pendingCount = 0

                // Import each record independently: a single malformed record (unknown
                // Profile.Type from a newer alpha build, bad UUID, corrupt JSON) is skipped
                // and logged rather than aborting the whole batch and leaving partial state.
                for (i in 0 until importedArray.length()) {
                    runCatching {
                        val obj = importedArray.getJSONObject(i)
                        val uuid = UUID.fromString(obj.getString("uuid"))
                        if (ImportedDao().exists(uuid) || PendingDao().exists(uuid)) return@runCatching false

                        val sourceDir = extractRoot.resolve(Migration.IMPORTED_DIR).resolve(uuid.toString())
                        if (!sourceDir.isDirectory) return@runCatching false

                        val targetDir = context.importedDir.resolve(uuid.toString())
                        targetDir.deleteRecursively()
                        sourceDir.copyRecursively(targetDir, overwrite = true)

                        ImportedDao().insert(obj.toImported())
                        true
                    }.onFailure {
                        Log.w("Migration: skipping imported record $i: $it", it)
                    }.getOrDefault(false).let { if (it) importedCount++ }
                }

                for (i in 0 until pendingArray.length()) {
                    runCatching {
                        val obj = pendingArray.getJSONObject(i)
                        val uuid = UUID.fromString(obj.getString("uuid"))
                        if (ImportedDao().exists(uuid) || PendingDao().exists(uuid)) return@runCatching false

                        val sourceDir = extractRoot.resolve(Migration.PENDING_DIR).resolve(uuid.toString())
                        val targetDir = context.pendingDir.resolve(uuid.toString())
                        targetDir.deleteRecursively()
                        if (sourceDir.isDirectory) {
                            sourceDir.copyRecursively(targetDir, overwrite = true)
                        } else {
                            targetDir.mkdirs()
                            targetDir.resolve("config.yaml").createNewFile()
                            targetDir.resolve("providers").mkdir()
                        }

                        PendingDao().insert(obj.toPending())
                        true
                    }.onFailure {
                        Log.w("Migration: skipping pending record $i: $it", it)
                    }.getOrDefault(false).let { if (it) pendingCount++ }
                }

                for (i in 0 until selectionsArray.length()) {
                    runCatching {
                        val obj = selectionsArray.getJSONObject(i)
                        val uuid = UUID.fromString(obj.getString("uuid"))
                        if (!ImportedDao().exists(uuid)) return@runCatching
                        SelectionDao().setSelected(
                            Selection(
                                uuid = uuid,
                                proxy = obj.getString("proxy"),
                                selected = obj.getString("selected"),
                            )
                        )
                    }.onFailure {
                        Log.w("Migration: skipping selection record $i: $it", it)
                    }
                }

                mergeSharedPreferences(
                    context.getSharedPreferences("service", Context.MODE_PRIVATE),
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
                    if (activeUuid != null && ImportedDao().exists(activeUuid)) {
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
            }
        }
    }

    private suspend fun dumpProfilesJson(context: Context): String {
        val imported = JSONArray()
        val pending = JSONArray()
        val selections = JSONArray()

        ImportedDao().queryAllUUIDs().forEach { uuid ->
            val item = ImportedDao().queryByUUID(uuid) ?: return@forEach
            imported.put(item.toJson())
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
            pending.put(item.toJson())
        }

        return JSONObject()
            .put("activeProfile", ServiceStore(context).activeProfile?.toString() ?: "")
            .put("imported", imported)
            .put("pending", pending)
            .put("selections", selections)
            .toString()
    }

    private fun dumpSharedPreferences(prefs: SharedPreferences): String {
        val root = JSONObject()
        prefs.all.forEach { (key, value) ->
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

    private fun Imported.toJson(): JSONObject {
        return JSONObject()
            .put("uuid", uuid.toString())
            .put("name", name)
            .put("type", type.name)
            .put("source", source)
            .put("interval", interval)
            .put("upload", upload)
            .put("download", download)
            .put("total", total)
            .put("expire", expire)
            .put("createdAt", createdAt)
            .put("ageSecretKey", ageSecretKey)
    }

    private fun Pending.toJson(): JSONObject {
        return JSONObject()
            .put("uuid", uuid.toString())
            .put("name", name)
            .put("type", type.name)
            .put("source", source)
            .put("interval", interval)
            .put("upload", upload)
            .put("download", download)
            .put("total", total)
            .put("expire", expire)
            .put("createdAt", createdAt)
            .put("ageSecretKey", ageSecretKey)
    }

    private fun JSONObject.toImported(): Imported {
        return Imported(
            uuid = UUID.fromString(getString("uuid")),
            name = getString("name"),
            type = Profile.Type.valueOf(getString("type")),
            source = optString("source"),
            interval = optLong("interval"),
            upload = optLong("upload"),
            download = optLong("download"),
            total = optLong("total"),
            expire = optLong("expire"),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            ageSecretKey = optString("ageSecretKey").takeIf { it.isNotBlank() },
        )
    }

    private fun JSONObject.toPending(): Pending {
        return Pending(
            uuid = UUID.fromString(getString("uuid")),
            name = getString("name"),
            type = Profile.Type.valueOf(getString("type")),
            source = optString("source"),
            interval = optLong("interval"),
            upload = optLong("upload"),
            download = optLong("download"),
            total = optLong("total"),
            expire = optLong("expire"),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            ageSecretKey = optString("ageSecretKey").takeIf { it.isNotBlank() },
        )
    }
}
