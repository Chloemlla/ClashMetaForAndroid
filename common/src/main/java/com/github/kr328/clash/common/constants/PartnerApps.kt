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

    fun isPiliPlusPackage(packageName: String): Boolean =
        packageName in piliPlusPackages

    fun installedPiliPlusPackages(context: Context): Set<String> {
        val pm = context.packageManager
        return piliPlusPackages.filter { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }.toSet()
    }
}
