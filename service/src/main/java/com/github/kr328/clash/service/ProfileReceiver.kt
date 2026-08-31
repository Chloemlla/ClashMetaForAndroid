package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class ProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                Global.launch {
                    reset()

                    val service = Intent(Intents.ACTION_PROFILE_SCHEDULE_UPDATES)
                        .setComponent(ProfileWorker::class.componentName)

                    if (!context.startForegroundServiceCompat(service)) {
                        Log.w("Start profile worker for rescheduling rejected")
                    }
                }
            }
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                val redirect = intent.setComponent(ProfileWorker::class.componentName)
                val pending = goAsync()

                Global.launch {
                    try {
                        withTimeoutOrNull(GO_ASYNC_TIMEOUT) {
                            if (!context.startForegroundServiceCompat(redirect)) {
                                // Android 12+ refuses a background foreground-service start; put
                                // the update back on the alarm queue instead of dropping it.
                                Log.w("Start profile worker rejected, retry later")

                                intent.uuid?.let { ImportedDao().queryByUUID(it) }
                                    ?.also { scheduleRetry(context, it, START_RETRY_DELAY) }
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private val lock = Mutex()
        private var initialized: Boolean = false

        private val GO_ASYNC_TIMEOUT = TimeUnit.SECONDS.toMillis(8)
        private val START_RETRY_DELAY = TimeUnit.MINUTES.toMillis(15)
        private val MINIMAL_INTERVAL = TimeUnit.MINUTES.toMillis(15)

        suspend fun rescheduleAll(context: Context) = lock.withLock {
            if (initialized)
                return

            initialized = true

            Log.i("Reschedule all profiles update")

            ImportedDao().queryAll()
                .filter { it.type != Profile.Type.File }
                .forEach { scheduleNext(context, it) }
        }

        fun cancelNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)
        }

        fun schedule(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            intent.send(context, 0, null)
        }

        fun scheduleNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            if (imported.interval < MINIMAL_INTERVAL)
                return

            val current = System.currentTimeMillis()
            val last = context.importedDir
                .resolve(imported.uuid.toString())
                .resolve("config.yaml")
                .lastModified()

            // file not existed
            if (last < 0)
                return

            val interval = (imported.interval - (current - last)).coerceAtLeast(0)

            context.getSystemService<AlarmManager>()
                ?.set(AlarmManager.RTC, current + interval, intent)
        }

        fun scheduleRetry(context: Context, imported: Imported, delay: Long) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            if (imported.interval < MINIMAL_INTERVAL)
                return

            // A retry must never be later than the subscription's own update interval.
            val actual = delay.coerceAtMost(imported.interval)

            context.getSystemService<AlarmManager>()
                ?.set(AlarmManager.RTC, System.currentTimeMillis() + actual, intent)
        }

        private suspend fun reset() = lock.withLock {
            initialized = false
        }

        private fun pendingIntentOf(context: Context, imported: Imported): PendingIntent {
            val intent = Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName)
                .setUUID(imported.uuid)

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }
}