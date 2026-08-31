package com.github.kr328.clash.common.log

import android.content.pm.ApplicationInfo
import com.github.kr328.clash.common.Global

object Log {
    private const val TAG = "ClashMetaForAndroid"

    /**
     * Debug/verbose lines must never reach logcat in a release build (subscription URLs and
     * credentials can end up in the message). Evaluated lazily so a process that has not yet run
     * [Global.init] — or that runs the library without an application — does not crash on first use.
     */
    private val isDebug: Boolean by lazy {
        try {
            (Global.application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }
    }

    fun i(message: String, throwable: Throwable? = null): Int =
        android.util.Log.i(TAG, redact(message), throwable)

    fun w(message: String, throwable: Throwable? = null): Int =
        android.util.Log.w(TAG, redact(message), throwable)

    fun e(message: String, throwable: Throwable? = null): Int =
        android.util.Log.e(TAG, redact(message), throwable)

    fun d(message: String, throwable: Throwable? = null): Int {
        if (!isDebug) return 0
        return android.util.Log.d(TAG, redact(message), throwable)
    }

    fun v(message: String, throwable: Throwable? = null): Int {
        if (!isDebug) return 0
        return android.util.Log.v(TAG, redact(message), throwable)
    }

    fun f(message: String, throwable: Throwable): Int =
        android.util.Log.wtf(TAG, redact(message), throwable)

    /**
     * Best-effort redaction of URLs before they reach logcat: keeps the scheme and host and short
     * path segments, masks the query string and any path segment long enough to be a subscription
     * token. Applied on the facade level so every call site is defended without touching each one.
     */
    private val URL_PATTERN = Regex("""https?://[^\s"'<>)\]]+""")

    private const val LONG_SEGMENT_THRESHOLD = 24

    internal fun redact(message: String): String {
        return URL_PATTERN.replace(message) { match ->
            val url = match.value
            val parsed = try {
                java.net.URI(url)
            } catch (_: Exception) {
                return@replace url
            }
            val scheme = parsed.scheme ?: return@replace url
            val host = parsed.host ?: return@replace url
            val path = parsed.rawPath ?: ""
            val maskedPath = path.split('/')
                .joinToString("/") { segment ->
                    if (segment.length > LONG_SEGMENT_THRESHOLD) "…" else segment
                }
            val suffix = if (parsed.rawQuery != null) "?…" else ""
            "$scheme://$host$maskedPath$suffix"
        }
    }
}
