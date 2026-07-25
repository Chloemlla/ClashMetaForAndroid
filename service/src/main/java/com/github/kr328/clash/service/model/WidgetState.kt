package com.github.kr328.clash.service.model

/**
 * Read-only snapshot for future home-screen widgets and dashboard UI.
 *
 * In-process only for M1 — no AppWidgetProvider, no Binder surface expansion.
 * M2 may observe this via broadcast / AppWidgetManager.
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
            downTotalBytes == other.downTotalBytes
    }
}
