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
import kotlinx.coroutines.runBlocking
import java.io.File

class MigrationProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

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
        // Ensure Room is ready before export.
        Database.database

        val file = bundleFile(ctx)
        val ok = runBlocking { MigrationBundle.exportToZip(ctx, file) }
        if (!ok || !file.isFile) {
            Log.w("MigrationProvider: export failed")
            return null
        }

        return MatrixCursor(arrayOf("size", "package")).apply {
            addRow(arrayOf(file.length(), ctx.packageName))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        enforceCaller()
        if (matcher.match(uri) != CODE_BUNDLE) return null
        if (mode != "r") throw SecurityException("read-only migration bundle")

        val ctx = context ?: return null
        Database.database
        val file = bundleFile(ctx)
        val ok = runBlocking { MigrationBundle.exportToZip(ctx, file) }
        if (!ok || !file.isFile) {
            throw IllegalStateException("migration export failed")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
    }
}