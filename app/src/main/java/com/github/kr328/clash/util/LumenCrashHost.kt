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
 *
 * All SDK calls are fail-soft: a stripped/integrity-blocked SDK must never
 * process-kill Activity.onCreate (README field lesson: white-screen / instant exit).
 */
fun Activity.presentPendingLumenCrashReportIfNeeded(): Boolean {
    val installed = runCatching { LumenCrash.isInstalled() }.getOrDefault(false)
    if (!installed) return false

    val pending = runCatching { LumenCrash.loadPendingReport() }.getOrNull() ?: return false

    return runCatching {
        startActivity(
            LumenCrashReportActivity::class.intent
                .putExtra(LumenCrashReportActivity.EXTRA_REPORT_ID, pending.reportId)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        // Do not call finish() here. The host activity finishing before the crash
        // surface is resumed has caused flash-exit / no-UI on some ROMs when the
        // process only had one activity. LumenCrashReportActivity is singleTask and
        // owns the next paint; callers already return early and skip their work.
        true
    }.getOrDefault(false)
}

/**
 * Best-effort host hook after a crash report is persisted by the SDK.
 * Keeps upload/telemetry out of the SDK; currently logs + breadcrumb only.
 */
fun Context.onLumenCrashSaved(reportId: String) {
    runCatching {
        com.github.kr328.clash.common.log.Log.i("LumenCrash saved reportId=$reportId")
    }
    runCatching {
        if (LumenCrash.isInstalled()) {
            com.chloemlla.lumen.crash.CrashBreadcrumbs.record("crash_saved id=$reportId")
        }
    }
}
