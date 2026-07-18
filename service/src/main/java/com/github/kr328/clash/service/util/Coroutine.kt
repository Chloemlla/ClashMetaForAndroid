package com.github.kr328.clash.service.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job

/**
 * Cancel this scope without blocking the caller.
 *
 * Service callbacks such as [android.app.Service.onDestroy] run on the main thread; joining
 * child coroutines there can stall stop/restart for an unbounded time (native teardown,
 * binder, file I/O). Cancellation is cooperative — work that must finish should use its own
 * NonCancellable critical section, not force the service main thread to wait.
 */
fun CoroutineScope.cancelAndJoinBlocking() {
    coroutineContext.job.cancel()
}