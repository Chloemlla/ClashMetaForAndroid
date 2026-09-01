package com.github.kr328.clash.service.clash

import android.app.Service
import android.content.ComponentCallbacks2
import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.clash.module.Module
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

private val globalLock = Mutex()
private val lockTimeout = TimeUnit.SECONDS.toMillis(10)
private const val GC_MIN_INTERVAL_MS = 5_000L

interface ClashRuntimeScope {
    fun <E, T : Module<E>> install(module: T): T
}

interface ClashRuntime {
    fun launch()
    fun requestGc(level: Int)
}

fun CoroutineScope.clashRuntime(block: suspend ClashRuntimeScope.() -> Unit): ClashRuntime {
    return object : ClashRuntime {
        private val gcLock = Any()
        private var lastGcAt = 0L

        override fun launch() {
            launch(Dispatchers.IO) {
                // Stopping only cancels the previous runtime, so its NonCancellable teardown can
                // still hold the lock. Waiting forever would leave the notification stuck on
                // "loading" with no way for the user to recover.
                if (withTimeoutOrNull(lockTimeout) { globalLock.lock() } == null) {
                    Log.e("ClashRuntime: previous runtime did not release the lock in ${lockTimeout}ms")

                    (this@clashRuntime as? Service)?.stopSelf()

                    return@launch
                }

                Log.d("ClashRuntime: initialize")

                try {
                    Clash.reset()
                    Clash.clearOverride(Clash.OverrideSlot.Session)

                    val scope = object : ClashRuntimeScope {
                        override fun <E, T : Module<E>> install(module: T): T {
                            launch {
                                module.execute()
                            }

                            return module
                        }
                    }

                    scope.block()

                    cancel()
                } finally {
                    withContext(NonCancellable) {
                        Clash.reset()
                        Clash.clearOverride(Clash.OverrideSlot.Session)

                        Log.d("ClashRuntime: destroyed")
                    }

                    globalLock.unlock()
                }
            }
        }

        override fun requestGc(level: Int) {
            // A native GC is a synchronous stop-the-world JNI call. onTrimMemory runs on the main
            // thread and the system may fire several TRIM_MEMORY_* levels in a row, so gate on the
            // level, merge consecutive calls, and run the actual GC off the caller thread (B-183).
            if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                return
            }

            val now = SystemClock.elapsedRealtime()
            synchronized(gcLock) {
                if (now - lastGcAt < GC_MIN_INTERVAL_MS) {
                    return
                }
                lastGcAt = now
            }

            launch(Dispatchers.Default) {
                runCatching { Clash.forceGc() }
            }
        }
    }
}