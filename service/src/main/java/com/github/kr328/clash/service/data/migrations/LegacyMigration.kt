@file:Suppress("BlockingMethodInNonBlockingContext")

package com.github.kr328.clash.service.data.migrations

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.text.isDigitsOnly
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import java.io.File

internal suspend fun migrationFromLegacy(context: Context) {
    val file = context.getDatabasePath("clash-config")

    if (!file.exists()) {
        return
    }

    Log.i("Migration from legacy database")

    try {
        var legacyVersion = 0
        val skipped = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            .use { db ->
                val v = db.version
                legacyVersion = v

                Log.i("Legacy database version = $v")

                when (v) {
                    1 -> migrationFromLegacy1(context, db)
                    2, 3, 4 -> migrationFromLegacy234(context, db, v)
                    else -> {
                        Log.w("Legacy database version $v is not supported; keeping legacy database")
                        1
                    }
                }
            }

        if (skipped > 0) {
            Log.w("Legacy migration skipped $skipped unrecognized profile(s); keeping legacy database")
            // The run completed without an exception; clear any failure flag from a prior run.
            recordLegacyMigrationFailure(context, failed = false)
            return
        }

        context.deleteDatabase("clash-config")

        if (legacyVersion in 2..4) {
            context.filesDir.resolve("profiles").deleteRecursively()
            context.filesDir.resolve("clash").listFiles()?.forEach {
                if (it.name.isDigitsOnly()) {
                    it.deleteRecursively()
                }
            }
        }

        recordLegacyMigrationFailure(context, failed = false)

        Log.i("Legacy database migrated")
    } catch (e: Exception) {
        // A-39: an empty profile list is indistinguishable from a total loss, so a failed
        // migration must not be silent. Log at error level and persist a flag the UI can
        // surface on the next launch (offer retry / exporting the old database).
        Log.e("Legacy migration failed: $e — keeping legacy database for retry", e)
        recordLegacyMigrationFailure(context, failed = true)
    }
}

private fun recordLegacyMigrationFailure(context: Context, failed: Boolean) {
    // The flag is written in whichever process the migration happens to run in and read by
    // the UI (main process), so route through the PreferenceProvider channel instead of
    // touching the "service" preference file directly (A-32).
    runCatching {
        ServiceStore(context).legacyMigrationFailed = failed
    }
}

private suspend fun migrationFromLegacy234(
    context: Context,
    legacy: SQLiteDatabase,
    version: Int,
): Int {
    var skipped = 0

    data class LegacyRow(
        val id: Long,
        val name: String,
        val type: Int,
        val uri: String,
        val interval: Long,
    )

    // Read every row into memory first: each row is deleted from the legacy table as it
    // migrates (A-10), which must not happen while a cursor over that table is still open.
    val rows = legacy.query(
        "profiles",
        arrayOf("id", "name", "type", "uri", if (version == 2) "update_interval" else "interval"),
        null,
        null,
        null,
        null,
        "id"
    ).use { cursor ->
        val id = cursor.getColumnIndex("id")
        val name = cursor.getColumnIndex("name")
        val type = cursor.getColumnIndex("type")
        val uri = cursor.getColumnIndex("uri")
        val interval = cursor.getColumnIndex(if (version == 2) "update_interval" else "interval")

        if (!cursor.moveToFirst())
            return 0

        val result = ArrayList<LegacyRow>()
        do {
            result += LegacyRow(
                id = cursor.getLong(id),
                name = cursor.getString(name),
                type = cursor.getInt(type),
                uri = cursor.getString(uri),
                interval = cursor.getLong(interval),
            )
        } while (cursor.moveToNext())
        result
    }

    for (row in rows) {
        val newType = when (row.type) {
            1 -> { // TYPE_FILE
                Profile.Type.File
            }
            2 -> { // TYPE_URL
                Profile.Type.Url
            }
            3 -> { // TYPE_EXTERNAL
                Profile.Type.External
            }
            else -> { // unknown
                skipped++
                Log.w("Legacy migration: unrecognized profile type ${row.type} for '${row.name}'; skipping")
                continue
            }
        }

        val pending = Pending(
            uuid = generateProfileUUID(),
            name = row.name,
            type = newType,
            source = if (newType != Profile.Type.File) row.uri else "",
            interval = if (version == 2) row.interval * 1000 else row.interval,
            upload = 0,
            download = 0,
            total = 0,
            expire = 0,
        )

        val base = context.pendingDir.resolve(pending.uuid.toString())

        base.apply {
            mkdirs()

            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        if (newType == Profile.Type.File) {
            val legacyFile = context.filesDir.resolve("profiles/${row.id}.yaml")

            if (legacyFile.isFile) {
                legacyFile.copyTo(base.resolve("config.yaml"), overwrite = true)
            }
        }

        PendingDao().insert(pending)

        // A-10: drop the migrated row from the legacy table only once the new row is durable,
        // so an interrupted migration retries the remaining rows instead of re-inserting the
        // already-migrated ones and duplicating profiles on the next launch.
        legacy.delete("profiles", "id = ?", arrayOf(row.id.toString()))

        context.sendProfileChanged(pending.uuid)

        Log.i("${pending.name} migrated")
    }

    return skipped
}

private suspend fun migrationFromLegacy1(context: Context, legacy: SQLiteDatabase): Int {
    var skipped = 0

    data class LegacyRow(
        val id: Long,
        val name: String,
        val token: String,
        val file: String,
    )

    // See migrationFromLegacy234: rows are deleted as they migrate, so read them all first.
    val rows = legacy.query(
        "profiles",
        arrayOf("name", "token", "id", "file"),
        null,
        null,
        null,
        null,
        "id",
    ).use { cursor ->
        val name = cursor.getColumnIndex("name")
        val token = cursor.getColumnIndex("token")
        val id = cursor.getColumnIndex("id")
        val file = cursor.getColumnIndex("file")

        if (!cursor.moveToFirst())
            return 0

        val result = ArrayList<LegacyRow>()
        do {
            result += LegacyRow(
                id = cursor.getLong(id),
                name = cursor.getString(name),
                token = cursor.getString(token),
                file = cursor.getString(file),
            )
        } while (cursor.moveToNext())
        result
    }

    for (row in rows) {
        val legacyToken = row.token

        val newType = when {
            legacyToken.startsWith("file|") -> Profile.Type.File
            legacyToken.startsWith("url|") -> Profile.Type.Url
            else -> {
                skipped++
                Log.w("Legacy migration: unrecognized token for profile '${row.name}'; skipping")
                continue
            }
        }

        val source = if (newType == Profile.Type.Url) {
            legacyToken.removePrefix("url|")
        } else {
            ""
        }

        val pending = Pending(
            uuid = generateProfileUUID(),
            name = row.name,
            type = newType,
            source = source,
            interval = 0,
            upload = 0,
            download = 0,
            total = 0,
            expire = 0,
        )

        val base = context.pendingDir.resolve(pending.uuid.toString())

        base.apply {
            mkdirs()

            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        val legacyFile = File(row.file)
        var sourceCopied = false

        if (newType == Profile.Type.File) {
            if (legacyFile.isFile) {
                legacyFile.copyTo(base.resolve("config.yaml"), overwrite = true)
                sourceCopied = true
            }
        }

        PendingDao().insert(pending)

        // A-10: drop the migrated row from the legacy table once the new row is durable.
        // Doing this before removing the source file keeps the retry path (which still has
        // the row) able to re-copy the source if anything below throws.
        legacy.delete("profiles", "id = ?", arrayOf(row.id.toString()))

        // A-40: drop the source only when it was actually copied and lives inside the app's
        // private dir. A legacy v1 row may point at shared storage the user owns — that file
        // must be left alone, and deleting it would be irreversible. The delete stays after
        // the insert (A-01) so the retry path still has the source if the insert throws.
        if (sourceCopied &&
            legacyFile.absolutePath.startsWith(context.filesDir.absolutePath + File.separator)
        ) {
            legacyFile.delete()
        }

        context.sendProfileChanged(pending.uuid)

        Log.i("${pending.name} migrated")
    }

    return skipped
}
