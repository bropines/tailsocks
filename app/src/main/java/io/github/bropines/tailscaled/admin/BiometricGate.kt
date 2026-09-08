package io.github.bropines.tailscaled.admin

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The gate the Admin console stands behind, for one action elsewhere: the device
 * credential (biometric or lock screen) before something that changes the tailnet
 * through the Admin API. [onResult] gets true on success and false when the user
 * gives up; where no prompt can be shown at all it opens, as the console does.
 */
fun FragmentActivity.authenticateWithBiometrics(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
    val executor = ContextCompat.getMainExecutor(this)
    val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            onResult(true)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            android.util.Log.w("BiometricGate", "credential prompt ended with error $errorCode: $errString")
            onResult(false)
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()
    try {
        prompt.authenticate(info)
    } catch (e: Exception) {
        android.util.Log.w("BiometricGate", "credential prompt could not be shown, proceeding as the console does", e)
        onResult(true)
    }
}

/** The FragmentActivity behind a Compose LocalContext, through the locale wrappers. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
