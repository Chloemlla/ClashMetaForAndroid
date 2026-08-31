package com.github.kr328.clash.util

/**
 * Pure helpers for optional app-lock gating.
 *
 * Default background grace is 60s. No secrets are stored here —
 * only whether lock is enabled and when the last successful unlock happened.
 */
object AppLockGate {
    const val DEFAULT_BACKGROUND_TIMEOUT_MS: Long = 60_000L

    /**
     * @return true if the UI must present biometric / device-credential before content.
     */
    fun requiresUnlock(
        enabled: Boolean,
        lastUnlockedAt: Long,
        now: Long,
        backgroundTimeoutMs: Long = DEFAULT_BACKGROUND_TIMEOUT_MS,
    ): Boolean {
        if (!enabled) return false
        if (lastUnlockedAt <= 0L) return true
        if (now < lastUnlockedAt) return true
        return now - lastUnlockedAt >= backgroundTimeoutMs
    }

    /**
     * Return-from-background gate. Unlike [requiresUnlock] (which measures from the last unlock),
     * this measures the *duration of the last background trip*, captured once when the app returns
     * to the foreground. Navigating between activities while the app stays foreground never
     * re-prompts, and a long foreground session does not re-arm the gate.
     *
     * [backgroundDurationMs] is 0 when the app has never backgrounded this process (the cold-start
     * gate handles that separately), so 0 means "no recheck needed".
     */
    fun requiresUnlockOnResume(
        enabled: Boolean,
        backgroundDurationMs: Long,
        backgroundTimeoutMs: Long = DEFAULT_BACKGROUND_TIMEOUT_MS,
    ): Boolean {
        if (!enabled) return false
        if (backgroundDurationMs < 0L) return true
        if (backgroundDurationMs == 0L) return false
        return backgroundDurationMs >= backgroundTimeoutMs
    }
}
