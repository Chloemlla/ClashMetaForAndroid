package com.github.kr328.clash.service

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import kotlinx.coroutines.*

abstract class BaseService : Service(),
    CoroutineScope by CoroutineScope(
        // SupervisorJob keeps one failing child (e.g. ProfileReceiver.rescheduleAll) from
        // cancelling the whole service scope and every sibling update; the handler turns an
        // uncaught failure into a log line instead of an unhandled crash of :background (B-181).
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e("Unhandled exception in service scope", e)
        }
    ) {
    override fun onDestroy() {
        super.onDestroy()

        cancelAndJoinBlocking()
    }
}