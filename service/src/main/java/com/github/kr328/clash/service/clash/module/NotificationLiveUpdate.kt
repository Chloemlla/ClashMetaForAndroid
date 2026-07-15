package com.github.kr328.clash.service.clash.module

import androidx.core.app.NotificationCompat

/**
 * Android Live Update helpers for the Clash status notification.
 *
 * Live Updates require an ongoing, non-custom, titled notification that requests
 * promotion via [NotificationCompat.Builder.setRequestPromotedOngoing], plus the
 * non-runtime [android.Manifest.permission.POST_PROMOTED_NOTIFICATIONS] permission.
 *
 * @see <a href="https://developer.android.google.cn/develop/ui/views/notifications/live-update">Create live update notifications</a>
 */
internal fun NotificationCompat.Builder.applyClashLiveUpdate(
    shortCriticalText: String? = null,
): NotificationCompat.Builder {
    setOngoing(true)
    setRequestPromotedOngoing(true)
    // Live Update eligibility forbids setColorized(true); keep tint via setColor only.
    setColorized(false)
    if (!shortCriticalText.isNullOrBlank()) {
        // Status chips are narrow (~96dp / ~7 chars). Drop spaces so rates still fit.
        setShortCriticalText(
            shortCriticalText.replace(" ", "").take(STATUS_CHIP_TEXT_LIMIT),
        )
    }
    return this
}

private const val STATUS_CHIP_TEXT_LIMIT = 7
