package com.github.kr328.clash.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.chloemlla.lumen.crash.LumenCrash
import com.github.kr328.clash.LumenCrashReportActivity
import com.github.kr328.clash.common.util.intent

/**
 * Present the pending Lumen crash report UI if one exists.
 * Returns true when crash UI owns the process and the caller must stop.
 */
fun Activity.presentPendingLumenCrashReportIfNeeded(): Boolean {
    if (!LumenCrash.isInstalled()) return false
    // loadPendingReport() is fail-closed on integrity; always wrap so a
    // corrupt / stripped SDK never process-kills Activity.onCreate.
    runCatching { LumenCrash.loadPendingReport() }.getOrNull() ?: return false
    startActivity(
        LumenCrashReportActivity::class.intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
    )
    finish()
    return true
}

/**
 * Best-effort host hook after a crash report is persisted by the SDK.
 * Keeps upload/telemetry out of the SDK; currently logs + breadcrumb only.
 */
fun Context.onLumenCrashSaved(reportId: String) {
    runCatching {
        com.github.kr328.clash.common.log.Log.i("LumenCrash saved reportId=$reportId")
    }
    if (LumenCrash.isInstalled()) {
        runCatching {
            com.chloemlla.lumen.crash.CrashBreadcrumbs.record("crash_saved id=$reportId")
        }
    }
}