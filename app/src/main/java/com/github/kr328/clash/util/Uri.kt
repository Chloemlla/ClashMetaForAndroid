package com.github.kr328.clash.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Display name of the document behind [this].
 *
 * A `content://` URI carries an opaque, percent-encoded documentId in its path, so the provider is
 * the only place that knows the name the user picked. Falling back to the path segment would put
 * `%E6%88%91...` in front of the user — and `%` is rejected by the file-name validator, so the
 * suggested name would not even be accepted.
 */
fun Uri.queryFileName(resolver: ContentResolver): String? = when (scheme) {
    ContentResolver.SCHEME_CONTENT -> queryDisplayName(resolver) ?: lastSegmentName()
    else -> schemeSpecificPart.split("/").lastOrNull()
}

private fun Uri.queryDisplayName(resolver: ContentResolver): String? = runCatching {
    resolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

// getLastPathSegment() is already decoded; a documentId still looks like "primary:Dir/name.yaml".
private fun Uri.lastSegmentName(): String? = lastPathSegment
    ?.substringAfterLast('/')
    ?.substringAfterLast(':')
    ?.takeIf { it.isNotBlank() }
