package com.github.kr328.clash.sdk.internal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.sdk.ClashRuntimeEvent
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges self-broadcasts from `:service` into a host-observable [events] stream.
 *
 * Instances are created at [com.github.kr328.clash.sdk.ClashRuntime] load time and
 * attached to an [Application] during `install`; subscribing to [events] before install
 * is legal and simply yields no events until receivers are registered by [register].
 */
@OptIn(FlowPreview::class)
internal class EventHub {
    // High-frequency / progress events: a bounded buffer is acceptable, and any drop is logged.
    private val _events = MutableSharedFlow<ClashRuntimeEvent>(
        extraBufferCapacity = 64,
    )

    // Lifecycle state events: StateFlow guarantees the latest state is delivered to every
    // (re-)subscriber even when transient emissions were conflated, so a host that rebinds
    // cannot permanently miss e.g. "started".
    private val _state = MutableStateFlow<ClashRuntimeEvent?>(null)

    val events: Flow<ClashRuntimeEvent> =
        merge(_state.filterNotNull(), _events.asSharedFlow())

    @Volatile
    var clashRunning: Boolean = false
        private set

    private val registered = AtomicBoolean(false)

    @Volatile
    private var context: Application? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != context?.packageName) return

            when (intent?.action) {
                Intents.ACTION_SERVICE_RECREATED -> {
                    clashRunning = false
                    emitState(ClashRuntimeEvent.ServiceRecreated)
                }
                Intents.ACTION_CLASH_STARTED -> {
                    clashRunning = true
                    emitState(ClashRuntimeEvent.Started)
                }
                Intents.ACTION_CLASH_STOPPED -> {
                    clashRunning = false
                    emitState(ClashRuntimeEvent.Stopped(intent.getStringExtra(Intents.EXTRA_STOP_REASON)))
                }
                Intents.ACTION_PROFILE_CHANGED -> emit(ClashRuntimeEvent.ProfileChanged)
                Intents.ACTION_PROFILE_LOADED -> emit(ClashRuntimeEvent.ProfileLoaded)
                Intents.ACTION_PROFILE_UPDATE_COMPLETED -> {
                    val raw = intent.getStringExtra(Intents.EXTRA_UUID)
                    emit(
                        ClashRuntimeEvent.ProfileUpdateCompleted(
                            raw?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                        ),
                    )
                }
                Intents.ACTION_PROFILE_UPDATE_FAILED -> {
                    val raw = intent.getStringExtra(Intents.EXTRA_UUID)
                    emit(
                        ClashRuntimeEvent.ProfileUpdateFailed(
                            raw?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                            intent.getStringExtra(Intents.EXTRA_FAIL_REASON),
                        ),
                    )
                }
            }
        }
    }

    /** Bind this hub to an [Application]; required before [register]/[unregister]. */
    fun attach(application: Application) {
        context = application
    }

    fun register() {
        val ctx = context ?: return
        if (!registered.compareAndSet(false, true)) return
        try {
            ctx.registerReceiverCompat(
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
            // Probe the real service state instead of assuming stopped: a quick unbind->rebind
            // cycle must not render a stale "not running".
            clashRunning = probeClashRunning(ctx)
        } catch (e: Exception) {
            registered.set(false)
            Log.w("Register runtime event receiver: $e", e)
        }
    }

    fun unregister() {
        val ctx = context ?: return
        if (!registered.compareAndSet(true, false)) return
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w("Unregister runtime event receiver: $e", e)
        }
        // Intentionally leave clashRunning untouched: it is driven by broadcasts and the
        // register() probe, so unbind() alone must not flip the running flag.
    }

    private fun emit(event: ClashRuntimeEvent) {
        if (!_events.tryEmit(event)) {
            Log.w("ClashRuntime: event buffer full, dropped ${event::class.simpleName}")
        }
    }

    private fun emitState(event: ClashRuntimeEvent) {
        _state.value = event
    }

    private fun probeClashRunning(ctx: Context): Boolean {
        return try {
            ctx.contentResolver.call(
                Uri.Builder()
                    .scheme("content")
                    .authority(Authorities.STATUS_PROVIDER)
                    .build(),
                StatusProvider.METHOD_WIDGET_STATE,
                null,
                null,
            )?.getBoolean(StatusProvider.KEY_RUNNING, false) ?: false
        } catch (e: Exception) {
            Log.w("Query clash running state: $e", e)
            false
        }
    }
}
