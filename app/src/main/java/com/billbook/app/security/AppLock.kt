package com.billbook.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock using the phone's own unlock: fingerprint or face, with the PIN / pattern / password
 * as fallback. Pocket Ledger keeps no separate PIN, so there is nothing of ours to forget or leak.
 */
object AppLock {
    /** Weak biometrics + screen lock is the combination every Android version (8+) supports. */
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Leaving the app for less than this (e.g. to take a photo or pick a file) doesn't lock it. */
    const val GRACE_MILLIS = 30_000L

    /** False when the phone has no screen lock set up. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint, face or screen lock")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }
}
