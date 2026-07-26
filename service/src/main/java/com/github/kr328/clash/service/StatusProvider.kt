package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.constants.PartnerApps
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.WidgetStateStore

class StatusProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_CURRENT_PROFILE -> {
                if (!isSelfOrPartnerCaller()) {
                    return null
                }
                return if (serviceRunning)
                    Bundle().apply {
                        putString("name", currentProfile)
                    }
                else
                    null
            }
            METHOD_PARTNER_STATUS -> {
                if (!isSelfOrPartnerCaller()) {
                    return null
                }
                val ctx = context
                val autoAdapt = ctx?.let { ServiceStore(it).partnerAppAutoAdapt } ?: true
                Bundle().apply {
                    putBoolean("running", serviceRunning)
                    putBoolean("vpnRunning", vpnRunning)
                    // Keep legacy key for older partner clients.
                    putBoolean("piliPlusAutoAdapt", autoAdapt)
                    putBoolean("partnerAppAutoAdapt", autoAdapt)
                    putString("name", currentProfile)
                    putString("package", context?.packageName)
                }
            }
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

    private fun isSelfCaller(): Boolean {
        val ctx = context ?: return false
        val packages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?: return false
        return packages.any { it == ctx.packageName }
    }

    private fun isSelfOrPartnerCaller(): Boolean {
        if (isSelfCaller()) {
            return true
        }
        val ctx = context ?: return false
        val packages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?: return false
        return packages.any { PartnerApps.isPartnerPackage(it) }
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

        private const val CLASH_SERVICE_RUNNING_FILE = "service_running.lock"

        var serviceRunning: Boolean = false
            set(value) {
                field = value

                shouldStartClashOnBoot = value
            }
        var vpnRunning: Boolean = false
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
        var currentProfile: String? = null
    }
}
