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
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.design.AccessControlDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.isKnownBrowserPackage
import com.github.kr328.clash.design.util.resolveBrowserPackages
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.github.kr328.clash.design.R as DesignR

class AccessControlActivity : BaseActivity<AccessControlDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            service.accessControlPackages.toMutableSet()
        }

        defer {
            withContext(Dispatchers.IO) {
                val changed = selected != service.accessControlPackages
                service.accessControlPackages = selected
                if (clashRunning && changed) {
                    stopClashService()
                    val stopped = withTimeoutOrNull(SERVICE_STOP_TIMEOUT_MILLIS) {
                        while (clashRunning) {
                            delay(200)
                        }
                        true
                    } == true

                    if (stopped) {
                        startClashService()
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                applicationContext,
                                DesignR.string.access_control_restart_timeout,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
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
                            val data = clipboard?.primaryClip

                            if (data != null && data.itemCount > 0) {
                                val packages = data.getItemAt(0).text.split("\n").toSet()
                                val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                selected.clear()
                                selected.addAll(all)
                            }

                            design.rebindAll()
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

    private suspend fun loadApps(selected: Set<String>, design: AccessControlDesign): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val reverse = uiStore.accessControlReverse
            val sort = uiStore.accessControlSort
            val systemApp = uiStore.accessControlSystemApp
            val onlySelected = design.onlySelectedFilter
            val onlyPartners = design.onlyPartnersFilter

            val base = compareByDescending<AppInfo> { it.packageName in selected }
            val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val browserPackages = resolveBrowserPackages(pm)

            packages.asSequence()
                .filter {
                    it.packageName != packageName
                }
                .filter {
                    it.applicationInfo != null
                }
                .filter {
                    it.requestedPermissions?.contains(INTERNET) == true || it.applicationInfo!!.uid < android.os.Process.FIRST_APPLICATION_UID
                }
                .filter {
                    systemApp || !it.isSystemApp
                }
                .map {
                    it.toAppInfo(this@AccessControlActivity, browserPackages)
                }
                .filter {
                    !onlySelected || it.packageName in selected
                }
                .filter {
                    !onlyPartners || it.isPartner
                }
                .sortedWith(comparator)
                .toList()
        }

    /**
     * Best-effort, filter-independent union of installed browser and
     * registered-partner packages, used by the "browsers + partners" preset.
     * Bypasses the system-app / display filters so hidden OEM system
     * browsers are still selectable.
     */
    private suspend fun loadBrowserAndPartnerPackages(): Set<String> =
        withContext(Dispatchers.IO) {
            val pm = packageManager
            val browserPackages = resolveBrowserPackages(pm)
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            packages.asSequence()
                .filter {
                    it.packageName != packageName
                }
                .filter {
                    it.applicationInfo != null
                }
                .filter {
                    it.requestedPermissions?.contains(INTERNET) == true || it.applicationInfo!!.uid < android.os.Process.FIRST_APPLICATION_UID
                }
                .map { it.packageName }
                .filter { pkg ->
                    pkg in browserPackages ||
                            isKnownBrowserPackage(pkg) ||
                            PartnerApps.isPartnerPackage(pkg)
                }
                .toSet()
        }

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
