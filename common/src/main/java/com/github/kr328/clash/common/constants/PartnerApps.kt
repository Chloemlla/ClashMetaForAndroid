package com.github.kr328.clash.common.constants

import android.content.Context
import android.content.pm.PackageManager

/**
 * Known partner apps that should be auto-included in VPN access control
 * and allowed to query lightweight Clash running status.
 */
object PartnerApps {
    val piliPlusPackages: Set<String> = setOf(
        "com.chloemlla.piliplus",
        "com.chloemlla.piliplus.debug",
        "com.chloemlla.piliplus.dev",
    )

    val nexAiPackages: Set<String> = setOf(
        "com.chloemlla.nexai",
        "com.chloemlla.nexai.debug",
        "com.chloemlla.nexai.dev",
    )

    val projectLumenPackages: Set<String> = setOf(
        "com.chloemlla.projectlumen",
        "com.chloemlla.projectlumen.debug",
        "com.chloemlla.projectlumen.dev",
    )

    val zhihuPlusPackages: Set<String> = setOf(
        "com.chloemlla.zhplus",
        "com.chloemlla.zhplus.lite",
    )

    /** All partner applicationIds (release + common suffixes). */
    val allPackages: Set<String> =
        piliPlusPackages + nexAiPackages + projectLumenPackages + zhihuPlusPackages

    fun isPiliPlusPackage(packageName: String): Boolean =
        packageName in piliPlusPackages

    fun isPartnerPackage(packageName: String): Boolean =
        packageName in allPackages

    fun installedPartnerPackages(context: Context): Set<String> {
        val pm = context.packageManager
        return allPackages.filter { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }.toSet()
    }

    /** Backward-compatible alias used by older call sites. */
    fun installedPiliPlusPackages(context: Context): Set<String> =
        installedPartnerPackages(context)
}
