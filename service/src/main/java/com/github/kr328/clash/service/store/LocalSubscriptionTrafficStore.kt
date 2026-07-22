package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.service.PreferenceProvider
import java.util.UUID

/**
 * Local per-subscription traffic counters that intentionally ignore upstream
 * subscription-userinfo headers. Every profile starts at 0 B and only grows from
 * traffic observed while that profile is active in this app.
 */
class LocalSubscriptionTrafficStore(context: Context) {
    private val preferences = PreferenceProvider.createSharedPreferencesFromContext(context)

    fun getUpload(uuid: UUID): Long {
        return preferences.getLong(uploadKey(uuid), 0L).coerceAtLeast(0L)
    }

    fun getDownload(uuid: UUID): Long {
        return preferences.getLong(downloadKey(uuid), 0L).coerceAtLeast(0L)
    }

    fun getUsed(uuid: UUID): Long {
        return getUpload(uuid) + getDownload(uuid)
    }

    @Synchronized
    fun add(uuid: UUID, uploadDelta: Long, downloadDelta: Long) {
        val up = uploadDelta.coerceAtLeast(0L)
        val down = downloadDelta.coerceAtLeast(0L)
        if (up == 0L && down == 0L) return

        preferences.edit()
            .putLong(uploadKey(uuid), getUpload(uuid) + up)
            .putLong(downloadKey(uuid), getDownload(uuid) + down)
            .apply()
    }

    @Synchronized
    fun reset(uuid: UUID) {
        preferences.edit()
            .putLong(uploadKey(uuid), 0L)
            .putLong(downloadKey(uuid), 0L)
            .apply()
    }

    @Synchronized
    fun clear(uuid: UUID) {
        preferences.edit()
            .remove(uploadKey(uuid))
            .remove(downloadKey(uuid))
            .apply()
    }

    private fun uploadKey(uuid: UUID): String = "local_sub_traffic_upload_$uuid"

    private fun downloadKey(uuid: UUID): String = "local_sub_traffic_download_$uuid"
}
