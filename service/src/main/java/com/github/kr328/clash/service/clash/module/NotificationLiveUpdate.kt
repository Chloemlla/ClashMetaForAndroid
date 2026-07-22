package com.github.kr328.clash.service.clash.module

import android.content.Context
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.service.R
import java.util.Locale
import kotlin.math.max

/**
 * Android Live Update helpers for the Clash status notification.
 *
 * Live Updates require an ongoing, non-custom, titled notification that requests
 * promotion via the promoted-ongoing extras, plus the non-runtime
 * [android.Manifest.permission.POST_PROMOTED_NOTIFICATIONS] permission.
 *
 * Host keeps androidx.core on the AGP 8.8 / compileSdk 35 line (1.16.x), so
 * request/promoted APIs are written through extras instead of newer Builder helpers.
 *
 * @see <a href="https://developer.android.google.cn/develop/ui/views/notifications/live-update">Create live update notifications</a>
 */
internal fun NotificationCompat.Builder.applyClashLiveUpdate(
    shortCriticalText: String? = null,
): NotificationCompat.Builder {
    setOngoing(true)
    setOnlyAlertOnce(true)
    setShowWhen(false)
    setCategory(NotificationCompat.CATEGORY_SERVICE)
    // Live Update eligibility forbids setColorized(true); keep tint via setColor only.
    setColorized(false)
    setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
    if (!shortCriticalText.isNullOrBlank()) {
        extras.putString(
            EXTRA_SHORT_CRITICAL_TEXT,
            shortCriticalText.compactStatusChipText(),
        )
    }
    return this
}

internal fun Context.liveProfileTitle(profileName: String?): String {
    return profileName?.takeIf { it.isNotBlank() } ?: getString(R.string.profile_not_selected)
}

internal fun Context.liveSpeedContent(upload: String, download: String): String {
    return getString(R.string.clash_live_speed_content, upload, download)
}

internal fun Context.liveTotalContent(upload: String, download: String): String {
    return getString(R.string.clash_live_total_content, upload, download)
}

/**
 * Build a short status-chip label from instantaneous traffic.
 * Prefers the busier direction so the chip tracks meaningful activity.
 */
internal fun Context.liveSpeedChipText(now: Traffic): String {
    val upBytes = scaleTrafficBytes(now ushr 32)
    val downBytes = scaleTrafficBytes(now and 0xFFFFFFFF)
    val dominant = max(upBytes, downBytes)
    if (dominant <= 0L) {
        return getString(R.string.clash_live_chip_idle)
    }
    val rate = compactRate(dominant)
    return getString(R.string.clash_live_chip_speed, rate)
}

internal fun Context.liveStaticChipText(loading: Boolean): String {
    return getString(
        if (loading) R.string.clash_live_chip_loading else R.string.clash_live_chip_running,
    )
}

internal data class LiveTrafficSnapshot(
    val contentText: String,
    val subText: String,
    val chipText: String,
) {
    fun sameAs(other: LiveTrafficSnapshot?): Boolean {
        if (other == null) return false
        return contentText == other.contentText &&
            subText == other.subText &&
            chipText == other.chipText
    }
}

internal fun Context.liveTrafficSnapshot(now: Traffic, total: Traffic): LiveTrafficSnapshot {
    val uploading = now.trafficUpload()
    val downloading = now.trafficDownload()
    val uploaded = total.trafficUpload()
    val downloaded = total.trafficDownload()
    return LiveTrafficSnapshot(
        contentText = liveSpeedContent("$uploading/s", "$downloading/s"),
        subText = liveTotalContent(uploaded, downloaded),
        chipText = liveSpeedChipText(now),
    )
}

private fun String.compactStatusChipText(): String {
    return replace(" ", "")
        .replace('\t', ' ')
        .trim()
        .take(STATUS_CHIP_TEXT_LIMIT)
}

private fun compactRate(bytesPerSecond: Long): String {
    val value = bytesPerSecond.toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> formatCompact(value / (1024.0 * 1024.0 * 1024.0), "G")
        value >= 1024.0 * 1024.0 -> formatCompact(value / (1024.0 * 1024.0), "M")
        value >= 1024.0 -> formatCompact(value / 1024.0, "K")
        else -> "${bytesPerSecond}B"
    }
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


// Platform extras used by Android 16 Live Updates / status chips.
private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
private const val STATUS_CHIP_TEXT_LIMIT = 7

/**
 * Decode Clash packed traffic samples into raw byte counts.
 */
internal fun scaleTrafficBytes(value: Long): Long {
    val type = (value ushr 30) and 0x3
    val data = value and 0x3FFFFFFF
    return when (type) {
        0L -> data
        1L -> data * 1024
        2L -> data * 1024 * 1024
        3L -> data * 1024 * 1024 * 1024
        else -> 0L
    }
}

