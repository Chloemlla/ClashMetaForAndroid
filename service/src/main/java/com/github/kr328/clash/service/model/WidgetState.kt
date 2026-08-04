package com.github.kr328.clash.service.model

/**
 * Read-only snapshot for home-screen widgets, StatusProvider, and dashboard UI.
 *
 * M2 adds [proxyDelay], [aliveProxies], [memoryUsageBytes] for partner-app
 * status bar display and debug panel.
 */
data class WidgetState(
    val running: Boolean,
    val profileName: String?,
    val mode: String,
    val selectedNode: String,
    val upRateBytesPerSec: Long,
    val downRateBytesPerSec: Long,
    val upTotalBytes: Long,
    val downTotalBytes: Long,
    val updatedAtEpochMs: Long,
    /** Average delay (ms) of the currently selected proxy, or 0 if unknown. */
    val proxyDelay: Long = 0L,
    /** Count of alive proxies (delay > 0) in the selected proxy group, or 0. */
    val aliveProxies: Int = 0,
    /** Clash core Go runtime allocated memory in bytes. */
    val memoryUsageBytes: Long = 0L,
) {
    /**
     * Content equality for skip-if-unchanged publishers.
     * Ignores [updatedAtEpochMs] so pure time ticks do not force redraws.
     */
    fun sameAs(other: WidgetState?): Boolean {
        if (other == null) return false
        return running == other.running &&
            profileName == other.profileName &&
            mode == other.mode &&
            selectedNode == other.selectedNode &&
            upRateBytesPerSec == other.upRateBytesPerSec &&
            downRateBytesPerSec == other.downRateBytesPerSec &&
            upTotalBytes == other.upTotalBytes &&
            downTotalBytes == other.downTotalBytes &&
            proxyDelay == other.proxyDelay &&
            aliveProxies == other.aliveProxies &&
            memoryUsageBytes == other.memoryUsageBytes
    }
}