package com.github.kr328.clash.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.kr328.clash.design.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Cache-backed external-editor session; the real profile is never granted directly. */
class ProfileFileEditor private constructor(
    private val directory: File,
    private val originalFile: File,
    private val editedFile: File,
    val originalUri: Uri,
    val editedUri: Uri,
) {
    fun createEditIntent(context: Context): Intent {
        val yamlIntent = buildEditIntent(editedUri, MIME_YAML)
        val textIntent = buildEditIntent(editedUri, MIME_TEXT)
        val editIntent = if (yamlIntent.resolveActivity(context.packageManager) != null) {
            yamlIntent
        } else if (textIntent.resolveActivity(context.packageManager) != null) {
            textIntent
        } else {
            throw IllegalStateException(context.getString(R.string.external_editor_unavailable))
        }

        return Intent.createChooser(
            editIntent,
            context.getString(R.string.edit_configuration),
        )
    }

    suspend fun hasChanges(): Boolean = withContext(Dispatchers.IO) {
        !originalFile.contentEquals(editedFile)
    }

    suspend fun close() {
        withContext(Dispatchers.IO + NonCancellable) {
            directory.deleteRecursively()
        }
    }

    companion object {
        const val MIME_YAML = "application/yaml"
        const val MIME_TEXT = "text/plain"

        suspend fun prepare(
            context: Context,
            original: Uri,
            editedSource: Uri = original,
        ): ProfileFileEditor = withContext(Dispatchers.IO) {
            val directory = context.cacheDir
                .resolve("profile-editor")
                .resolve(UUID.randomUUID().toString())
                .apply { mkdirs() }
            val originalFile = directory.resolve("original.yaml").apply { createNewFile() }
            val editedFile = directory.resolve("config.yaml").apply { createNewFile() }
            val authority = "${context.packageName}.fileprovider"
            val originalUri = FileProvider.getUriForFile(context, authority, originalFile)
            val editedUri = FileProvider.getUriForFile(context, authority, editedFile)

            try {
                context.contentResolver.copyContentTo(original, originalUri)

                if (editedSource == original) {
                    originalFile.copyTo(editedFile, overwrite = true)
                } else {
                    context.contentResolver.copyContentTo(editedSource, editedUri)
                }

                ProfileFileEditor(
                    directory = directory,
                    originalFile = originalFile,
                    editedFile = editedFile,
                    originalUri = originalUri,
                    editedUri = editedUri,
                )
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw e
            }
        }

        private fun buildEditIntent(uri: Uri, mimeType: String): Intent {
            return Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, mimeType)
                clipData = ClipData.newRawUri("config.yaml", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }
}

private fun File.contentEquals(other: File): Boolean {
    if (length() != other.length()) return false

    inputStream().buffered().use { first ->
        other.inputStream().buffered().use { second ->
            val firstBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val secondBuffer = ByteArray(DEFAULT_BUFFER_SIZE)

            while (true) {
                val firstRead = first.read(firstBuffer)
                val secondRead = second.read(secondBuffer)

                if (firstRead != secondRead) return false
                if (firstRead < 0) return true
                for (index in 0 until firstRead) {
                    if (firstBuffer[index] != secondBuffer[index]) return false
                }
            }
        }
    }
}
