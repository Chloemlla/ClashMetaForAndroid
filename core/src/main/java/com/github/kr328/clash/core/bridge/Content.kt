package com.github.kr328.clash.core.bridge

import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import java.io.FileNotFoundException

@Keep
object Content {
    // Cap applied before the fd is handed to native: content providers can serve arbitrary
    // stream types and sizes, and the native fetch would otherwise block or exhaust memory.
    private const val MAX_FILE_SIZE = 64L * 1024L * 1024L

    @JvmStatic
    fun open(url: String): Int {
        return runCatching {
            val uri = Uri.parse(url)
            require(uri.scheme == "content") { "Unsupported scheme ${uri.scheme}" }

            val pfd = Global.application.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("$uri not found")

            try {
                // Ownership stays with the pfd until detachFd, so a rejected fd is released by
                // pfd.close(); only a detached fd (transferred to native) is never closed here.
                val stat = Os.fstat(pfd.fileDescriptor)
                val isRegular =
                    (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG

                if (!isRegular || stat.st_size > MAX_FILE_SIZE) {
                    Log.w("Content.open: rejected $uri (regular=$isRegular size=${stat.st_size})")
                    pfd.close()
                    return@runCatching -1
                }

                pfd.detachFd()
            } catch (e: Throwable) {
                pfd.close()
                throw e
            }
        }.getOrElse { -1 }
    }
}
