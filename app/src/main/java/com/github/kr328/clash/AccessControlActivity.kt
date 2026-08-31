package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.design.AccessControlDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.isKnownBrowserPackage
import com.github.kr328.clash.design.util.queryIgnoringBatteryOptimizations
import com.github.kr328.clash.design.util.resolveBrowserPackages
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.github.kr328.clash.design.R as DesignR

class AccessControlActivity : BaseActivity<AccessControlDesign>() {
    private var installedAppsCache: InstalledApps? = null

    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            service.accessControlPackages.toMutableSet()
        }

        defer {
            // Read before the first suspension: leaving the page unregisters the broadcast
            // receiver, which resets the cached running flag to false.
            val running = clashRunning

            val changed = withContext(Dispatchers.IO) {
                val changed = selected != service.accessControlPackages
                service.accessControlPackages = selected
                changed
            }

            if (running && changed) {
                restartClashForAccessControl()
            }
        }

        val design = AccessControlDesign(this, uiStore, selected)

        setContentDesign(design)

        design.requests.send(AccessControlDesign.Request.ReloadApps)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        AccessControlDesign.Request.ReloadApps -> {
                            design.patchApps(loadApps(selected, design))
                        }

                        AccessControlDesign.Request.SelectAll -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName)
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectNone -> {
                            selected.clear()

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectInvert -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName).toSet() - selected
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectBrowsersAndPartners -> {
                            val preset = loadBrowserAndPartnerPackages()

                            selected.addAll(preset)

                            design.rebindAll()

                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    applicationContext,
                                    applicationContext.getString(
                                        DesignR.string.access_control_preset_selected,
                                        preset.size
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        AccessControlDesign.Request.Import -> {
                            val clipboard = getSystemService<ClipboardManager>()
                            // Non-text clip items (images, URIs) have a null `text`; coerceToText
                            // is the only accessor that survives them.
                            val text = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(this@AccessControlActivity)
                                ?.toString()

                            if (text.isNullOrBlank()) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        applicationContext,
                                        DesignR.string.geofile_import_failed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                val packages = text.split("\n")
                                    .mapTo(mutableSetOf()) { it.trim() }
                                val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                selected.clear()
                                selected.addAll(all)

                                design.rebindAll()
                            }
                        }

                        AccessControlDesign.Request.Export -> {
                            val clipboard = getSystemService<ClipboardManager>()

                            val data = ClipData.newPlainText(
                                "packages",
                                selected.joinToString("\n")
                            )

                            clipboard?.setPrimaryClip(data)
                        }

                        AccessControlDesign.Request.OpenBatterySettings -> {
                            openBatteryOptimizationSettings()
                        }
                    }
                }
            }
        }
    }

    /**
     * The stop-then-start sequence has to outlive this Activity: cancelled half-way it would
     * leave the tunnel down with nobody left to bring it back up.
     */
    private fun restartClashForAccessControl() {
        val context = applicationContext

        Global.launch(NonCancellable) {
            context.stopClashService()

            val stopped = withTimeoutOrNull(SERVICE_STOP_TIMEOUT_MILLIS) {
                while (Remote.broadcasts.clashRunning) {
                    delay(200)
                }
                true
            } == true

            if (stopped) {
                context.startClashService()
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        DesignR.string.access_control_restart_timeout,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun loadApps(selected: Set<String>, design: AccessControlDesign): List<AppInfo> {
        val installed = installedApps()

        return withContext(Dispatchers.IO) {
            val reverse = uiStore.accessControlReverse
            val sort = uiStore.accessControlSort
            val systemApp = uiStore.accessControlSystemApp
            val onlySelected = design.onlySelectedFilter
            val onlyPartners = design.onlyPartnersFilter

            val base = compareByDescending<AppInfo> { it.packageName in selected }
            val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

            installed.packages.asSequence()
                .filter {
                    systemApp || !it.isSystemApp
                }
                // Both display filters only need the package id, and toAppInfo costs one
                // battery-optimization Binder round-trip per app. Filtering first keeps that cost
                // off the apps this pass will never show.
                .filter {
                    !onlySelected || it.packageName in selected
                }
                .filter {
                    !onlyPartners || it.packageName in installed.partnerPackages
                }
                .map {
                    it.toAppInfo(
                        this@AccessControlActivity,
                        installed.browserPackages,
                        installed.partnerPackages,
                        installed.batteryOptimizationIgnored.getOrPut(it.packageName) {
                            queryIgnoringBatteryOptimizations(
                                this@AccessControlActivity,
                                it.packageName,
                            )
                        },
                    )
                }
                .sortedWith(comparator)
                .toList()
        }
    }

    /**
     * Best-effort, filter-independent union of installed browser and
     * registered-partner packages, used by the "browsers + partners" preset.
     * Bypasses the system-app / display filters so hidden OEM system
     * browsers are still selectable.
     */
    private suspend fun loadBrowserAndPartnerPackages(): Set<String> {
        val installed = installedApps()

        return withContext(Dispatchers.Default) {
            installed.packages.asSequence()
                .map { it.packageName }
                .filter { pkg ->
                    pkg in installed.browserPackages ||
                            isKnownBrowserPackage(pkg) ||
                            pkg in installed.partnerPackages
                }
                .toSet()
        }
    }

    /**
     * One enumeration per Activity instance. Reload requests only re-apply sort and display
     * filters, so re-querying PackageManager (and re-hashing every signing certificate for the
     * partner set) on each of them buys nothing and costs seconds on app-heavy devices.
     */
    private suspend fun installedApps(): InstalledApps {
        installedAppsCache?.let { return it }

        return withContext(Dispatchers.IO) {
            val pm = packageManager

            InstalledApps(
                packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    it.packageName != packageName &&
                            it.applicationInfo != null &&
                            (
                                it.requestedPermissions?.contains(INTERNET) == true ||
                                    it.applicationInfo!!.uid < android.os.Process.FIRST_APPLICATION_UID
                                )
                },
                browserPackages = resolveBrowserPackages(pm),
                partnerPackages = PartnerApps.installedPartnerPackages(this@AccessControlActivity),
            )
        }.also { installedAppsCache = it }
    }

    private class InstalledApps(
        val packages: List<PackageInfo>,
        val browserPackages: Set<String>,
        val partnerPackages: Set<String>,
        /**
         * Memoized per package. Only touched from the sequential request loop, which is also why a
         * plain map is enough here.
         */
        val batteryOptimizationIgnored: MutableMap<String, Boolean?> = mutableMapOf(),
    )

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        val started = runCatching { startActivity(intent) }.isSuccess

        if (!started) {
            Toast.makeText(
                applicationContext,
                DesignR.string.access_control_battery_settings_unavailable,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        }

    companion object {
        private const val SERVICE_STOP_TIMEOUT_MILLIS = 10_000L
    }
}
