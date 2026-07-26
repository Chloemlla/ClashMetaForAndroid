package com.github.kr328.clash.widget

import java.util.Locale

/** Pure formatting helpers for widget RemoteViews (unit-testable). */
object WidgetFormat {
    private const val DEFAULT_MAX_PROFILE = 18
    private const val DEFAULT_MAX_NODE = 20

    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        if (maxChars == 1) return "…"
        return text.take(maxChars - 1) + "…"
    }

    fun truncateProfile(text: String, maxChars: Int = DEFAULT_MAX_PROFILE): String =
        truncate(text, maxChars)

    fun truncateNode(text: String, maxChars: Int = DEFAULT_MAX_NODE): String =
        truncate(text, maxChars)

    /**
     * Compact rate like notification chip: `12.3K`, `1.2M`, `800B`.
     */
    fun compactRate(bytesPerSecond: Long): String {
        val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
        return when {
            value >= 1024.0 * 1024.0 * 1024.0 ->
                formatCompact(value / (1024.0 * 1024.0 * 1024.0), "G")
            value >= 1024.0 * 1024.0 ->
                formatCompact(value / (1024.0 * 1024.0), "M")
            value >= 1024.0 ->
                formatCompact(value / 1024.0, "K")
            else -> "${bytesPerSecond.coerceAtLeast(0L)}B"
        }
    }

    fun ratesLine(upBytesPerSec: Long, downBytesPerSec: Long): String {
        val up = compactRate(upBytesPerSec)
        val down = compactRate(downBytesPerSec)
        return "↑$up/s  ↓$down/s"
    }

    private fun formatCompact(value: Double, unit: String): String {
        val text = if (value >= 10.0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
                .trimEnd('0')
                .trimEnd('.')
        }
        return text + unit
    }
}
