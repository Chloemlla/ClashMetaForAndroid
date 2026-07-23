package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.LocalSubscriptionTrafficStore
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Accumulates Clash core session traffic into [LocalSubscriptionTrafficStore]
 * for the currently active profile when local-subscription traffic mode is enabled.
 */
class LocalTrafficAccountingModule(service: Service) : Module<Unit>(service) {
    private val serviceStore = ServiceStore(service)
    private val trafficStore = LocalSubscriptionTrafficStore(service)

    private var trackedProfile: UUID? = null
    private var lastUploadBytes: Long = 0L
    private var lastDownloadBytes: Long = 0L

    override suspend fun run() = coroutineScope {
        val profileChanged = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_PROFILE_LOADED)
        }
        val ticker = ticker(TimeUnit.SECONDS.toMillis(2))

        trackedProfile = serviceStore.activeProfile
        captureBaseline()

        try {
            while (true) {
                select<Unit> {
                    profileChanged.onReceive {
                        flushDelta()
                        trackedProfile = serviceStore.activeProfile
                        captureBaseline()
                    }
                    ticker.onReceive {
                        val active = serviceStore.activeProfile
                        if (active != trackedProfile) {
                            flushDelta()
                            trackedProfile = active
                            captureBaseline()
                        } else {
                            flushDelta()
                        }
                    }
                }
            }
        } finally {
            flushDelta()
        }
    }

    private fun captureBaseline() {
        val (upload, download) = decodeTraffic(Clash.queryTrafficTotal())
        lastUploadBytes = upload
        lastDownloadBytes = download
    }

    private fun flushDelta() {
        if (!serviceStore.localSubscriptionTraffic) {
            // Upstream userinfo mode: do not accumulate local counters.
            captureBaseline()
            return
        }
        val uuid = trackedProfile ?: return
        val (upload, download) = decodeTraffic(Clash.queryTrafficTotal())

        // Core counters reset on profile reload / tunnel restart.
        if (upload < lastUploadBytes || download < lastDownloadBytes) {
            lastUploadBytes = upload
            lastDownloadBytes = download
            return
        }

        val uploadDelta = upload - lastUploadBytes
        val downloadDelta = download - lastDownloadBytes
        if (uploadDelta > 0L || downloadDelta > 0L) {
            trafficStore.add(uuid, uploadDelta, downloadDelta)
        }

        lastUploadBytes = upload
        lastDownloadBytes = download
    }

    private fun decodeTraffic(total: Long): Pair<Long, Long> {
        val upload = scaleTrafficBytes(total ushr 32)
        val download = scaleTrafficBytes(total and 0xFFFFFFFF)
        return upload to download
    }
}


