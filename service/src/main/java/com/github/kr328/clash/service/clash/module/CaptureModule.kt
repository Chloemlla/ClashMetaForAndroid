package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.util.CaptureStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ADB-triggered traffic capture module.
 *
 * Listens for [Intents.ACTION_TRAFFIC_CAPTURE] broadcasts (sent via ADB) and
 * manages the [CaptureStore] lifecycle. Subscribes to DNS and connection feeds
 * while capture is active.
 *
 * ADB usage:
 *   adb shell am broadcast -a <pkg>.intent.action.TRAFFIC_CAPTURE \
 *       --es capture_action start_capture \
 *       --ei capture_duration 60
 *   adb shell am broadcast -a <pkg>.intent.action.TRAFFIC_CAPTURE \
 *       --es capture_action stop_capture
 */
class CaptureModule(service: Service) : Module<Unit>(service) {

    override suspend fun run() = coroutineScope {
        val broadcasts = receiveBroadcast(requireSelf = false) {
            addAction(Intents.ACTION_TRAFFIC_CAPTURE)
        }

        try {
            for (intent in broadcasts) {
                val action = intent.getStringExtra(Intents.EXTRA_CAPTURE_ACTION)
                    ?: continue

                when (action) {
                    "start_capture" -> {
                        if (CaptureStore.isActive) {
                            Log.i("CaptureModule: already capturing")
                            continue
                        }
                        val duration = intent.getIntExtra(
                            Intents.EXTRA_CAPTURE_DURATION, 60
                        )
                        CaptureStore.start(service, durationMs = duration * 1000L)
                        Log.i("CaptureModule: capture started (${duration}s)")
                        runCapture()
                    }
                    "stop_capture" -> {
                        if (!CaptureStore.isActive) {
                            Log.i("CaptureModule: not capturing")
                            continue
                        }
                        CaptureStore.stop()
                        Log.i("CaptureModule: capture stopped")
                    }
                }
            }
        } finally {
            // Ensure capture is stopped on module teardown.
            if (CaptureStore.isActive) {
                CaptureStore.stop()
            }
        }
    }

    /**
     * Capture loop — subscribes to DNS and connection feeds while capture is active.
     */
    private suspend fun runCapture() = coroutineScope {
        // Subscribe to DNS capture events.
        val dnsChannel = Clash.subscribeDns()

        // Route DNS events to CaptureStore.
        launch {
            try {
                for (record in dnsChannel) {
                    CaptureStore.enqueue("dns", record)
                }
            } catch (e: Exception) {
                Log.w("CaptureModule: DNS channel error: ${e.message}", e)
            }
        }

        // PR3: subscribe to enhanced ConnectionSnapshot → CaptureStore.enqueue("connection", ...)
        // PR3: subscribe to HttpRecord channel → CaptureStore.enqueue("http", ...)

        // Poll until capture stops.
        try {
            while (CaptureStore.isActive) {
                delay(500)
            }
        } finally {
            dnsChannel.cancel()
        }
    }
}