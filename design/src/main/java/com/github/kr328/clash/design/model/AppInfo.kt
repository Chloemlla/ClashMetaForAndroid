package com.github.kr328.clash.design.model

data class AppInfo(
    val packageName: String,
    val label: String,
    val installTime: Long,
    val updateDate: Long,
    /** True when [packageName] is a registered CMFA partner package. */
    val isPartner: Boolean = false,
    /** True when [packageName] resolves as a browser (CATEGORY_APP_BROWSER or known fallback). */
    val isBrowser: Boolean = false,
    /**
     * Best-effort battery optimization state for [packageName]:
     * true = ignoring optimizations (safe), false = not ignoring (may be killed),
     * null = unknown / not queryable on this device.
     */
    val batteryOptimizationIgnored: Boolean? = null,
)
