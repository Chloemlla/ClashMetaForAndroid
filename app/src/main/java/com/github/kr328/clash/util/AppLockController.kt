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

        return true
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
