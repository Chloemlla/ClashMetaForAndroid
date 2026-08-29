package com.github.kr328.clash.util

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.service.migration.MigrationBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

object DataBackup {
    suspend fun export(context: Context, target: Uri) = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("clash-backup-", ".zip", context.cacheDir)
        try {
            check(MigrationBundle.exportToZip(context, temporary)) {
                "Unable to create backup"
            }
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: throw FileNotFoundException("Unable to open backup destination")
            output.use { destination ->
                temporary.inputStream().use { source ->
                    source.copyTo(destination)
                }
            }
        } finally {
            temporary.delete()
        }
    }

    suspend fun import(context: Context, source: Uri): MigrationBundle.ImportResult =
        withContext(Dispatchers.IO) {
            val temporary = File.createTempFile("clash-restore-", ".zip", context.cacheDir)
            try {
                copyToTemporary(context, source, temporary)
                MigrationBundle.importFromZip(context, temporary)
            } finally {
                temporary.delete()
            }
        }

    private fun copyToTemporary(context: Context, source: Uri, target: File) {
        val input = context.contentResolver.openInputStream(source)
            ?: throw FileNotFoundException("Unable to open backup source")
        input.use { inputStream ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    if (copied > MAX_BACKUP_BYTES - count) {
                        throw IllegalStateException("Backup file is too large")
                    }
                    output.write(buffer, 0, count)
                    copied += count
                }
            }
        }
    }

    private const val MAX_BACKUP_BYTES = 256L * 1024L * 1024L
}
