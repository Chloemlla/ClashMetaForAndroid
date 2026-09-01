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
import com.chloemlla.lumen.crash.CrashBreadcrumbs
import com.chloemlla.lumen.crash.LumenCrash
import com.github.kr328.clash.common.constants.Adblock
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.PartnerPairingNotifier
import com.github.kr328.clash.service.store.PartnerGrantStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.applyDynamicShortcuts
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    private var clashStarting = false
    private var clashStartWatchdog: Job? = null
    private val promptedPairings = mutableSetOf<String>()
    private val promptedAdblockProfiles = mutableSetOf<UUID>()
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
        ensureUpdateNotesAcknowledged()

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
                            clashStartWatchdog?.cancel()
                            clashStarting = false
                            design.setClashStarting(false)
                            design.fetch()
                            if (it == Event.ClashStart) {
                                maybePromptAdblockDownload()
                            }
                        }
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ProfileLoaded, Event.ProfileChanged -> {
                            design.fetch()
                            maybeShowAlphaMigrationToast(design)
                            if (it == Event.ActivityStart) {
                                maybeShowLegacyMigrationFailureToast(design)
                                promptPendingPairing()
                            }
                            maybePromptAdblockDownload()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning) {
                                clashStartWatchdog?.cancel()
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
                        MainDesign.Request.OpenConnections ->
                            startActivity(ConnectionsActivity::class.intent)
                        MainDesign.Request.OpenPartners ->
                            startActivity(PartnerAppsActivity::class.intent)
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


    /**
     * Raises the pairing dialog for a request that arrived while CMFA was in the background, where
     * the platform refuses an activity start. Each package is offered once per activity instance so
     * dismissing the dialog without answering cannot loop on every return to the home screen.
     */
    private suspend fun promptPendingPairing() {
        val pending = withContext(Dispatchers.IO) {
            PartnerGrantStore(this@MainActivity).pendingRequests()
        }
        val next = pending.firstOrNull { it.packageName !in promptedPairings } ?: return

        promptedPairings += next.packageName

        startActivity(PartnerPairingNotifier.pairingIntent(next.packageName, next.sha256))
    }

    /**
     * Prompts once per profile per activity instance to download the built-in adblock
     * rule-set. Skipped when the rules are already on disk, adblock is disabled, or the
     * tunnel isn't running. The download routes through the running config by default;
     * the user may instead pick a specific proxy group from the loaded config.
     */
    private suspend fun maybePromptAdblockDownload() {
        if (!clashRunning || !activityStarted) return

        val active = withProfile { queryActive() } ?: return
        if (!active.imported || active.uuid in promptedAdblockProfiles) return

        val override = withClash { queryOverride(Clash.OverrideSlot.Persist) }
        if (override.app.adblock == false) return

        if (withClash { isAdblockRulesReady() }) return

        promptedAdblockProfiles += active.uuid

        MaterialAlertDialogBuilder(this)
            .setTitle(DesignR.string.adblock_prompt_title)
            .setMessage(DesignR.string.adblock_prompt_message)
            .setPositiveButton(DesignR.string.adblock_prompt_download) { _, _ ->
                downloadAdblockRules(null)
            }
            .setNeutralButton(DesignR.string.adblock_prompt_choose_proxy) { _, _ ->
                showAdblockProxyPicker()
            }
            .setNegativeButton(DesignR.string.adblock_prompt_later, null)
            .show()
    }

    private fun showAdblockProxyPicker() {
        launch {
            val groups = withClash { queryProxyGroupNames(false) }

            if (groups.isEmpty()) {
                downloadAdblockRules(null)
                return@launch
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(DesignR.string.adblock_proxy_choose_title)
                .setItems(groups.toTypedArray()) { _, which -> downloadAdblockRules(groups[which]) }
                .setNegativeButton(DesignR.string.cancel, null)
                .show()
        }
    }

    private fun downloadAdblockRules(proxy: String?) {
        launch {
            try {
                withClash { updateAdblock(proxy) }
                design?.showToast(DesignR.string.adblock_download_complete, ToastDuration.Short)
            } catch (e: Exception) {
                recordBreadcrumbSafe("Adblock download failed: ${e::class.java.simpleName}")
                design?.showExceptionToast(
                    getString(DesignR.string.format_update_provider_failure, Adblock.PROVIDER_NAME, e.message)
                )
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

    private suspend fun maybeShowLegacyMigrationFailureToast(design: MainDesign) {
        // A-39: the migration keeps the old database and sets this flag when it throws. Without
        // surfacing it, a failed migration looks exactly like "you had no profiles".
        val failed = withContext(Dispatchers.IO) {
            val store = ServiceStore(this@MainActivity)
            store.legacyMigrationFailed.also { if (it) store.legacyMigrationFailed = false }
        }

        if (failed) {
            design.showToast(DesignR.string.legacy_migration_failed, ToastDuration.Long)
        }
    }

    private suspend fun ensureUpdateNotesAcknowledged() {
        val store = AppStore(this)
        val identity = "${BuildConfig.VERSION_CODE}|${BuildConfig.COMMIT_HASH}"
        val last = store.lastSeenBuildIdentity

        // Same build already acknowledged.
        if (last == identity) return

        // First install: OpenSourceNoticeActivity seeds lastSeen on accept so this is skipped.
        // Upgrade from builds without this field: last is blank while open-source is already accepted
        // → show immersive update notes once.
        if (last.isBlank() && !store.openSourceNoticeAccepted) {
            store.lastSeenBuildIdentity = identity
            return
        }

        startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            UpdateNotesActivity::class.intent,
        )

        if (AppStore(this).lastSeenBuildIdentity != identity) {
            finishAffinity()
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
                    return
                }
            }

            armClashStartWatchdog()
        } catch (e: Exception) {
            this@MainActivity.clashStarting = false
            setClashStarting(false)
            recordBreadcrumbSafe("Clash start failed: ${e::class.java.simpleName}")
            runCatching { LumenCrash.record(e) }
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    /**
     * The start/stop broadcasts are the only thing that clears [clashStarting], and neither
     * arrives when :background is killed while the core is still initializing. Without this the
     * toggle stays in "starting" forever and refuses every further tap.
     */
    private fun MainDesign.armClashStartWatchdog() {
        clashStartWatchdog?.cancel()
        clashStartWatchdog = this@MainActivity.launch {
            delay(CLASH_START_TIMEOUT_MILLIS)

            if (!this@MainActivity.clashStarting) return@launch

            this@MainActivity.clashStarting = false
            setClashStarting(false)
            recordBreadcrumbSafe("Clash start timed out")

            if (activityStarted) {
                showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
            }
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            // B-78: never touch core.bridge.Bridge from the UI process — merely referencing it
            // loads the whole mihomo native library (and class-init side effects) into the app
            // process. The core version is baked into BuildConfig at build time instead.
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + BuildConfig.CORE_VERSION.replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordBreadcrumbSafe("MainActivity.onCreate")
        applyDynamicShortcuts(uiStore.hideAppIcon)
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

    private companion object {
        const val CLASH_START_TIMEOUT_MILLIS = 25_000L
    }
}
