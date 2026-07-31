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
}
