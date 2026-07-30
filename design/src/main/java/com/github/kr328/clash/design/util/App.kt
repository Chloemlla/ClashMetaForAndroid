package com.github.kr328.clash.design.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.design.model.AppInfo

/**
 * Well-known browser package ids used as a fail-soft fallback when runtime
 * CATEGORY_APP_BROWSER resolution is unavailable or restricted on an OEM ROM.
 */
private val KNOWN_BROWSER_PACKAGES: Set<String> = setOf(
    "com.android.chrome",
    "com.chrome.beta",
    "com.chrome.dev",
    "com.chrome.canary",
    "org.mozilla.firefox",
    "org.mozilla.firefox_beta",
    "org.mozilla.fenix",
    "com.microsoft.emmx",
    "com.opera.browser",
    "com.opera.browser.beta",
    "com.opera.mini.native",
    "com.opera.gx",
    "com.brave.browser",
    "com.duckduckgo.mobile.android",
    "com.UCMobile.intl",
    "com.uc.browser.en",
    "com.vivaldi.browser",
    "com.sec.android.app.sbrowser",
    "com.mi.globalbrowser",
    "com.huawei.browser",
    "mark.via.gp",
    "com.kiwibrowser.browser",
    "com.qihoo.contents",
    "com.qwant.liberty",
)

/**
 * Pure classifier: true when [packageName] matches a well-known browser
 * package id. Used as a fallback when runtime CATEGORY_APP_BROWSER
 * resolution fails or returns nothing.
 */
fun isKnownBrowserPackage(packageName: String): Boolean =
    packageName in KNOWN_BROWSER_PACKAGES

/**
 * Best-effort, fail-soft resolution of packages registered as
 * CATEGORY_APP_BROWSER handlers on this device. Returns an empty set
 * instead of throwing on OEM PackageManager quirks.
 */
fun resolveBrowserPackages(pm: PackageManager): Set<String> {
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
            .addCategory(Intent.CATEGORY_APP_BROWSER)

        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}

/**
 * Best-effort, fail-soft battery-optimization lookup for [packageName].
 * Returns null (unknown) when [PowerManager] is unavailable or the query
 * throws on this device, rather than crashing the caller.
 */
fun queryIgnoringBatteryOptimizations(context: Context, packageName: String): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val powerManager = context.getSystemService<PowerManager>() ?: return null

    return runCatching { powerManager.isIgnoringBatteryOptimizations(packageName) }.getOrNull()
}

fun PackageInfo.toAppInfo(context: Context, browserPackages: Set<String> = emptySet()): AppInfo {
    val pm = context.packageManager

    return AppInfo(
        packageName = packageName,
        label = applicationInfo!!.loadLabel(pm).toString(),
        installTime = firstInstallTime,
        updateDate = lastUpdateTime,
        isPartner = PartnerApps.isPartnerPackage(packageName),
        isBrowser = packageName in browserPackages || isKnownBrowserPackage(packageName),
        batteryOptimizationIgnored = queryIgnoringBatteryOptimizations(context, packageName),
    )
}
