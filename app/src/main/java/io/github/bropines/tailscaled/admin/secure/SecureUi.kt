package io.github.bropines.tailscaled.admin.secure

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import io.github.bropines.tailscaled.ui.theme.findActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.fragment.app.FragmentActivity

/**
 * Keeps the window out of screenshots, screen recordings and the recents thumbnail while it is
 * in the composition. Counted, so two secret views on one screen do not clear each other's
 * flag. Dialogs and sheets are windows of their own: they take `SecureFlagPolicy.SecureOn`.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return
    DisposableEffect(context) {
        val activity = context.findActivity()
        if (activity != null) SecureWindowCount.acquire(activity)
        onDispose { if (activity != null) SecureWindowCount.release(activity) }
    }
}

private object SecureWindowCount {
    private val counts = java.util.WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity) = synchronized(counts) {
        val n = (counts[activity] ?: 0) + 1
        counts[activity] = n
        if (n == 1) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) = synchronized(counts) {
        val n = (counts[activity] ?: 1) - 1
        if (n <= 0) {
            counts.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else counts[activity] = n
    }
}

/** The FragmentActivity behind a Compose LocalContext, through the locale wrappers; the unlock prompt needs it. */
fun Context.findFragmentActivity(): FragmentActivity? = findActivity() as? FragmentActivity

object SensitiveClipboard {
    /**
     * Copies [text] marked sensitive: Android 13+ then shows it masked in its copy preview, and
     * keyboards with clipboard history are asked not to keep it. The extra is honoured from 13
     * and harmless before.
     */
    fun copy(context: Context, label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE
            else "android.content.extra.IS_SENSITIVE"
            putBoolean(key, true)
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
}

/**
 * A field for a secret: masked, no suggestions or autocorrect, with an eye to check what was
 * typed. Holds what is being entered, never a stored secret — the console does not read a
 * stored one back into a field.
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    showLabel: String,
    hideLabel: String,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        visualTransformation = if (shown) VisualTransformation.None else remember { PasswordVisualTransformation() },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (shown) KeyboardType.Ascii else KeyboardType.Password,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (shown) hideLabel else showLabel,
                )
            }
        },
        modifier = modifier,
    )
}
