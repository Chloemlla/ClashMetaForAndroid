package com.github.kr328.clash.service.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.kr328.clash.common.constants.Migration
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Database
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

class MigrationProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    @Volatile
    private var cachedBundle: File? = null

    @Volatile
    private var cachedBundleAt: Long = 0L

    override fun onCreate(): Boolean {
        val authority = context?.packageName?.let(Migration::authorityFor) ?: return false
        matcher.addURI(authority, Migration.BUNDLE_PATH, CODE_BUNDLE)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceCaller()
        if (matcher.match(uri) != CODE_BUNDLE) return null

        val ctx = context ?: return null
        val file = ensureBundle(ctx) ?: return null

        return MatrixCursor(arrayOf("size", "package")).apply {
            addRow(arrayOf(file.length(), ctx.packageName))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        enforceCaller()
        if (matcher.match(uri) != CODE_BUNDLE) return null
        if (mode != "r") throw SecurityException("read-only migration bundle")

        val ctx = context ?: return null
        val file = ensureBundle(ctx)
            ?: throw IllegalStateException("migration export failed")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        // A-31: the bundle carries service prefs, including the age secret key. Unlink it as
        // soon as it has been served so the key does not linger in cacheDir; the open
        // descriptor keeps the file alive for the caller across the binder transfer.
        cachedBundle = null
        cachedBundleAt = 0L
        file.delete()
        return pfd
    }

    override fun getType(uri: Uri): String? {
        return if (matcher.match(uri) == CODE_BUNDLE) "application/zip" else null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun ensureBundle(ctx: android.content.Context): File? {
        cachedBundle?.takeIf {
            it.isFile && it.length() > 0L &&
                System.currentTimeMillis() - cachedBundleAt < CACHE_TTL_MS
        }?.let { return it }

        // Ensure Room is ready before export.
        Database.database
        val file = bundleFile(ctx)
        // query/openFile run on a binder thread from the calling process. Bound the
        // synchronous export so a very large profile set cannot block the binder
        // thread indefinitely (ANR); the caller sees a failed export instead of a hang.
        val ok = try {
            runBlocking {
                withTimeout(EXPORT_TIMEOUT_MS) { MigrationBundle.exportToZip(ctx, file) }
            }
        } catch (e: TimeoutCancellationException) {
            // The blocking export keeps running on the IO dispatcher; it writes to a temp
            // file and renames only on completion, so the public path is never truncated.
            // On timeout we just don't cache a result for this request.
            Log.w("MigrationProvider: export timed out (continuing in background)", e)
            false
        }
        if (!ok || !file.isFile || file.length() <= 0L) {
            Log.w("MigrationProvider: export failed")
            return null
        }
        // A-31: also schedule removal at process exit, so a bundle that was exported but
        // never opened (no openFile) does not linger with the private key in cacheDir.
        file.deleteOnExit()
        cachedBundle = file
        cachedBundleAt = System.currentTimeMillis()
        return file
    }

    private fun enforceCaller() {
        val ctx = context ?: throw SecurityException("no context")
        val caller = callingPackage
            ?: throw SecurityException("missing calling package")
        if (caller == ctx.packageName) return

        val result = ctx.packageManager.checkSignatures(ctx.packageName, caller)
        if (result < 0) {
            throw SecurityException("caller not same-signature: $caller")
        }
    }

    private fun bundleFile(context: android.content.Context): File {
        return context.cacheDir.resolve("migration-export.zip")
    }

    companion object {
        private const val CODE_BUNDLE = 1

        // Upper bound for the synchronous bundle export on a binder thread.
        private const val EXPORT_TIMEOUT_MS = 20_000L

        // A bundle is a point-in-time snapshot of preferences that include private keys.
        // Never serve a stale snapshot, and never keep one in cacheDir beyond this window
        // (A-31).
        private const val CACHE_TTL_MS = 60_000L
    }
}