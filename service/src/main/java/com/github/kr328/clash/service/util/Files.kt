package com.github.kr328.clash.service.util

import android.content.Context
import java.io.File

val Context.importedDir: File
    get() = filesDir.resolve("imported")

val Context.pendingDir: File
    get() = filesDir.resolve("pending")

val Context.processingDir: File
    get() = filesDir.resolve("processing")

val File.directoryLastModified: Long?
    get() {
        return walk().map { it.lastModified() }.maxOrNull()
    }

/**
 * Cheap profile "updated at" signal: prefer config.yaml, then the profile directory itself.
 * Avoids recursive provider-tree walks on every Profiles list refresh.
 */
val File.profileContentModified: Long?
    get() {
        if (!exists()) return null
        val config = resolve("config.yaml")
        if (config.isFile) return config.lastModified()
        return lastModified().takeIf { it > 0L }
    }

/**
 * Atomically replace [target] with the contents of [source].
 *
 * Copies [source] into a sibling temporary directory first, then swaps it into
 * place with [File.renameTo]. A crash between steps never leaves [target] as a
 * half-written directory: the caller either sees the previous complete contents
 * or the new complete contents. Falls back to a delete-then-copy only if the
 * rename is unsupported, which is the pre-existing (non-atomic) behavior.
 */
fun replaceDirectoryAtomically(source: File, target: File) {
    val staging = File(target.parentFile, "${target.name}.tmp-${System.currentTimeMillis()}")
    staging.deleteRecursively()
    try {
        source.copyRecursively(staging, overwrite = true)

        // Move the current target out of the way, then swap staging in.
        val backup = File(target.parentFile, "${target.name}.old-${System.currentTimeMillis()}")
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) {
            // Rename unsupported (rare); fall back to non-atomic replace.
            target.deleteRecursively()
            staging.copyRecursively(target, overwrite = true)
            return
        }
        if (!staging.renameTo(target)) {
            // Roll back to the previous contents if the swap failed.
            if (hadTarget) backup.renameTo(target)
            staging.copyRecursively(target, overwrite = true)
        }
        backup.deleteRecursively()
    } finally {
        staging.deleteRecursively()
    }
}