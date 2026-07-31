package com.github.kr328.clash.service.document

import android.provider.DocumentsContract
import java.io.File

class FileDocument(
    val file: File,
    override val flags: Set<Flag>,
    private val idOverride: String? = null,
    private val nameOverride: String? = null,
) : Document {
    override val id: String
        get() = idOverride ?: file.name
    override val name: String
        get() = nameOverride ?: file.name
    override val mimeType: String
        get() = when {
            file.isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
            file.extension.equals("yaml", ignoreCase = true) ||
                file.extension.equals("yml", ignoreCase = true) -> "application/yaml"
            else -> "text/plain"
        }
    override val size: Long
        get() = file.length()
    override val updatedAt: Long
        get() = file.lastModified()
}
