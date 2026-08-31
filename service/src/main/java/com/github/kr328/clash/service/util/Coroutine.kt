package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val JOIN_TIMEOUT_MS = 2_000L

/**
 * Cancel this scope and wait for its children to finish.
 *
 * Callers release shared state right after this returns (native teardown, fds, service
 * restart), so returning while children still run races them — a restart then observes a
 * half-torn-down runtime. The budget is small because every caller is a
 * [android.app.Service.onDestroy] on the main thread and RemoteService calls this twice in a
 * row: cooperative cancellation lands well inside it, while a child that ignores cancellation
 * must not turn stopping the VPN into a freeze. Work that must complete needs its own
 * NonCancellable section.
 */
fun CoroutineScope.cancelAndJoinBlocking() {
    val job = coroutineContext.job

    val joined = runBlocking {
        withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            job.cancelAndJoin()
        }
    }

    if (joined == null) {
        Log.w("cancelAndJoinBlocking: children still running after ${JOIN_TIMEOUT_MS}ms")
    }
}
