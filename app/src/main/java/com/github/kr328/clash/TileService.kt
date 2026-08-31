package com.github.kr328.clash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.service.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class TileService : TileService() {
    private var currentProfile = ""
    private var clashRunning = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var registered = false

    override fun onClick() {
        val tile = qsTile ?: return

        when (tile.state) {
            Tile.STATE_INACTIVE -> {
                // startClashService() returns the consent Intent *and starts nothing*. The panel
                // cannot host the system dialog, so hand the user to the app instead of no-oping.
                if (startClashService() != null) {
                    openApp()
                }
            }
            Tile.STATE_ACTIVE -> {
                stopClashService()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        if (!registered) {
            registered = runCatching {
                registerReceiverCompat(
                    receiver,
                    IntentFilter().apply {
                        addAction(Intents.ACTION_CLASH_STARTED)
                        addAction(Intents.ACTION_CLASH_STOPPED)
                        addAction(Intents.ACTION_PROFILE_LOADED)
                        addAction(Intents.ACTION_SERVICE_RECREATED)
                    },
                    Permissions.RECEIVE_SELF_BROADCASTS,
                    null
                )
            }.isSuccess
        }

        // Draw what is already known first: currentProfile() is a ContentProvider call into
        // :background and may have to cold-start that process while the panel is animating.
        updateTile()
        refreshStatus()
    }

    override fun onStopListening() {
        super.onStopListening()

        refreshJob?.cancel()

        if (registered) {
            // Some ROMs deliver onStopListening without a matching successful registration.
            runCatching { unregisterReceiver(receiver) }
            registered = false
        }
    }

    override fun onDestroy() {
        scope.cancel()

        super.onDestroy()
    }

    private fun refreshStatus() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { StatusClient(this@TileService).currentProfile() }.getOrNull()
            }

            clashRunning = name != null
            currentProfile = name ?: ""

            updateTile()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQ_OPEN_APP,
                    intent,
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return

        tile.state = if (clashRunning)
            Tile.STATE_ACTIVE
        else
            Tile.STATE_INACTIVE

        tile.label = if (currentProfile.isEmpty())
            getText(R.string.launch_name)
        else
            currentProfile

        tile.icon = Icon.createWithResource(this, R.drawable.ic_logo_service)

        tile.updateTile()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intents.ACTION_CLASH_STARTED -> {
                    clashRunning = true

                    currentProfile = ""
                }
                Intents.ACTION_CLASH_STOPPED, Intents.ACTION_SERVICE_RECREATED -> {
                    clashRunning = false

                    currentProfile = ""
                }
                Intents.ACTION_PROFILE_LOADED -> {
                    refreshStatus()

                    return
                }
            }

            updateTile()
        }
    }

    private companion object {
        private const val REQ_OPEN_APP = 4201
    }
}
