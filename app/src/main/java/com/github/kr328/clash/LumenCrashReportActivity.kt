package com.github.kr328.clash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.chloemlla.lumen.crash.LumenCrash
import com.chloemlla.lumen.crash.ui.LumenCrashReportScreen
import com.github.kr328.clash.common.util.intent

/**
 * Host surface for a pending Lumen Crash SDK report.
 *
 * The rest of the app remains view-based; this activity is the only Compose entry.
 * Must never process-kill on open: every SDK/Compose path is wrapped.
 */
class LumenCrashReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = runCatching {
            if (!LumenCrash.isInstalled()) return@runCatching null
            LumenCrash.loadPendingReport()
        }.getOrNull()

        if (report == null) {
            // Nothing to show — resume normal app without looping.
            resumeMainQuietly()
            return
        }

        val opened = runCatching {
            setContent {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    LumenCrashReportScreen(
                        report = report,
                        onContinue = {
                            runCatching { LumenCrash.clearPendingReport() }
                            resumeMainQuietly()
                        },
                        clearStoredReportOnContinue = true,
                    )
                }
            }
            true
        }.getOrDefault(false)

        if (!opened) {
            // Compose crash-UI deps missing / integrity blocked: drop report and resume app.
            runCatching { LumenCrash.clearPendingReport() }
            resumeMainQuietly()
        }
    }

    private fun resumeMainQuietly() {
        runCatching {
            startActivity(
                MainActivity::class.intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_REPORT_ID: String = "lumen_crash_report_id"
    }
}
