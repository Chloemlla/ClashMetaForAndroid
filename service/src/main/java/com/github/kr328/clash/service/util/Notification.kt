package com.github.kr328.clash.service.util

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat

fun Context.notifyIfAllowed(id: Int, notification: Notification) {
    // areNotificationsEnabled() is the version-correct gate: POST_NOTIFICATIONS only exists on
    // API 33+, and checking it unconditionally made every notification silently drop on API 26-32
    // (B-178). On 33+ it reflects the POST_NOTIFICATIONS grant; below that, the app-level
    // notification setting.
    if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
