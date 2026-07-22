package com.github.kr328.clash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.chloemlla.lumen.crash.LumenCrash
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.util.presentPendingLumenCrashReportIfNeeded

/**
 * Legacy entry kept for deep-links / old intents.
 * All crash UX is owned by [LumenCrashReportActivity].
 */
class AppCrashedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val presented = runCatching { presentPendingLumenCrashReportIfNeeded() }.getOrDefault(false)
        if (presented) {
            finish()
            return
        }

        // No pending Lumen report: still open the Lumen surface (it self-finishes when empty).
        if (runCatching { LumenCrash.isInstalled() }.getOrDefault(false)) {
            runCatching { startActivity(LumenCrashReportActivity::class.intent) }
        }
        finish()
    }
}
