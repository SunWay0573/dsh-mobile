package io.github.sunway0573.dshmobile

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Whether this device can prompt for a biometric at all.
 *
 * `BIOMETRIC_WEAK` rather than `BIOMETRIC_STRONG` on purpose: this gates access
 * to an app that holds a session cookie, and a device with only a weaker sensor
 * is still far better protected than one with no prompt. Demanding a strong
 * sensor would lock those users out of the protection entirely, which is a worse
 * outcome than accepting a class-2 biometric.
 *
 * @param context any context; the manager does not retain it.
 * @returns true when a prompt could succeed.
 */
fun canAuthenticate(context: Context): Boolean {
    val manager = BiometricManager.from(context)
    return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Hold the UI behind a biometric prompt.
 *
 * ## Why this exists
 *
 * The app is the key to a machine that can run arbitrary code, and it keeps a
 * 30-day session cookie. A phone handed over unlocked, or left on a table, is
 * then equivalent to handing over the computer. The host's own auth cannot help
 * — it already trusts this device.
 *
 * ## Why there is no fallback to a PIN in this app
 *
 * The device credential prompt is available and could be offered, but the
 * session cookie is the thing being protected, and the device credential is
 * what unlocks the phone in the first place. Offering it would mean the gate
 * adds nothing beyond the lock screen the user already has, while looking like
 * it adds something.
 *
 * @param activity the host activity, which `BiometricPrompt` requires.
 * @param enabled when false the content is shown immediately and no prompt runs.
 * @param promptOnStart whether to raise the prompt as soon as the gate appears,
 *   rather than waiting for a tap. Off for the first frame after a config change.
 * @param content shown once unlocked.
 */
@Composable
fun BiometricGate(
    activity: FragmentActivity,
    enabled: Boolean,
    promptOnStart: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    var unlocked by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun prompt() {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    message = null
                }

                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    // A user-initiated cancel is not an error worth shouting
                    // about; anything else is.
                    message = if (code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_CANCELED
                    ) {
                        "Unlock to continue."
                    } else {
                        errString.toString()
                    }
                }

                override fun onAuthenticationFailed() {
                    // A single non-matching finger. The prompt stays up and the
                    // system handles retries, so saying nothing is correct.
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock DSH Mobile")
                .setSubtitle("This app can control a computer.")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build(),
        )
    }

    LaunchedEffect(enabled, promptOnStart) {
        if (promptOnStart) prompt()
    }

    if (unlocked) {
        content()
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("DSH Mobile is locked", style = MaterialTheme.typography.titleLarge)
            Text(
                message ?: "Unlock to reach your machine.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
            Button(onClick = { prompt() }) { Text("Unlock") }
        }
    }
}
