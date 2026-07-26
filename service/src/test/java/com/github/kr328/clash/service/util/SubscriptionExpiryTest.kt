package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class SubscriptionExpiryTest {
    private val dayMs = TimeUnit.DAYS.toMillis(1)
    private val threshold = SubscriptionExpiry.DEFAULT_THRESHOLD_MS

    @Test
    fun evaluate_skipsZeroExpire() {
        assertEquals(
            SubscriptionExpiry.Bucket.None,
            SubscriptionExpiry.evaluate(expireMs = 0L, nowMs = 1_000L),
        )
        assertEquals(
            SubscriptionExpiry.Bucket.None,
            SubscriptionExpiry.evaluate(expireMs = -1L, nowMs = 1_000L),
        )
    }

    @Test
    fun evaluate_expiredWhenNowPastExpire() {
        val expire = 10_000L
        assertEquals(
            SubscriptionExpiry.Bucket.Expired,
            SubscriptionExpiry.evaluate(expireMs = expire, nowMs = expire),
        )
        assertEquals(
            SubscriptionExpiry.Bucket.Expired,
            SubscriptionExpiry.evaluate(expireMs = expire, nowMs = expire + 1),
        )
    }

    @Test
    fun evaluate_expiringSoonWithinThreshold() {
        val now = 100_000L
        val expire = now + threshold
        assertEquals(
            SubscriptionExpiry.Bucket.ExpiringSoon,
            SubscriptionExpiry.evaluate(expireMs = expire, nowMs = now, thresholdMs = threshold),
        )
        assertEquals(
            SubscriptionExpiry.Bucket.ExpiringSoon,
            SubscriptionExpiry.evaluate(expireMs = now + dayMs, nowMs = now, thresholdMs = threshold),
        )
    }

    @Test
    fun evaluate_noneWhenOutsideThreshold() {
        val now = 100_000L
        val expire = now + threshold + 1
        assertEquals(
            SubscriptionExpiry.Bucket.None,
            SubscriptionExpiry.evaluate(expireMs = expire, nowMs = now, thresholdMs = threshold),
        )
    }

    @Test
    fun notificationKey_andOnceGate() {
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val expire = 1_700_000_000_000L

        assertNull(
            SubscriptionExpiry.notificationKey(uuid, SubscriptionExpiry.Bucket.None, expire),
        )

        val key = SubscriptionExpiry.notificationKey(
            uuid,
            SubscriptionExpiry.Bucket.ExpiringSoon,
            expire,
        )
        assertEquals("$uuid|ExpiringSoon|$expire", key)

        val empty = emptySet<String>()
        assertTrue(SubscriptionExpiry.shouldNotify(empty, key))
        assertFalse(SubscriptionExpiry.shouldNotify(empty, null))

        val marked = SubscriptionExpiry.markNotified(empty, key!!)
        assertFalse(SubscriptionExpiry.shouldNotify(marked, key))

        // New expire value opens a new notify bucket.
        val nextKey = SubscriptionExpiry.notificationKey(
            uuid,
            SubscriptionExpiry.Bucket.ExpiringSoon,
            expire + 1,
        )
        assertTrue(SubscriptionExpiry.shouldNotify(marked, nextKey))
    }
}
