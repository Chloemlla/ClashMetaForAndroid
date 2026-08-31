package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConnectionSnapshot
import com.github.kr328.clash.core.model.DnsRecord
import com.github.kr328.clash.core.model.HttpRecord
import com.github.kr328.clash.service.util.CaptureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Traffic capture module.
 *
 * Listens for [Intents.ACTION_TRAFFIC_CAPTURE] self-broadcasts and manages the
 * [CaptureStore] lifecycle. Subscribes to DNS, connection, and HTTP feeds while
 * capture is active.
 */
class CaptureModule(service: Service) : Module<Unit>(service) {

    override suspend fun run() = coroutineScope {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_TRAFFIC_CAPTURE)
        }

        var captureJob: Job? = null

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
                        captureJob = launch { runCapture() }
                    }
                    "stop_capture" -> {
                        if (!CaptureStore.isActive) {
                            Log.i("CaptureModule: not capturing")
                            continue
                        }
                        CaptureStore.stop()
                        captureJob?.cancelAndJoin()
                        captureJob = null
                        Log.i("CaptureModule: capture stopped")
                    }
                }
            }
        } finally {
            // Ensure capture is stopped on module teardown.
            if (CaptureStore.isActive) {
                CaptureStore.stop()
            }
            captureJob?.cancel()
        }
    }

    /**
     * Capture loop — subscribes to DNS, connection, and HTTP feeds while capture is active.
     */
    private suspend fun runCapture() = coroutineScope {
        // Subscribe to DNS capture events.
        val dnsChannel = Clash.subscribeDns()

        // Subscribe to periodic connection snapshots (enhanced connection details).
        val connectionChannel = Clash.subscribeConnections()

        // Subscribe to HTTP capture events (plaintext HTTP request/response).
        val httpChannel = Clash.subscribeHttp()

        // Route DNS events to CaptureStore.
        launch {
            try {
                for (record in dnsChannel) {
                    CaptureStore.enqueue("dns", DnsRecord.serializer(), record)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CaptureModule: DNS channel error: ${e.message}", e)
            }
        }

        // Route connection snapshots to CaptureStore.
        launch {
            try {
                for (snapshot in connectionChannel) {
                    CaptureStore.enqueue("connection", ConnectionSnapshot.serializer(), snapshot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CaptureModule: connection channel error: ${e.message}", e)
            }
        }

        // Route HTTP events to CaptureStore.
        launch {
            try {
                for (record in httpChannel) {
                    CaptureStore.enqueue("http", HttpRecord.serializer(), record)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CaptureModule: HTTP channel error: ${e.message}", e)
            }
        }

        // Poll until capture stops.
        try {
            while (CaptureStore.isActive) {
                delay(500)
            }
        } finally {
            dnsChannel.cancel()
            connectionChannel.cancel()
            httpChannel.cancel()
        }
    }
}