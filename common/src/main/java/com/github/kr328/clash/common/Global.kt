package com.github.kr328.clash.common

import android.app.Application
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// SupervisorJob so one failing child does not cancel the whole app-wide scope, and a
// handler so an uncaught failure is logged instead of silently tearing down siblings.
private val globalExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.w("Uncaught exception in Global scope: $throwable", throwable)
}

object Global : CoroutineScope by CoroutineScope(
    SupervisorJob() + Dispatchers.IO + globalExceptionHandler
) {
    val application: Application
        get() = application_

    private lateinit var application_: Application

    fun init(application: Application) {
        this.application_ = application
    }

    fun destroy() {
        cancel()
    }
}