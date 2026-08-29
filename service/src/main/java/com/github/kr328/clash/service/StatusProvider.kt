package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.WidgetStateStore

class StatusProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_CURRENT_PROFILE -> {
                // The profile name is detail-tier: only a fully trusted caller sees it.
                if (accessFor(method).tier != PartnerAccessTier.Full) {
                    return null
                }
                return if (serviceRunning)
                    Bundle().apply {
                        putString("name", currentProfile)
                    }
                else
                    null
            }
            METHOD_PARTNER_STATUS -> partnerStatus(accessFor(method))
            // Self-only read surface for home widgets (no control, no secrets).
            METHOD_WIDGET_STATE -> {
                if (!isSelfCaller()) {
                    return null
                }
                val state = WidgetStateStore.current()
                Bundle().apply {
                    putBoolean(KEY_RUNNING, serviceRunning)
                    putString(KEY_NAME, currentProfile)
                    if (state != null) {
                        putBoolean(KEY_HAS_DETAIL, true)
                        putString(KEY_MODE, state.mode)
                        putString(KEY_SELECTED_NODE, state.selectedNode)
                        putLong(KEY_UP_RATE, state.upRateBytesPerSec)
                        putLong(KEY_DOWN_RATE, state.downRateBytesPerSec)
                    } else {
                        putBoolean(KEY_HAS_DETAIL, false)
                    }
                }
            }
            else -> super.call(method, arg, extras)
        }
    }

    /**
     * Status split by sensitivity. The basic tier carries only what a partner needs to pick its
     * own egress (is the core up, is the tunnel carrying this device's traffic); profile name,
     * selected node, traffic counters and runtime errors stay behind full trust. A denied caller
     * still gets [KEY_DENIED_REASON] so it can tell the user what to do instead of reporting an
     * unexplained "no status".
     */
    private fun partnerStatus(access: PartnerAccess): Bundle {
        val bundle = Bundle().apply {
            putInt(KEY_API_VERSION, PARTNER_STATUS_API_VERSION)
            putString(KEY_ACCESS_TIER, access.tier.name.lowercase())
            access.reason?.let { putString(KEY_DENIED_REASON, it) }
        }
        if (access.tier == PartnerAccessTier.Denied) {
            return bundle
        }

        val ctx = context
        val autoAdapt = ctx?.let { ServiceStore(it).partnerAppAutoAdapt } ?: true
        bundle.apply {
            putBoolean("running", serviceRunning)
            // v2: granular VPN state (0=disconnected, 1=connecting, 2=connected).
            // Keep vpnRunning for backward compatibility with v1 clients.
            putBoolean("vpnRunning", vpnRunning)
            putInt(KEY_VPN_STATE, if (vpnRunning) VPN_STATE_CONNECTED else VPN_STATE_DISCONNECTED)
            // Keep legacy key for older partner clients.
            putBoolean("piliPlusAutoAdapt", autoAdapt)
            putBoolean("partnerAppAutoAdapt", autoAdapt)
            putString("package", ctx?.packageName)
        }
        if (access.tier != PartnerAccessTier.Full) {
            return bundle
        }

        // Read-only enrichment sourced from the same in-memory snapshot widgets use.
        // Never add subscription URL / ageSecretKey / full config here (F-17 adjacent
        // secret-hygiene) and never grant start/stop control (F-12).
        val state = WidgetStateStore.current()
        return bundle.apply {
            putString("name", currentProfile)
            if (state != null) {
                putString(KEY_MODE, state.mode)
                putString(KEY_SELECTED_NODE, state.selectedNode)
                putLong(KEY_UP_TOTAL, state.upTotalBytes)
                putLong(KEY_DOWN_TOTAL, state.downTotalBytes)
                // v2: proxy delay, alive proxies, memory usage
                putLong(KEY_PROXY_DELAY, state.proxyDelay)
                putInt(KEY_ALIVE_PROXIES, state.aliveProxies)
                putLong(KEY_MEMORY_USAGE, state.memoryUsageBytes)
            }
            // v2: last error from the clash runtime, null when healthy
            putString(KEY_LAST_ERROR, lastError)
        }
    }

    private fun accessFor(method: String): PartnerAccess {
        val ctx = context
            ?: return PartnerAccess(
                PartnerAccessTier.Denied,
                PartnerAccessResolver.REASON_NOT_PARTNER,
                null,
                null,
            )
        if (isSelfCaller()) {
            return PartnerAccess(PartnerAccessTier.Full, null, ctx.packageName, null)
        }
        val packages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?.filterNotNull()
            .orEmpty()
        return PartnerAccessResolver.resolve(ctx, packages).also {
            PartnerAccessResolver.logDecision(method, it)
        }
    }

    private fun isSelfCaller(): Boolean {
        val ctx = context ?: return false
        val packages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?: return false
        return packages.any { it == ctx.packageName }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw IllegalArgumentException("Stub!")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        throw IllegalArgumentException("Stub!")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun getType(uri: Uri): String? {
        throw IllegalArgumentException("Stub!")
    }

    override fun onCreate(): Boolean {
        return true
    }

    companion object {
        const val METHOD_CURRENT_PROFILE = "currentProfile"
        const val METHOD_PARTNER_STATUS = "partnerStatus"
        /** Read-only widget snapshot; self-app only (not partners). */
        const val METHOD_WIDGET_STATE = "widgetState"

        const val KEY_RUNNING = "running"
        const val KEY_NAME = "name"
        const val KEY_HAS_DETAIL = "hasDetail"
        const val KEY_MODE = "mode"
        const val KEY_SELECTED_NODE = "selectedNode"
        const val KEY_UP_RATE = "upRate"
        const val KEY_DOWN_RATE = "downRate"

        /** `partnerStatus` bundle schema version; bump when fields are added/removed. */
        const val KEY_API_VERSION = "apiVersion"
        const val KEY_UP_TOTAL = "upTotal"
        const val KEY_DOWN_TOTAL = "downTotal"
        const val PARTNER_STATUS_API_VERSION = 3

        // v2 fields
        const val KEY_VPN_STATE = "vpnState"
        const val KEY_PROXY_DELAY = "proxyDelay"
        const val KEY_ALIVE_PROXIES = "aliveProxies"
        const val KEY_MEMORY_USAGE = "memoryUsage"
        const val KEY_LAST_ERROR = "lastError"

        // v3 fields: tell the caller how much it was granted and, when short of full access,
        // exactly why — a partner can then show actionable guidance instead of "no status".
        const val KEY_ACCESS_TIER = "accessTier"
        const val KEY_DENIED_REASON = "deniedReason"

        const val VPN_STATE_DISCONNECTED = 0
        const val VPN_STATE_CONNECTING = 1
        const val VPN_STATE_CONNECTED = 2

        private const val CLASH_SERVICE_RUNNING_FILE = "service_running.lock"

        @Volatile
        var serviceRunning: Boolean = false
            set(value) {
                field = value

                shouldStartClashOnBoot = value
            }
        @Volatile
        var vpnRunning: Boolean = false
        @Volatile
        var lastError: String? = null
        @Volatile
        var currentProfile: String? = null
        var shouldStartClashOnBoot: Boolean
            get() = Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).exists()
            set(value) {
                Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).apply {
                    if (value)
                        createNewFile()
                    else
                        delete()
                }
            }
    }
}
