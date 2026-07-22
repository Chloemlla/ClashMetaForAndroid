package com.github.kr328.clash

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.R
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.presentPendingLumenCrashReportIfNeeded
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService

/** App-private target for launcher shortcuts that change VPN state. */
class InternalControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        if (presentPendingLumenCrashReportIfNeeded()) return

        when (intent.action) {
            Intents.ACTION_TOGGLE_CLASH -> if (Remote.broadcasts.clashRunning) stopClash() else startClash()
            Intents.ACTION_START_CLASH -> if (Remote.broadcasts.clashRunning) showStarted() else startClash()
            Intents.ACTION_STOP_CLASH -> if (Remote.broadcasts.clashRunning) stopClash() else showStopped()
        }

        finish()
    }

    private fun startClash() {
        if (startClashService() != null) {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
            return
        }
        showStarted()
    }

    private fun stopClash() {
        stopClashService()
        showStopped()
    }

    private fun showStarted() =
        Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()

    private fun showStopped() =
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
