package com.github.kr328.clash.sdk.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal class Resource<T> {
    private interface Callback<T> {
        fun accept(value: T)
    }

    private val pending: MutableSet<Callback<T>> = mutableSetOf()
    private var value: T? = null

    // set(null) intentionally does not wake waiters, so without a deadline a waiter parked while
    // the service is down never resumes. Embedding hosts must not be wedged by our bind failure.
    suspend fun get(): T = withTimeout(AWAIT_TIMEOUT) { await() }

    private suspend fun await(): T {
        return suspendCancellableCoroutine { ctx ->
            val callback = object : Callback<T> {
                override fun accept(value: T) {
                    ctx.resume(value)
                }
            }

            ctx.invokeOnCancellation {
                cancel(callback)
            }

            get(callback)
        }
    }

    fun set(v: T?) {
        setAndNotify(v)
    }

    fun reset(v: T) {
        resetIfMatched(v)
    }

    @Synchronized
    private fun get(callback: Callback<T>) {
        val v = value
        if (v == null) {
            pending.add(callback)
        } else {
            callback.accept(v)
        }
    }

    @Synchronized
    private fun setAndNotify(value: T?) {
        this.value = value
        if (value != null) {
            pending.forEach { it.accept(value) }
            pending.clear()
        }
    }

    @Synchronized
    private fun resetIfMatched(value: T) {
        if (this.value === value) {
            this.value = null
        }
    }

    @Synchronized
    private fun cancel(callback: Callback<T>) {
        pending.remove(callback)
    }

    companion object {
        private val AWAIT_TIMEOUT = TimeUnit.SECONDS.toMillis(15)
    }
}