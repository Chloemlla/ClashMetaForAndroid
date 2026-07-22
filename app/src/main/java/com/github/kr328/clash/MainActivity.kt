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
import com.chloemlla.lumen.crash.CrashBreadcrumbs
import com.chloemlla.lumen.crash.LumenCrash
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.bridge.*
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
        ensureOpenSourceNoticeAccepted()

        val design = MainDesign(this)

        setContentDesign(design)

        // Initial state is driven by Event.ActivityStart (posted from onStart). An extra
        // explicit fetch() here races that event and doubles Binder/core queries on cold start.
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
                                recordBreadcrumbSafe("Clash stop requested")
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
                // Match Profiles/Providers: do not poll traffic while MainActivity is stopped.
                if (clashRunning && activityStarted) {
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

    private suspend fun ensureOpenSourceNoticeAccepted() {
        val store = AppStore(this)
        if (store.openSourceNoticeAccepted) return

        startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            OpenSourceNoticeActivity::class.intent,
        )

        if (!AppStore(this).openSourceNoticeAccepted) {
            finishAffinity()
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        // Single core/JSON summary: mode + hasProviders + selected node (no full lists).
        // Fail soft: dashboard/native decode issues must never take down the home screen.
        runCatching {
            withClash {
                val summary = queryDashboardSummary(
                    preferred = uiStore.proxyLastGroup,
                    excludeNotSelectable = uiStore.proxyExcludeNotSelectable,
                )
                val selected = summary.selectedNow.takeIf { clashRunning && it.isNotBlank() }
                setProxySummary(summary.mode, selected)
                setHasProviders(summary.hasProviders)
            }
        }.onFailure { error ->
            recordBreadcrumbSafe("MainDesign.fetch summary failed: ${error::class.java.simpleName}")
            runCatching { LumenCrash.record(error) }
        }

        runCatching {
            withProfile {
                setProfileName(queryActive()?.name)
            }
        }.onFailure { error ->
            recordBreadcrumbSafe("MainDesign.fetch profile failed: ${error::class.java.simpleName}")
        }

        if (clashRunning) {
            runCatching { fetchTraffic() }
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
            recordBreadcrumbSafe("Clash start blocked: no profile selected")
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
            recordBreadcrumbSafe("Clash start blocked: notification permission")
            return
        }

        this@MainActivity.clashStarting = true
        setClashStarting(true)
        recordBreadcrumbSafe("Clash start requested profile=${active.name}")

        try {
            val vpnRequest = startClashService()

            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    recordBreadcrumbSafe("VPN permission granted; restarting clash service")
                    startClashService()
                } else {
                    this@MainActivity.clashStarting = false
                    setClashStarting(false)
                    recordBreadcrumbSafe("Clash start cancelled: VPN permission denied")
                }
            }
        } catch (e: Exception) {
            this@MainActivity.clashStarting = false
            setClashStarting(false)
            recordBreadcrumbSafe("Clash start failed: ${e::class.java.simpleName}")
            runCatching { LumenCrash.record(e) }
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
        recordBreadcrumbSafe("MainActivity.onCreate")
        setupShortcuts()
    }

    private fun recordBreadcrumbSafe(event: String) {
        if (!LumenCrash.isInstalled()) return
        runCatching { CrashBreadcrumbs.record(event) }
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




