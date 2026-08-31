package com.github.kr328.clash.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Biometric / device-credential gate for the optional app lock.
 *
 * Uses AndroidX BiometricPrompt with strong biometric + DEVICE_CREDENTIAL.
 * Does not implement a custom PIN database (device credential is the fallback).
 */
object AppLockController {
    private const val AUTHENTICATORS =
        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL

    /**
     * In-process "the user has unlocked this session" flag. Cleared whenever the app goes to the
     * background (see [ApplicationObserver.markBackgrounded]), so returning after the timeout
     * re-verifies. While the app stays foreground, navigating between activities never re-prompts.
     */
    @Volatile
    private var unlockedInProcess = false

    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        val result = manager.canAuthenticate(AUTHENTICATORS)
        return result == BiometricManager.BIOMETRIC_SUCCESS ||
            result == BiometricManager.BIOMETRIC_STATUS_UNKNOWN
    }

    /**
     * Present the system unlock UI. On success, updates [UiStore.lastUnlockedAt].
     *
     * @return true if unlocked (or lock is disabled / not required by caller), false on cancel/error.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        uiStore: UiStore,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    uiStore.lastUnlockedAt = System.currentTimeMillis()
                    unlockedInProcess = true
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    cont.resume(resolveError(activity, uiStore, "$errorCode $errString"))
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open for another attempt; do not resume yet.
                }
            },
        )

        cont.invokeOnCancellation {
            runCatching { prompt.cancelAuthentication() }
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        runCatching {
            prompt.authenticate(info)
        }.onFailure {
            if (cont.isActive) cont.resume(resolveError(activity, uiStore, "$it"))
        }
    }

    /**
     * Decide whether a prompt error means deny or means the gate is unsatisfiable.
     *
     * Callers finish() the Activity on false, so answering false to *every* error code bricks the
     * app when the device has no enrolled biometric and no device credential: there is then no way
     * to ever pass the gate and the only recovery is clearing app data. A gate that cannot be
     * satisfied protects nothing, so turn it off and let the user in instead.
     */
    private fun resolveError(
        activity: FragmentActivity,
        uiStore: UiStore,
        cause: String,
    ): Boolean {
        if (canAuthenticate(activity)) {
            return false
        }

        Log.w("App lock unsatisfiable on this device, disabling it: $cause")

        uiStore.appLockEnabled = false
        uiStore.lastUnlockedAt = System.currentTimeMillis()
        unlockedInProcess = true

        return true
    }

    fun markUnlocked(uiStore: UiStore, now: Long = System.currentTimeMillis()) {
        uiStore.lastUnlockedAt = now
        unlockedInProcess = true
    }

    /**
     * Cold-start gate: requires unlock whenever the lock is enabled and this process has not
     * already authenticated. A cleared unlock timestamp (the settings screen zeroes it when the
     * lock is toggled off) forces a fresh authentication even if the in-process flag survived.
     */
    fun isUnlockRequired(uiStore: UiStore): Boolean {
        if (!uiStore.appLockEnabled) return false
        if (uiStore.lastUnlockedAt <= 0L) {
            unlockedInProcess = false
            return true
        }
        return !unlockedInProcess
    }

    /**
     * Return-from-background gate: requires a fresh authentication only when the app actually went
     * to the background and the trip lasted past [AppLockGate.DEFAULT_BACKGROUND_TIMEOUT_MS].
     * While the app stays foreground (in-process navigation) this stays false.
     */
    fun isRecheckRequiredOnResume(
        uiStore: UiStore,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!uiStore.appLockEnabled) return false
        if (unlockedInProcess) return false
        return AppLockGate.requiresUnlockOnResume(
            enabled = true,
            backgroundDurationMs = ApplicationObserver.backgroundReturnMs,
        )
    }

    /** Called by [ApplicationObserver] when the app leaves the foreground; drops the session flag. */
    fun onAppBackgrounded() {
        unlockedInProcess = false
    }
}
