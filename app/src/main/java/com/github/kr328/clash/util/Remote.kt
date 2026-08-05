package com.github.kr328.clash.util

import android.os.DeadObjectException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private const val MAX_RETRIES = 5
private const val RETRY_BASE_DELAY_MS = 100L

/**
 * Retries a Binder call when the remote service dies. Bounded with exponential
 * backoff so a permanently dead service fails fast instead of spinning forever.
 */
suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T {
    var attempt = 0
    while (true) {
        val remote = Remote.service.remote.get()
        val client = remote.clash()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            attempt += 1
            if (attempt > MAX_RETRIES) throw e
            Log.w("Remote services panic (attempt $attempt/$MAX_RETRIES)")
            Remote.service.remote.reset(remote)
            delay(RETRY_BASE_DELAY_MS * attempt)
        }
    }
}

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T {
    var attempt = 0
    while (true) {
        val remote = Remote.service.remote.get()
        val client = remote.profile()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            attempt += 1
            if (attempt > MAX_RETRIES) throw e
            Log.w("Remote services panic (attempt $attempt/$MAX_RETRIES)")
            Remote.service.remote.reset(remote)
            delay(RETRY_BASE_DELAY_MS * attempt)
        }
    }
}
