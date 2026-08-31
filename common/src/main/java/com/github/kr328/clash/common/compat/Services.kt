package com.github.kr328.clash.common.compat

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import com.github.kr328.clash.common.log.Log

// Returns false instead of throwing: on Android 12+ a background caller gets
// ForegroundServiceStartNotAllowedException, which must not kill the calling process.
fun Context.startForegroundServiceCompat(intent: Intent): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        true
    } catch (e: Exception) {
        Log.w("startForegroundServiceCompat: ${intent.component ?: intent.action} - $e", e)
        false
    }
}

fun Service.startForegroundCompat(id: Int, notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(id, notification)
    }
}

fun Service.stopForegroundCompat() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
}
