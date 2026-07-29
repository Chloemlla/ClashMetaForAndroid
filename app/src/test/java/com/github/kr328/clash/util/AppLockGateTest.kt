package com.github.kr328.clash.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockGateTest {
    @Test
    fun requiresUnlock_disabledNeverRequires() {
        assertFalse(AppLockGate.requiresUnlock(enabled = false, lastUnlockedAt = 0L, now = 1_000L))
        assertFalse(
            AppLockGate.requiresUnlock(enabled = false, lastUnlockedAt = 500L, now = 999_999L)
        )
    }

    @Test
    fun requiresUnlock_coldStartWithNoPriorUnlockRequires() {
        assertTrue(AppLockGate.requiresUnlock(enabled = true, lastUnlockedAt = 0L, now = 1_000L))
    }

    @Test
    fun requiresUnlock_withinTimeoutDoesNotRequire() {
        val lastUnlockedAt = 10_000L
        val now = lastUnlockedAt + AppLockGate.DEFAULT_BACKGROUND_TIMEOUT_MS - 1
        assertFalse(AppLockGate.requiresUnlock(enabled = true, lastUnlockedAt = lastUnlockedAt, now = now))
    }

    @Test
    fun requiresUnlock_atExactTimeoutRequires() {
        val lastUnlockedAt = 10_000L
        val now = lastUnlockedAt + AppLockGate.DEFAULT_BACKGROUND_TIMEOUT_MS
        assertTrue(AppLockGate.requiresUnlock(enabled = true, lastUnlockedAt = lastUnlockedAt, now = now))
    }

    @Test
    fun requiresUnlock_afterTimeoutRequires() {
        val lastUnlockedAt = 10_000L
        val now = lastUnlockedAt + AppLockGate.DEFAULT_BACKGROUND_TIMEOUT_MS + 5_000L
        assertTrue(AppLockGate.requiresUnlock(enabled = true, lastUnlockedAt = lastUnlockedAt, now = now))
    }

    @Test
    fun requiresUnlock_clockRewoundRequires() {
        // now < lastUnlockedAt (e.g. device clock changed) must fail closed, not open.
        assertTrue(
            AppLockGate.requiresUnlock(enabled = true, lastUnlockedAt = 100_000L, now = 10L)
        )
    }

    @Test
    fun requiresUnlock_customTimeoutRespected() {
        val lastUnlockedAt = 0L
        val now = 5_000L
        assertFalse(
            AppLockGate.requiresUnlock(
                enabled = true,
                lastUnlockedAt = lastUnlockedAt,
                now = now,
                backgroundTimeoutMs = 10_000L,
            )
        )
        assertTrue(
            AppLockGate.requiresUnlock(
                enabled = true,
                lastUnlockedAt = lastUnlockedAt,
                now = now,
                backgroundTimeoutMs = 1_000L,
            )
        )
    }
}
