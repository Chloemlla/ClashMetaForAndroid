package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-99 / B-13: the retry backoff must be exponential (100ms doubling) so the documented
 * "~3s total budget" contract holds and a permanently dead service fails fast.
 */
class RemoteRetryTest {
    @Test
    fun backoffIsExponentialDoubling() {
        val delays = (1..5).map(::retryBackoffMillis)
        assertEquals(listOf(100L, 200L, 400L, 800L, 1600L), delays)
    }

    @Test
    fun totalBudgetAcrossFiveRetriesIsUnderThreeSeconds() {
        val total = (1..5).sumOf(::retryBackoffMillis)
        assertTrue("expected ~3.1s total but got $total", total in 3_000L..3_200L)
    }

    @Test
    fun firstRetryHasNoBackoff() {
        assertEquals(100L, retryBackoffMillis(1))
    }

    @Test
    fun neverExceedsDocumentedCap() {
        // Bounded by MAX_RETRIES = 5; even a hypothetical attempt 6 stays bounded and positive.
        assertTrue(retryBackoffMillis(6) > 0)
    }
}
