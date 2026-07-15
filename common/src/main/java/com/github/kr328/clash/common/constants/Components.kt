package com.github.kr328.clash.common.constants

import android.content.ComponentName
import com.github.kr328.clash.common.util.packageName

/**
 * Host-facing activity components used by service notifications and VPN configure intents.
 *
 * CMFA ships defaults pointing at the stock UI. Embedded hosts (Runtime SDK) should call
 * [configure] once during application start so deep-links return to the host shell.
 */
object Components {
    private const val DEFAULT_UI_PACKAGE = "com.github.kr328.clash"
    private const val DEFAULT_MAIN = "$DEFAULT_UI_PACKAGE.MainActivity"
    private const val DEFAULT_PROPERTIES = "$DEFAULT_UI_PACKAGE.PropertiesActivity"

    @Volatile
    private var mainActivityOverride: ComponentName? = null

    @Volatile
    private var propertiesActivityOverride: ComponentName? = null

    /**
     * Override notification / VPN configure targets for white-label or SDK hosts.
     * Pass null to restore the corresponding CMFA default.
     */
    @JvmStatic
    fun configure(
        mainActivity: ComponentName? = null,
        propertiesActivity: ComponentName? = null,
    ) {
        mainActivityOverride = mainActivity
        propertiesActivityOverride = propertiesActivity
    }

    val MAIN_ACTIVITY: ComponentName
        get() = mainActivityOverride
            ?: ComponentName(packageName, DEFAULT_MAIN)

    val PROPERTIES_ACTIVITY: ComponentName
        get() = propertiesActivityOverride
            ?: ComponentName(packageName, DEFAULT_PROPERTIES)
}