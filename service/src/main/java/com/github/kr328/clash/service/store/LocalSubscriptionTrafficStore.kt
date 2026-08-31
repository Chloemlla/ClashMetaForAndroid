package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.service.PreferenceProvider
import java.util.UUID

/**
 * Local per-subscription traffic counters that intentionally ignore upstream
 * subscription-userinfo headers. Every profile starts at 0 B and only grows from
 * traffic observed while that profile is active in this app.
 *
 * Callers each construct their own instance, so the read-modify-write mutators
 * hold a class-level lock instead of an instance one.
 */
class LocalSubscriptionTrafficStore(context: Context) {
    private val preferences = PreferenceProvider.createSharedPreferencesFromContext(context)

    init {
        migrateInflatedCountersIfNeeded()
    }

    fun getUpload(uuid: UUID): Long {
        return preferences.getLong(uploadKey(uuid), 0L).coerceAtLeast(0L)
    }

    fun getDownload(uuid: UUID): Long {
        return preferences.getLong(downloadKey(uuid), 0L).coerceAtLeast(0L)
    }

    fun getUsed(uuid: UUID): Long {
        return getUpload(uuid) + getDownload(uuid)
    }

    fun add(uuid: UUID, uploadDelta: Long, downloadDelta: Long) {
        val up = uploadDelta.coerceAtLeast(0L)
        val down = downloadDelta.coerceAtLeast(0L)
        if (up == 0L && down == 0L) return

        synchronized(lock) {
            preferences.edit()
                .putLong(uploadKey(uuid), getUpload(uuid) + up)
                .putLong(downloadKey(uuid), getDownload(uuid) + down)
                .apply()
        }
    }

    fun reset(uuid: UUID) {
        synchronized(lock) {
            preferences.edit()
                .putLong(uploadKey(uuid), 0L)
                .putLong(downloadKey(uuid), 0L)
                .apply()
        }
    }

    fun clear(uuid: UUID) {
        synchronized(lock) {
            preferences.edit()
                .remove(uploadKey(uuid))
                .remove(downloadKey(uuid))
                .apply()
        }
    }

    /**
     * Older builds decoded packed core traffic without dividing the ×100 display
     * scale, so persisted counters are ~100× too large. Rewrite them once.
     */
    private fun migrateInflatedCountersIfNeeded() {
        synchronized(lock) {
            if (preferences.getBoolean(SCALE_MIGRATION_KEY, false)) {
                return
            }

            val editor = preferences.edit()
            for ((key, value) in preferences.all) {
                if (value !is Long) continue
                if (!key.startsWith(UPLOAD_PREFIX) && !key.startsWith(DOWNLOAD_PREFIX)) continue
                editor.putLong(key, (value / LEGACY_INFLATION_FACTOR).coerceAtLeast(0L))
            }
            editor.putBoolean(SCALE_MIGRATION_KEY, true)
            // Commit synchronously so concurrent service/app processes do not both rewrite.
            editor.commit()
        }
    }

    private fun uploadKey(uuid: UUID): String = UPLOAD_PREFIX + uuid

    private fun downloadKey(uuid: UUID): String = DOWNLOAD_PREFIX + uuid

    private companion object {
        const val UPLOAD_PREFIX = "local_sub_traffic_upload_"
        const val DOWNLOAD_PREFIX = "local_sub_traffic_download_"
        const val SCALE_MIGRATION_KEY = "local_sub_traffic_scale_fixed_v1"
        const val LEGACY_INFLATION_FACTOR = 100L

        private val lock = Any()
    }
}
