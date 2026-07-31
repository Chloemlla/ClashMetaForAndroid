package com.github.kr328.clash.util

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.Profile
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.FilesClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Explicit, user-confirmed raw profile export. This is not an automatic backup path. */
object ProfileFileExport {
    suspend fun share(design: Design<*>, profile: Profile) {
        try {
            val context = design.context
            val payload = withContext(Dispatchers.IO) {
                val safeName = profile.name
                    .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
                    .replace(Regex("(?i)\\.ya?ml$"), "")
                    .ifBlank { "profile" }
                    .take(60)
                val exportRoot = context.cacheDir.resolve("profile-export").apply { mkdirs() }
                removeStaleExports(exportRoot)
                val exportDirectory = exportRoot
                    .resolve(UUID.randomUUID().toString())
                    .apply { mkdirs() }
                try {
                    val exportFile = exportDirectory
                        .resolve("$safeName.yaml")
                        .apply { createNewFile() }
                    val target = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        exportFile,
                    )
                    val source = FilesClient(context)
                        .buildDocumentUri("${profile.uuid}/config.yaml")

                    context.contentResolver.copyContentTo(source, target)
                    ExportPayload(target, exportDirectory)
                } catch (e: Exception) {
                    exportDirectory.deleteRecursively()
                    throw e
                }
            }

            val send = Intent(Intent.ACTION_SEND).apply {
                type = ProfileFileEditor.MIME_YAML
                putExtra(Intent.EXTRA_STREAM, payload.uri)
                putExtra(Intent.EXTRA_SUBJECT, profile.name)
                clipData = ClipData.newRawUri(profile.name, payload.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                withContext(Dispatchers.Main) {
                    context.startActivity(
                        Intent.createChooser(
                            send,
                            context.getString(R.string.export_profile_file_chooser),
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.IO + NonCancellable) {
                    payload.directory.deleteRecursively()
                }
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private fun removeStaleExports(root: File) {
        val cutoff = System.currentTimeMillis() - STALE_EXPORT_AGE_MILLIS

        root.listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    private data class ExportPayload(
        val uri: Uri,
        val directory: File,
    )

    private const val STALE_EXPORT_AGE_MILLIS = 24L * 60L * 60L * 1000L
}
