package com.github.kr328.clash.service.util

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pure subscription-expiry evaluation and once-per-bucket notify gate.
 *
 * [Profile.expire] is epoch millis (0 means no expiry metadata).
 */
object SubscriptionExpiry {
    val DEFAULT_THRESHOLD_MS: Long = TimeUnit.DAYS.toMillis(3)

    enum class Bucket {
        None,
        ExpiringSoon,
        Expired,
    }

    fun evaluate(
        expireMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    ): Bucket {
        if (expireMs <= 0L) return Bucket.None
        if (nowMs >= expireMs) return Bucket.Expired
        if (expireMs - nowMs <= thresholdMs) return Bucket.ExpiringSoon
        return Bucket.None
    }

    /**
     * Stable key so each profile notifies at most once per (bucket, expire value).
     * When the subscription's expire timestamp changes, a new key allows another notify.
     */
    fun notificationKey(uuid: UUID, bucket: Bucket, expireMs: Long): String? {
        if (bucket == Bucket.None || expireMs <= 0L) return null
        return "${uuid}|${bucket.name}|$expireMs"
    }

    fun shouldNotify(lastNotifiedKeys: Set<String>, key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        return key !in lastNotifiedKeys
    }

    fun markNotified(lastNotifiedKeys: Set<String>, key: String): Set<String> {
        if (key.isBlank()) return lastNotifiedKeys
        return lastNotifiedKeys + key
    }
}
