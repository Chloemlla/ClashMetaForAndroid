package com.github.kr328.clash.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(false)
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
            if (cont.isActive) cont.resume(false)
        }
    }

    fun markUnlocked(uiStore: UiStore, now: Long = System.currentTimeMillis()) {
        uiStore.lastUnlockedAt = now
    }

    fun isUnlockRequired(uiStore: UiStore, now: Long = System.currentTimeMillis()): Boolean {
        return AppLockGate.requiresUnlock(
            enabled = uiStore.appLockEnabled,
            lastUnlockedAt = uiStore.lastUnlockedAt,
            now = now,
        )
    }
}
