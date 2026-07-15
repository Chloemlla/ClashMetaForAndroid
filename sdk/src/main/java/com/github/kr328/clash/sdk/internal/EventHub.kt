package com.github.kr328.clash.sdk.internal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.sdk.ClashRuntimeEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

internal class EventHub(private val context: Application) {
    private val _events = MutableSharedFlow<ClashRuntimeEvent>(
        extraBufferCapacity = 32,
    )
    val events: SharedFlow<ClashRuntimeEvent> = _events.asSharedFlow()

    @Volatile
    var clashRunning: Boolean = false
        private set

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != context?.packageName) return

            val event = when (intent?.action) {
                Intents.ACTION_SERVICE_RECREATED -> {
                    clashRunning = false
                    ClashRuntimeEvent.ServiceRecreated
                }
                Intents.ACTION_CLASH_STARTED -> {
                    clashRunning = true
                    ClashRuntimeEvent.Started
                }
                Intents.ACTION_CLASH_STOPPED -> {
                    clashRunning = false
                    ClashRuntimeEvent.Stopped(intent.getStringExtra(Intents.EXTRA_STOP_REASON))
                }
                Intents.ACTION_PROFILE_CHANGED -> ClashRuntimeEvent.ProfileChanged
                Intents.ACTION_PROFILE_LOADED -> ClashRuntimeEvent.ProfileLoaded
                Intents.ACTION_PROFILE_UPDATE_COMPLETED -> {
                    val raw = intent.getStringExtra(Intents.EXTRA_UUID)
                    ClashRuntimeEvent.ProfileUpdateCompleted(
                        raw?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                    )
                }
                Intents.ACTION_PROFILE_UPDATE_FAILED -> {
                    val raw = intent.getStringExtra(Intents.EXTRA_UUID)
                    ClashRuntimeEvent.ProfileUpdateFailed(
                        raw?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                        intent.getStringExtra(Intents.EXTRA_FAIL_REASON),
                    )
                }
                else -> null
            }

            if (event != null) {
                _events.tryEmit(event)
            }
        }
    }

    fun register() {
        if (registered) return
        try {
            context.registerReceiverCompat(
                receiver,
                IntentFilter().apply {
                    addAction(Intents.ACTION_SERVICE_RECREATED)
                    addAction(Intents.ACTION_CLASH_STARTED)
                    addAction(Intents.ACTION_CLASH_STOPPED)
                    addAction(Intents.ACTION_PROFILE_CHANGED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_FAILED)
                    addAction(Intents.ACTION_PROFILE_LOADED)
                },
            )
            registered = true
        } catch (e: Exception) {
            Log.w("Register runtime event receiver: $e", e)
        }
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w("Unregister runtime event receiver: $e", e)
        } finally {
            registered = false
            clashRunning = false
        }
    }
}