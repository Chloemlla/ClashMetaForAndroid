package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    private var clashStarting = false
    private val notificationPermissionLauncher =
        registerForActivityResult(RequestPermission()) { granted ->
            if (!granted) {
                launch {
                    design?.showToast(
                        DesignR.string.notification_permission_denied,
                        ToastDuration.Indefinite
                    ) {
                        setAction(DesignR.string.settings) {
                            openNotificationSettings()
                        }
                    }
                }
            }
        }

    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()
        maybeShowAlphaMigrationToast(design)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop -> {
                            clashStarting = false
                            design.setClashStarting(false)
                            design.fetch()
                        }
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ProfileLoaded, Event.ProfileChanged -> {
                            design.fetch()
                            maybeShowAlphaMigrationToast(design)
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning) {
                                clashStarting = false
                                design.setClashStarting(false)
                                stopClashService()
                            } else if (!clashStarting) {
                                design.startClash()
                            }
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.CreateProfile ->
                            startActivity(NewProfileActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }


    private suspend fun maybeShowAlphaMigrationToast(design: MainDesign) {
        val store = AppStore(this)
        if (!store.alphaMigrationToastPending) return

        val count = store.alphaMigrationImportedCount
        store.alphaMigrationToastPending = false
        store.alphaMigrationImportedCount = 0

        if (count > 0) {
            design.showToast(
                getString(DesignR.string.alpha_migration_success, count),
                ToastDuration.Long,
            )
        }
    }
    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        val selectedNode = if (clashRunning) {
            withClash {
                val groups = queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                val preferred = uiStore.proxyLastGroup
                val groupName = when {
                    preferred.isNotBlank() && preferred in groups -> preferred
                    groups.isNotEmpty() -> groups.first()
                    else -> null
                }
                groupName?.let { queryProxyGroup(it, ProxySort.Default).now }?.takeIf { it.isNotBlank() }
            }
        } else {
            null
        }

        setProxySummary(state.mode, selectedNode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }

        if (clashRunning) {
            fetchTraffic()
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTrafficSummary(
                total = queryTrafficTotal(),
                now = queryTrafficNow(),
            )
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.create_profile) {
                    startActivity(NewProfileActivity::class.intent)
                }
            }

            return
        }

        if (!requestNotificationPermissionIfNeeded()) {
            this@MainActivity.clashStarting = false
            setClashStarting(false)
            return
        }

        this@MainActivity.clashStarting = true
        setClashStarting(true)

        try {
            val vpnRequest = startClashService()

            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    startClashService()
                } else {
                    this@MainActivity.clashStarting = false
                    setClashStarting(false)
                }
            }
        } catch (e: Exception) {
            this@MainActivity.clashStarting = false
            setClashStarting(false)
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupShortcuts()
    }

    private suspend fun requestNotificationPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return true

        val continueRequest = suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(DesignR.string.notification_permission_title)
                .setMessage(DesignR.string.notification_permission_rationale)
                .setPositiveButton(DesignR.string.continue_) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton(DesignR.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .show()

            dialog.setOnDismissListener {
                if (continuation.isActive) continuation.resume(false)
            }
            continuation.invokeOnCancellation { dialog.dismiss() }
        }

        if (continueRequest) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        return continueRequest
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        }
        startActivity(intent)
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, InternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, InternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, InternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}




