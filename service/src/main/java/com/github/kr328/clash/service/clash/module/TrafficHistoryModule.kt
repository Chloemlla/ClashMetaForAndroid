package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.DashboardSummary
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.model.WidgetState
import com.github.kr328.clash.service.store.TrafficHistoryStore
import com.github.kr328.clash.service.store.WidgetStateStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

/**
 * Samples Clash traffic into a bounded ring buffer and refreshes [WidgetStateStore].
 *
 * Decoupled from [DynamicNotificationModule] so history keeps a stable ≥2s cadence
 * regardless of screen interactive state. Skip-if-unchanged rates+totals to save CPU.
 *
 * Failures in dashboard summary lookup must not kill the VPN runtime.
 */
class TrafficHistoryModule(service: Service) : Module<Unit>(service) {
    private val buffer = TrafficHistoryStore.buffer

    private var lastUpRate: Long = Long.MIN_VALUE
    private var lastDownRate: Long = Long.MIN_VALUE
    private var lastUpTotal: Long = Long.MIN_VALUE
    private var lastDownTotal: Long = Long.MIN_VALUE

    override suspend fun run() = coroutineScope {
        val ticker = ticker(SAMPLE_INTERVAL_MS)

        while (true) {
            select<Unit> {
                ticker.onReceive { nowMs ->
                    sample(nowMs)
                }
            }
        }
    }

    private fun sample(nowMs: Long) {
        val (upRate, downRate) = splitTrafficBytes(Clash.queryTrafficNow())
        val (upTotal, downTotal) = splitTrafficBytes(Clash.queryTrafficTotal())

        val trafficChanged =
            upRate != lastUpRate ||
                downRate != lastDownRate ||
                upTotal != lastUpTotal ||
                downTotal != lastDownTotal

        if (trafficChanged) {
            lastUpRate = upRate
            lastDownRate = downRate
            lastUpTotal = upTotal
            lastDownTotal = downTotal

            buffer.tryAppend(
                TrafficHistorySample(
                    epochMs = nowMs,
                    upRateBytesPerSec = upRate,
                    downRateBytesPerSec = downRate,
                    upTotalBytes = upTotal,
                    downTotalBytes = downTotal,
                ),
            )
        }

        val summary = runCatching {
            Clash.queryDashboardSummary(preferred = "", excludeNotSelectable = true)
        }.getOrElse { DashboardSummary() }

        WidgetStateStore.update(
            WidgetState(
                running = StatusProvider.serviceRunning,
                profileName = StatusProvider.currentProfile,
                mode = summary.mode.name,
                selectedNode = summary.selectedNow,
                upRateBytesPerSec = upRate,
                downRateBytesPerSec = downRate,
                upTotalBytes = upTotal,
                downTotalBytes = downTotal,
                updatedAtEpochMs = nowMs,
            ),
        )
    }

    companion object {
        private val SAMPLE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(2)
    }
}
