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
 */
class LumenCrashReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = runCatching { LumenCrash.loadPendingReport() }.getOrNull()
        if (report == null) {
            finish()
            return
        }

        val opened = runCatching {
            setContent {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    LumenCrashReportScreen(
                        report = report,
                        onContinue = {
                            runCatching { LumenCrash.clearPendingReport() }
                            startActivity(MainActivity::class.intent)
                            finish()
                        },
                        clearStoredReportOnContinue = true,
                    )
                }
            }
        }.isSuccess

        if (!opened) {
            // Compose crash-UI deps missing / integrity blocked: drop report and resume app.
            runCatching { LumenCrash.clearPendingReport() }
            startActivity(MainActivity::class.intent)
            finish()
        }
    }
}