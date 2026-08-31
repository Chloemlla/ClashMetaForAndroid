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
    private const val DEFAULT_PARTNER_PAIRING = "$DEFAULT_UI_PACKAGE.PartnerPairingActivity"
    private const val DEFAULT_PARTNER_APPS = "$DEFAULT_UI_PACKAGE.PartnerAppsActivity"

    @Volatile
    private var mainActivityOverride: ComponentName? = null

    @Volatile
    private var propertiesActivityOverride: ComponentName? = null

    /**
     * Override notification / VPN configure targets for white-label or SDK hosts.
     *
     * Only non-null components are replaced, so a partial override never clears the other
     * target (B-135). Pass a component to override that target; use [reset] to restore every
     * target to the corresponding CMFA default.
     */
    @JvmStatic
    fun configure(
        mainActivity: ComponentName? = null,
        propertiesActivity: ComponentName? = null,
    ) {
        mainActivity?.let { mainActivityOverride = it }
        propertiesActivity?.let { propertiesActivityOverride = it }
    }

    /** Restore both overridden components to their CMFA defaults. */
    @JvmStatic
    fun reset() {
        mainActivityOverride = null
        propertiesActivityOverride = null
    }

    val MAIN_ACTIVITY: ComponentName
        get() = mainActivityOverride
            ?: ComponentName(packageName, DEFAULT_MAIN)

    val PROPERTIES_ACTIVITY: ComponentName
        get() = propertiesActivityOverride
            ?: ComponentName(packageName, DEFAULT_PROPERTIES)

    /** Centred confirmation dialog raised when a partner app first asks for Clash status. */
    val PARTNER_PAIRING_ACTIVITY: ComponentName
        get() = ComponentName(packageName, DEFAULT_PARTNER_PAIRING)

    /** Partner app list: which apps are covered by the tunnel and what each of them may read. */
    val PARTNER_APPS_ACTIVITY: ComponentName
        get() = ComponentName(packageName, DEFAULT_PARTNER_APPS)
}