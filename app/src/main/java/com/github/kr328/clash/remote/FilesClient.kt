@file:Suppress("BlockingMethodInNonBlockingContext")

package com.github.kr328.clash.remote

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.design.model.File
import com.github.kr328.clash.util.copyContentTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.DocumentsContract as DC

class FilesClient(private val context: Context) {
    suspend fun list(parentDocumentId: String): List<File> = withContext(Dispatchers.IO) {
        val uri = DC.buildChildDocumentsUri(Authorities.FILES_PROVIDER, parentDocumentId)

        context.contentResolver.query(uri, FilesProjection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DC.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DC.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(DC.Document.COLUMN_SIZE)
            val lastModified = cursor.getColumnIndexOrThrow(DC.Document.COLUMN_LAST_MODIFIED)
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(DC.Document.COLUMN_MIME_TYPE)

            cursor.moveToFirst()

            List(cursor.count) {
                File(
                    id = cursor.getString(idIndex),
                    name = cursor.getString(nameIndex),
                    size = cursor.getLong(sizeIndex),
                    lastModified = cursor.getLong(lastModified),
                    isDirectory = cursor.getString(mimeTypeIndex) == DC.Document.MIME_TYPE_DIR,
                ).also {
                    cursor.moveToNext()
                }
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        } ?: emptyList()
    }

    suspend fun renameDocument(documentId: String, name: String) = withContext(Dispatchers.IO) {
        val uri = buildDocumentUri(documentId)

        DC.renameDocument(context.contentResolver, uri, name)
    }

    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        val uri = buildDocumentUri(documentId)

        DC.deleteDocument(context.contentResolver, uri)
    }

    suspend fun importDocument(
        parentDocumentId: String,
        source: Uri,
        name: String
    ) = withContext(Dispatchers.IO) {
        val target = buildDocumentUri("$parentDocumentId/$name")

        context.contentResolver.copyContentTo(source, target)
    }

    suspend fun copyDocument(
        documentId: String,
        source: Uri
    ) {
        val target = buildDocumentUri(documentId)

        context.contentResolver.copyContentTo(source, target)
    }

    suspend fun copyDocument(
        target: Uri,
        documentId: String
    ) {
        val source = buildDocumentUri(documentId)

        context.contentResolver.copyContentTo(source, target)
    }

    suspend fun readText(documentId: String): String = withContext(Dispatchers.IO) {
        val source = buildDocumentUri(documentId)

        (context.contentResolver.openInputStream(source)
            ?: throw java.io.FileNotFoundException("$source not found"))
            .bufferedReader(Charsets.UTF_8)
            .use { reader ->
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                val text = StringBuilder()

                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_TEXT_CHARS) {
                        throw java.io.IOException("$source larger than $MAX_TEXT_CHARS characters")
                    }
                    text.append(buffer, 0, count)
                }

                text.toString()
            }
    }

    fun buildDocumentUri(documentId: String): Uri {
        return DC.buildDocumentUri(Authorities.FILES_PROVIDER, documentId)
    }

    companion object {
        private const val MAX_TEXT_CHARS = 4 * 1024 * 1024

        private val FilesProjection = arrayOf(
            DC.Document.COLUMN_DOCUMENT_ID,
            DC.Document.COLUMN_DISPLAY_NAME,
            DC.Document.COLUMN_SIZE,
            DC.Document.COLUMN_LAST_MODIFIED,
            DC.Document.COLUMN_MIME_TYPE,
        )
    }
}
