package com.github.kr328.clash.service.util

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

fun Context.notifyIfAllowed(id: Int, notification: Notification) {
    if (
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
