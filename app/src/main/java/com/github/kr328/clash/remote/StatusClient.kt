package com.github.kr328.clash.remote

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider

class StatusClient(private val context: Context) {
    private val uri: Uri
        get() {
            return Uri.Builder()
                .scheme("content")
                .authority(Authorities.STATUS_PROVIDER)
                .build()
        }

    fun currentProfile(): String? {
        return try {
            val result = context.contentResolver.call(
                uri,
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            )

            result?.getString("name")
        } catch (e: Exception) {
            Log.w("Query current profile: $e", e)

            null
        }
    }

    /**
     * Cross-process read of running/profile plus optional mode/node/rates.
     * Returns null only when the provider call fails entirely.
     */
    fun widgetState(): WidgetStatusSnapshot? {
        return try {
            val result = context.contentResolver.call(
                uri,
                StatusProvider.METHOD_WIDGET_STATE,
                null,
                null,
            ) ?: return null

            WidgetStatusSnapshot(
                running = result.getBoolean(StatusProvider.KEY_RUNNING, false),
                profileName = result.getString(StatusProvider.KEY_NAME),
                hasDetail = result.getBoolean(StatusProvider.KEY_HAS_DETAIL, false),
                mode = result.getString(StatusProvider.KEY_MODE),
                selectedNode = result.getString(StatusProvider.KEY_SELECTED_NODE),
                upRateBytesPerSec = result.getLong(StatusProvider.KEY_UP_RATE, 0L),
                downRateBytesPerSec = result.getLong(StatusProvider.KEY_DOWN_RATE, 0L),
            )
        } catch (e: Exception) {
            Log.w("Query widget state: $e", e)
            null
        }
    }
}

/** Read-only snapshot from [StatusProvider.METHOD_WIDGET_STATE]. */
data class WidgetStatusSnapshot(
    val running: Boolean,
    val profileName: String?,
    val hasDetail: Boolean,
    val mode: String?,
    val selectedNode: String?,
    val upRateBytesPerSec: Long,
    val downRateBytesPerSec: Long,
)
