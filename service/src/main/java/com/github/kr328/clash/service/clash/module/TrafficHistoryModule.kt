package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.DashboardSummary
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.model.WidgetState
import com.github.kr328.clash.service.store.TrafficHistoryStore
import com.github.kr328.clash.service.store.WidgetStateStore
import com.github.kr328.clash.service.util.sendBroadcastSelf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

/**
 * Samples Clash traffic into a bounded ring buffer and refreshes [WidgetStateStore].
 *
 * Decoupled from [DynamicNotificationModule] so history keeps a stable ≥2s cadence
 * regardless of screen interactive state. Skip-if-unchanged rates+totals to save CPU.
 *
 * Sample failures are isolated: never let a lookup exception tear down the Clash runtime.
 */
class TrafficHistoryModule(service: Service) : Module<Unit>(service) {
    private val buffer = TrafficHistoryStore.buffer

    private var lastUpRate: Long = Long.MIN_VALUE
    private var lastDownRate: Long = Long.MIN_VALUE
    private var lastUpTotal: Long = Long.MIN_VALUE
    private var lastDownTotal: Long = Long.MIN_VALUE

    override suspend fun run() = coroutineScope {
        // Process-local buffer may survive a prior runtime in the same process;
        // reset so min-interval gate / history do not leak across restarts.
        buffer.clear()
        lastUpRate = Long.MIN_VALUE
        lastDownRate = Long.MIN_VALUE
        lastUpTotal = Long.MIN_VALUE
        lastDownTotal = Long.MIN_VALUE

        val ticker = ticker(SAMPLE_INTERVAL_MS)

        try {
            while (true) {
                select<Unit> {
                    ticker.onReceive { nowMs ->
                        sample(nowMs)
                    }
                }
            }
        } finally {
            // Service/module teardown: avoid leaving in-process consumers with running=true.
            publishStoppedSnapshot()
        }
    }

    private fun sample(nowMs: Long) {
        try {
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

            publishWidgetState(
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
        } catch (e: Exception) {
            // Keep sampling on the next tick; do not fail the module coroutine / VPN runtime.
            Log.w("TrafficHistoryModule: sample failed: ${e.message}", e)
        }
    }

    private fun publishStoppedSnapshot() {
        try {
            val previous = WidgetStateStore.current()
            publishWidgetState(
                WidgetState(
                    running = false,
                    profileName = StatusProvider.currentProfile,
                    mode = previous?.mode ?: TunnelState.Mode.Rule.name,
                    selectedNode = previous?.selectedNode.orEmpty(),
                    upRateBytesPerSec = 0L,
                    downRateBytesPerSec = 0L,
                    upTotalBytes = previous?.upTotalBytes ?: 0L,
                    downTotalBytes = previous?.downTotalBytes ?: 0L,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            Log.w("TrafficHistoryModule: stopped snapshot failed: ${e.message}", e)
        }
    }

    /**
     * Store + notify same-app AppWidget observers when content actually changes.
     * Uses package-targeted self-broadcast (no third-party delivery).
     */
    private fun publishWidgetState(state: WidgetState) {
        if (!WidgetStateStore.update(state)) {
            return
        }
        service.sendBroadcastSelf(Intent(WidgetStateStore.ACTION_WIDGET_STATE_CHANGED))
    }

    companion object {
        private val SAMPLE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(2)
    }
}
