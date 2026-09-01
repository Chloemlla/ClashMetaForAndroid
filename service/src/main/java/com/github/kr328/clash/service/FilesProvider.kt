package com.github.kr328.clash.service

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.PatternFileName
import com.github.kr328.clash.service.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import android.provider.DocumentsContract.Document as D

class FilesProvider : DocumentsProvider() {
    companion object {
        private const val DEFAULT_ROOT_ID = "0"

        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            D.COLUMN_DOCUMENT_ID,
            D.COLUMN_DISPLAY_NAME,
            D.COLUMN_MIME_TYPE,
            D.COLUMN_LAST_MODIFIED,
            D.COLUMN_SIZE,
            D.COLUMN_FLAGS
        )
        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
        )

        private val FLAG_VIRTUAL: Int =
            if (Build.VERSION.SDK_INT >= 24) D.FLAG_VIRTUAL_DOCUMENT else 0
    }

    private val picker: Picker by lazy {
        Picker(context!!)
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        // B-172: an unspecified mode is a read; only an explicit write mode creates a pending
        // edit, and the parsed mode (not a string heuristic) is the write-intent signal.
        val m = if (mode != null) ParcelFileDescriptor.parseMode(mode) else ParcelFileDescriptor.MODE_READ_ONLY
        val writable = m != ParcelFileDescriptor.MODE_READ_ONLY

        // B-171: the pick/DB/copy work runs on the IO dispatcher instead of the Binder thread, and
        // the client's CancellationSignal cancels the coroutine rather than being ignored.
        return runBlocking(Dispatchers.IO) {
            signal?.setOnCancelListener { coroutineContext.cancel() }
            try {
                val path = Paths.resolve(documentId ?: "/")

                val document = picker.pick(path, writable)

                require(document is FileDocument) {
                    throw FileNotFoundException("invalid path $documentId")
                }

                // A file that exists but cannot be read must not be truncated by a write open.
                if (Flag.Unreadable in document.flags) {
                    throw FileNotFoundException("file is not readable: $documentId")
                }

                ParcelFileDescriptor.open(document.file, m)
            } finally {
                signal?.setOnCancelListener(null)
            }
        }
    }

    override fun deleteDocument(documentId: String?) {
        val documentPath = documentId ?: "/"

        runBlocking(Dispatchers.IO) {
            val path = Paths.resolve(documentPath)

            if (path.relative == null)
                throw IllegalArgumentException("invalid path $documentId")

            val document = picker.pick(path, true)

            require(document is FileDocument) {
                throw FileNotFoundException("invalid path $documentId")
            }

            document.file.deleteRecursively()
        }
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        val name = displayName ?: ""

        if (!PatternFileName.matches(name))
            throw IllegalArgumentException("invalid name $displayName")

        return runBlocking(Dispatchers.IO) {
            val path = Paths.resolve(documentId ?: "/")

            if (path.relative == null)
                throw IllegalArgumentException("unable to rename $documentId")

            val document = picker.pick(path, true)

            require(document is FileDocument) {
                throw IllegalArgumentException("unable to rename $document")
            }

            val parent = document.file.parentFile

            require(parent != null) {
                throw IllegalArgumentException("unable to rename $document")
            }

            val target = parent.resolve(name)

            // DocumentsProvider callers treat a failure as an exception. File.renameTo returns
            // false (rather than throwing) when the target exists or the rename is denied, and the
            // old code reported success anyway and handed back an id for a file that was never
            // renamed (B-173).
            if (target != document.file && target.exists()) {
                throw IllegalStateException("unable to rename $document: target already exists")
            }
            if (!document.file.renameTo(target)) {
                throw IllegalStateException("unable to rename $document")
            }

            path.copy(relative = path.relative.dropLast(1) + name).toString()
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return runBlocking(Dispatchers.IO) {
            try {
                val doc = parentDocumentId ?: "/"
                val path = Paths.resolve(doc)
                val documents = picker.list(path)

                MatrixCursor(resolveDocumentProjection(projection)).apply {
                    documents.forEach {
                        newRow().applyDocument(it)
                            .add(D.COLUMN_DOCUMENT_ID, "$doc/${it.id}")
                    }
                }
            } catch (e: Exception) {
                // Log before returning the empty cursor: without this, a real IO/permission
                // error is indistinguishable from an empty directory and cannot be diagnosed.
                Log.w("FilesProvider: queryChildDocuments($parentDocumentId) failed: $e", e)
                MatrixCursor(resolveDocumentProjection(projection))
            }
        }
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        return runBlocking(Dispatchers.IO) {
            try {
                val doc = documentId ?: "/"
                val path = Paths.resolve(doc)
                val document = picker.pick(path, false)

                MatrixCursor(resolveDocumentProjection(projection)).apply {
                    newRow().applyDocument(document).add(D.COLUMN_DOCUMENT_ID, doc)
                }
            } catch (e: Exception) {
                // Log before returning the empty cursor: without this, a real IO/permission
                // error is indistinguishable from a missing document and cannot be diagnosed.
                Log.w("FilesProvider: queryDocument($documentId) failed: $e", e)
                MatrixCursor(resolveDocumentProjection(projection))
            }
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val flags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD

        return MatrixCursor(projection ?: DEFAULT_ROOT_COLUMNS).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, DEFAULT_ROOT_ID)
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_ICON, R.drawable.ic_logo_service)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.clash_meta_for_android))
                add(Root.COLUMN_SUMMARY, context!!.getString(R.string.profiles_and_providers))
                add(Root.COLUMN_DOCUMENT_ID, "/")
                add(Root.COLUMN_MIME_TYPES, D.MIME_TYPE_DIR)
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null)
            return false

        // B-190: a raw startsWith treats "/uuid/providers/ab" as a parent of
        // "/uuid/providers/abc". Require a path-boundary match instead.
        return documentId == parentDocumentId ||
            documentId.startsWith(parentDocumentId.removeSuffix("/") + "/")
    }

    private fun MatrixCursor.RowBuilder.applyDocument(document: Document): MatrixCursor.RowBuilder {
        var flags = 0

        document.flags.forEach {
            flags = when (it) {
                Flag.Writable -> flags or D.FLAG_SUPPORTS_WRITE
                Flag.Deletable -> flags or D.FLAG_SUPPORTS_DELETE
                Flag.Virtual -> flags or FLAG_VIRTUAL
                // No DocumentsProvider bit for this; write support is already withheld and
                // openDocument rejects the file with a clear error.
                Flag.Unreadable -> flags
            }
        }

        add(D.COLUMN_DISPLAY_NAME, document.name)
        add(D.COLUMN_MIME_TYPE, document.mimeType)
        add(D.COLUMN_LAST_MODIFIED, document.updatedAt)
        add(D.COLUMN_SIZE, document.size)
        add(D.COLUMN_FLAGS, flags)

        return this
    }

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<out String> {
        return projection ?: DEFAULT_DOCUMENT_COLUMNS
    }
}
