package com.github.kr328.clash.service.store

import java.util.UUID

/**
 * Profile-scoped subscription traffic billing preference with a lazy legacy fallback.
 */
internal class SubscriptionTrafficBillingPreference(
    private val contains: (String) -> Boolean,
    private val readBoolean: (String, Boolean) -> Boolean,
    private val writeBoolean: (String, Boolean) -> Unit,
    private val remove: (String) -> Unit,
) {
    fun get(uuid: UUID, legacyDefault: Boolean): Boolean {
        return getIfPresent(uuid, legacyDefault)
            ?: legacyDefault.also { writeBoolean(key(uuid), it) }
    }

    fun getIfPresent(uuid: UUID, legacyDefault: Boolean): Boolean? {
        val key = key(uuid)
        if (!contains(key)) return null

        return try {
            readBoolean(key, legacyDefault)
        } catch (_: ClassCastException) {
            writeBoolean(key, legacyDefault)
            legacyDefault
        }
    }

    fun set(uuid: UUID, enabled: Boolean) {
        writeBoolean(key(uuid), enabled)
    }

    fun clear(uuid: UUID) {
        remove(key(uuid))
    }

    private fun key(uuid: UUID): String = KEY_PREFIX + uuid

    private companion object {
        const val KEY_PREFIX = "local_subscription_traffic_profile_"
    }
}
