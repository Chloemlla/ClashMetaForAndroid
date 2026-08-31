package com.github.kr328.clash.util

import android.os.RemoteException
import android.os.TransactionTooLargeException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private const val MAX_RETRIES = 5
private const val RETRY_BASE_DELAY_MS = 100L

/**
 * Exponential backoff for a retry attempt (1-based). 100ms doubling: 100, 200, 400, 800, 1600.
 * Exposed for unit tests; [withRemote] is the only production caller.
 */
internal fun retryBackoffMillis(attempt: Int): Long = RETRY_BASE_DELAY_MS shl (attempt - 1)

/**
 * Retries a Binder call when the remote service dies. Bounded with exponential
 * backoff (100ms doubling, ~3s total) so a permanently dead service fails fast
 * instead of spinning forever.
 */
suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T = withRemote(context, { clash() }, block)

/** @see withClash */
suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T = withRemote(context, { profile() }, block)

private suspend fun <C, T> withRemote(
    context: CoroutineContext,
    client: suspend IRemoteService.() -> C,
    block: suspend C.() -> T,
): T {
    var attempt = 0
    while (true) {
        val remote = Remote.service.remote.get()

        try {
            val target = remote.client()
            return withContext(context) { target.block() }
        } catch (e: TransactionTooLargeException) {
            // Not a transient failure: the payload exceeds the Binder buffer and every retry
            // would rebuild the same oversized transaction.
            throw e
        } catch (e: RemoteException) {
            attempt += 1
            if (attempt > MAX_RETRIES) throw e
            Log.w("Remote services panic (attempt $attempt/$MAX_RETRIES)")
            Remote.service.remote.reset(remote)
            delay(retryBackoffMillis(attempt))
        }
    }
}
