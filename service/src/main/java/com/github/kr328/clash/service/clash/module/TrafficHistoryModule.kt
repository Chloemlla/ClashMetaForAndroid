package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.SystemClock
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

    /** Last monotonic reading at which memory usage was sampled (throttled; see [MEMORY_SAMPLE_INTERVAL_MS]). */
    private var lastMemorySampleElapsedMs: Long = Long.MIN_VALUE

    private var lastBroadcastElapsedMs: Long = 0L
    private var lastBroadcastState: WidgetState? = null

    override suspend fun run() = coroutineScope {
        // Process-local buffer may survive a prior runtime in the same process;
        // reset so min-interval gate / history do not leak across restarts.
        buffer.clear()
        lastUpRate = Long.MIN_VALUE
        lastDownRate = Long.MIN_VALUE
        lastUpTotal = Long.MIN_VALUE
        lastDownTotal = Long.MIN_VALUE
        lastMemorySampleElapsedMs = Long.MIN_VALUE
        lastBroadcastElapsedMs = 0L
        lastBroadcastState = null

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
            // Interval gates use the monotonic clock; epochMs stays wall-clock for display.
            val elapsedMs = SystemClock.elapsedRealtime()
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
                    elapsedMs = elapsedMs,
                )
            }

            val summary = runCatching {
                Clash.queryDashboardSummary(preferred = "", excludeNotSelectable = true)
            }.getOrElse { DashboardSummary() }

            // Compute proxy delay and alive count from the selected group.
            var proxyDelay = 0L
            var aliveProxies = 0
            if (summary.selectedNow.isNotEmpty()) {
                val delays = runCatching {
                    Clash.queryGroupDelays(summary.selectedNow)
                }.getOrNull()
                if (delays != null) {
                    for ((_, delay) in delays) {
                        if (delay > 0) {
                            aliveProxies++
                            if (proxyDelay == 0L || delay < proxyDelay) {
                                proxyDelay = delay.toLong()
                            }
                        }
                    }
                }
            }

            // Memory sampling is throttled: queryMemoryUsage() calls runtime.ReadMemStats,
            // which stop-the-worlds the Go proxy core. Sample at most every 30s.
            var memoryUsage = 0L
            if (lastMemorySampleElapsedMs == Long.MIN_VALUE ||
                elapsedMs - lastMemorySampleElapsedMs >= MEMORY_SAMPLE_INTERVAL_MS
            ) {
                memoryUsage = runCatching {
                    Clash.queryMemoryUsage()
                }.getOrElse { 0L }
                lastMemorySampleElapsedMs = elapsedMs
            } else {
                memoryUsage = WidgetStateStore.current()?.memoryUsageBytes ?: 0L
            }

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
                    proxyDelay = proxyDelay,
                    aliveProxies = aliveProxies,
                    memoryUsageBytes = memoryUsage,
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
                force = true,
            )
        } catch (e: Exception) {
            Log.w("TrafficHistoryModule: stopped snapshot failed: ${e.message}", e)
        }
    }

    /**
     * Store + notify same-app AppWidget observers when content actually changes.
     * Uses package-targeted self-broadcast (no third-party delivery).
     *
     * The broadcast wakes the main process out of its cached state, so rate-only churn is
     * rate-limited to [BROADCAST_MIN_INTERVAL_MS] and skipped entirely with no widget placed.
     */
    private fun publishWidgetState(state: WidgetState, force: Boolean = false) {
        if (!WidgetStateStore.update(state)) {
            return
        }
        if (!force && !shouldBroadcast(state)) {
            return
        }
        if (!hasPlacedWidgets()) {
            return
        }
        lastBroadcastElapsedMs = SystemClock.elapsedRealtime()
        lastBroadcastState = state
        service.sendBroadcastSelf(Intent(WidgetStateStore.ACTION_WIDGET_STATE_CHANGED))
    }

    private fun shouldBroadcast(state: WidgetState): Boolean {
        val previous = lastBroadcastState ?: return true
        val contentChanged = state.running != previous.running ||
            state.profileName != previous.profileName ||
            state.mode != previous.mode ||
            state.selectedNode != previous.selectedNode
        if (contentChanged) {
            return true
        }
        return SystemClock.elapsedRealtime() - lastBroadcastElapsedMs >= BROADCAST_MIN_INTERVAL_MS
    }

    private fun hasPlacedWidgets(): Boolean {
        val manager = AppWidgetManager.getInstance(service) ?: return false
        return manager.getInstalledProvidersForPackage(service.packageName, null).any {
            manager.getAppWidgetIds(it.provider).isNotEmpty()
        }
    }

    companion object {
        private val SAMPLE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(2)

        // queryMemoryUsage() triggers Go runtime.ReadMemStats (STW). Sample it
        // far less often than the 2s traffic cadence to avoid periodic proxy stalls.
        private val MEMORY_SAMPLE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30)

        private val BROADCAST_MIN_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5)
    }
}
