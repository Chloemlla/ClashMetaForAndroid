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
        val lastUnlockedAt = 1_000L
        val now = 6_000L
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

    // B-72: the resume gate measures the duration of the last background trip (captured once on
    // return), so foreground navigation between activities never re-prompts.
    @Test
    fun requiresUnlockOnResume_neverBackgroundedDoesNotRecheck() {
        assertFalse(AppLockGate.requiresUnlockOnResume(enabled = true, backgroundDurationMs = 0L))
    }

    @Test
    fun requiresUnlockOnResume_disabledNeverRechecks() {
        assertFalse(
            AppLockGate.requiresUnlockOnResume(
                enabled = false,
                backgroundDurationMs = 999_999L,
            )
        )
    }

    @Test
    fun requiresUnlockOnResume_withinTimeoutDoesNotRecheck() {
        assertFalse(
            AppLockGate.requiresUnlockOnResume(
                enabled = true,
                backgroundDurationMs = AppLockGate.DEFAULT_BACKGROUND_TIMEOUT_MS - 1,
            )
        )
    }

    @Test
    fun requiresUnlockOnResume_afterTimeoutRechecks() {
        assertTrue(
            AppLockGate.requiresUnlockOnResume(
                enabled = true,
                backgroundDurationMs = AppLockGate.DEFAULT_BACKGROUND_TIMEOUT_MS + 5_000L,
            )
        )
    }

    @Test
    fun requiresUnlockOnResume_clockRewoundFailsClosed() {
        // A negative duration (device clock moved backwards during background) must fail closed.
        assertTrue(AppLockGate.requiresUnlockOnResume(enabled = true, backgroundDurationMs = -10_000L))
    }
}
