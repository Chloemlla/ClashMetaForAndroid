package com.github.kr328.clash.service.clash

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.clash.module.Module
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

private val globalLock = Mutex()
private val lockTimeout = TimeUnit.SECONDS.toMillis(10)

interface ClashRuntimeScope {
    fun <E, T : Module<E>> install(module: T): T
}

interface ClashRuntime {
    fun launch()
    fun requestGc()
}

fun CoroutineScope.clashRuntime(block: suspend ClashRuntimeScope.() -> Unit): ClashRuntime {
    return object : ClashRuntime {
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
                    val modules = mutableListOf<Module<*>>()

                    Clash.reset()
                    Clash.clearOverride(Clash.OverrideSlot.Session)

                    val scope = object : ClashRuntimeScope {
                        override fun <E, T : Module<E>> install(module: T): T {
                            launch {
                                modules.add(module)

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

        override fun requestGc() {
            Clash.forceGc()
        }
    }
}