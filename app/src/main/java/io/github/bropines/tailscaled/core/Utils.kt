package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result?.let { safeFileName(it) }
}

/**
 * Reduces a display name from another app's content provider to a plain file
 * name. Callers build `File(outDir, name)` from it, and a provider that
 * answers DISPLAY_NAME with `../../shared_prefs/x.xml` could otherwise write
 * anywhere inside the app's data directory.
 */
fun safeFileName(raw: String): String? {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        .replace('\u0000', '_')
    return base.takeUnless { it.isEmpty() || it == "." || it == ".." }
}

/**
 * Opens the OEM "autostart" screen — the permission that decides whether the app
 * may be started in the background at all on MIUI/HyperOS and relatives, and the
 * reason an update or a task killer can leave the connection down with nothing
 * able to bring it back.
 *
 * The component only exists on those skins, so the app info page is the fallback
 * (its "battery"/"permissions" entries are where the equivalent switch lives on
 * other ROMs), and a device with neither only gets a toast.
 */
fun openAutostartSettings(context: Context) {
    val miui = Intent()
        .setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        if (context.packageManager.resolveActivity(miui, 0) != null) {
            context.startActivity(miui)
            return
        }
    } catch (e: Exception) {
        // Present but not startable from here; fall through to the app info page.
    }
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.perm_open_failed), Toast.LENGTH_LONG).show()
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

object BackupCrypto {
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    fun encrypt(data: ByteArray, password: CharArray): ByteArray {
        // A password lost on the way here (see BackupRestore.PasswordHold) must not
        // turn into an archive sealed with nothing that the user's password cannot open.
        require(password.isNotEmpty()) { "A backup needs a password" }
        val salt = ByteArray(SALT_LENGTH)
        java.security.SecureRandom().nextBytes(salt)

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        val secretKey = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_LENGTH)
        java.security.SecureRandom().nextBytes(iv)
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val ciphertext = cipher.doFinal(data)

        val result = ByteArray(SALT_LENGTH + IV_LENGTH + ciphertext.size)
        System.arraycopy(salt, 0, result, 0, SALT_LENGTH)
        System.arraycopy(iv, 0, result, SALT_LENGTH, IV_LENGTH)
        System.arraycopy(ciphertext, 0, result, SALT_LENGTH + IV_LENGTH, ciphertext.size)
        return result
    }

    fun decrypt(encryptedData: ByteArray, password: CharArray): ByteArray {
        if (encryptedData.size < SALT_LENGTH + IV_LENGTH) {
            throw IllegalArgumentException("Data too short")
        }

        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        System.arraycopy(encryptedData, 0, salt, 0, SALT_LENGTH)
        System.arraycopy(encryptedData, SALT_LENGTH, iv, 0, IV_LENGTH)

        val ciphertextLength = encryptedData.size - SALT_LENGTH - IV_LENGTH
        val ciphertext = ByteArray(ciphertextLength)
        System.arraycopy(encryptedData, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertextLength)

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        val secretKey = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(ciphertext)
    }
}

/**
 * Mirrors the in-app language choice into the platform's per-app locale.
 *
 * Called from every Activity's attachBaseContext, so it must be cheap and must
 * not loop: setting the locale recreates the Activity, which calls back in
 * here, so it only writes when the platform disagrees with the preference.
 */
@Volatile private var frameworkLocaleTried = false

private fun syncFrameworkLocale(context: Context, lang: String) {
    // ONCE per process. Some ROMs accept the call and store nothing, and this
    // runs from attachBaseContext: retrying would set the locale, get recreated,
    // set it again — the app flickering in a recreate loop, which is exactly
    // what happened on HyperOS.
    if (frameworkLocaleTried) return
    frameworkLocaleTried = true
    try {
        // Straight to LocaleManager (AppLocale): AppCompatDelegate's calls reach the
        // platform only through an AppCompatActivity, and there is none in this app.
        AppLocale.toPlatform(context, lang)
    } catch (e: Exception) {
        // A locale is not worth a crash at startup.
        android.util.Log.w("Utils", "Could not apply the app locale: ${e.message}")
    }
}

/**
 * Wraps an Activity's base context so the app renders in the language chosen in
 * Settings (`app_locale`: "sys" or a tag of AppLanguages).
 *
 * WHY EVERY DIALOG HOISTS ITS STRINGS — do not "clean this up".
 *
 * This wrapper fixes the Activity's own window only. A Compose AlertDialog,
 * ModalBottomSheet, Dialog or DropdownMenu is a separate window with its own
 * AbstractComposeView, and that view re-provides LocalContext/LocalResources
 * from the window's context. On HyperOS (Android 16) that window does not
 * inherit the Activity's overridden configuration, so a `stringResource(...)`
 * called *inside* a dialog resolves against the SYSTEM language: the screen
 * renders in one language and its dialogs in the other. Confirmed on device,
 * both ways round.
 *
 * The platform per-app locale API would fix it at the source, and
 * syncFrameworkLocale() above asks for it once per process — but that ROM
 * accepts the call and stores nothing (`cmd locale get-app-locales` stays
 * empty), so it cannot be relied on.
 *
 * So every dialog resolves its strings in the PARENT composition, which uses
 * the Activity's wrapped context and therefore the right language, and passes
 * plain Strings in. Two shapes are used, both fine:
 *   - `val strFoo = stringResource(R.string.foo)` declared immediately before
 *     the dialog call, referenced inside it;
 *   - `val ctx = LocalContext.current` captured in the parent, then
 *     `ctx.getString(R.string.foo, arg)` inside the dialog — used where the
 *     format argument only exists inside the dialog's own content.
 *
 * Moving a `stringResource(...)` back inside a dialog slot reintroduces the bug.
 */
fun wrapContextWithLocale(context: Context): Context {
    // A language picked in Android 13+'s own settings for the app is adopted here.
    val lang = AppLocale.current(context)
    // Hand the choice to the framework as well, once per process. The wrapper
    // below only re-configures the Activity's own context; a dialog opens its
    // own window, which the framework builds from the app's configuration, so
    // wrapping alone leaves every dialog in the system language. Since Android
    // 13 the per-app locale is a platform feature — set it there and every
    // window agrees, this one included.
    syncFrameworkLocale(context, lang)
    // The wrapper stays on every version: the platform call above is advisory —
    // a ROM may accept it and store nothing — and without the wrapper the app
    // would then ignore the setting entirely.
    // The interface scale rides on the same configuration: a density of its own,
    // so every dp and sp in the activity — and the sheets and dialogs it opens,
    // whose windows take its resources — grows or shrinks together. The base is
    // the context handed in, which carries the system's density (the user's
    // display size included), never a scaled one.
    val scale = GlobalSettings.getUiScale(context)
    if (lang == "sys" && scale == 100) return context
    val config = android.content.res.Configuration(context.resources.configuration)
    if (lang != "sys") {
        val locale = java.util.Locale.forLanguageTag(lang)
        java.util.Locale.setDefault(locale)
        config.setLocale(locale)
    }
    if (scale != 100) config.densityDpi = scaledDensityDpi(config.densityDpi, scale)
    return context.createConfigurationContext(config)
}

/** [systemDpi] at [percent] of its size: the density an activity gets for the interface scale. */
fun scaledDensityDpi(systemDpi: Int, percent: Int): Int = (systemDpi * percent / 100f).roundToInt()

@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium
) {
    // It stands in settings pages: on a television a row that opens the field (ui/PageTextField.kt).
    if (isTv) {
        TvFieldRow(
            value = value,
            onValueChange = onValueChange,
            label = label.orEmpty(),
            modifier = modifier.fillMaxWidth(),
            placeholder = placeholder,
            supportingText = supportingText,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = if (label != null) { { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) } } else null,
        placeholder = if (placeholder != null) { { Text(placeholder, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) } } else null,
        supportingText = if (supportingText != null) { { Text(supportingText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) } } else null,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = true,
        maxLines = 1,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    // A field at the head of a list is the first thing a remote crosses: on a television it
    // is a button that opens the field (TvQueryField), wherever a screen draws this.
    if (isTv) {
        TvQueryField(value = value, onValueChange = onValueChange, placeholder = placeholderText, modifier = modifier, onDone = onSearch)
        return
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        ),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = {
                onSearch?.invoke()
                focusManager.clearFocus()
            },
            onDone = {
                focusManager.clearFocus()
            }
        ),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                placeholder = {
                    Text(
                        text = placeholderText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = if (value.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onValueChange("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    )
                },
                contentPadding = PaddingValues(start = 12.dp, top = 0.dp, end = 8.dp, bottom = 0.dp)
            )
        }
    )
}

/**
 * Motion spec for the in-app predictive-back transition. The numbers are the platform's own:
 * the outgoing window shrinks to 90 %, slides `width / 20` away from the swiped edge, and its
 * corners round off to the device corner radius, all driven through the system back easing.
 */
private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
/** How long a committed back takes to finish its move and fade: long enough to be seen as one motion. */
private const val BACK_EXIT_MS = 200

/**
 * Whether the platform draws the predictive back animation between Activities: Android 14
 * and later, unless the global switch says no — HyperOS ships it off, Android 14 keeps it in
 * the developer options (absent, it is on).
 */
fun systemAnimatesBack(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val flag = runCatching { Settings.Global.getString(context.contentResolver, "enable_back_animation") }.getOrNull()
    return flag != "0"
}

/**
 * [finish] (the screen's own back, which ends in finish()) with no window animation: the
 * page has already slid away and faded here, and a window fade on top of that showed it again
 * over the screen beneath.
 */
private fun finishSoftly(activity: android.app.Activity, finish: () -> Unit) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        activity.overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        finish()
    } else {
        finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
private const val BACK_MIN_SCALE = 0.9f
private const val BACK_MAX_X_SHIFT_FRACTION = 1f / 20f
private val BackMaxCornerRadius = 28.dp
private val BackMaxShadow = 8.dp
// A cancelled back settles slowly enough to be followed, with no bounce.
private val BackSettleSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * True when the user (or a test harness) has turned system animations off, which is this
 * platform's equivalent of `prefers-reduced-motion`. The gesture keeps working; only the
 * decorative transform is skipped.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f) == 0f
    }
}

/**
 * Wraps a full-screen destination and deals with the predictive back gesture. There are two
 * very different modes and [popsInAppState] picks between them:
 *
 * * **`false` — back merely closes the Activity.** No back callback is installed at all, so the
 *   platform keeps the gesture and plays its own cross-activity animation: the *real* previous
 *   Activity is revealed behind the shrinking window and the system finishes this one. Anything
 *   the screen has to persist before it disappears must happen in a lifecycle hook, because
 *   [onBack] is no longer on the gesture path (it is still used by the toolbar arrow).
 * * **`true` (default) — back pops state inside this screen.** The gesture is intercepted and the
 *   transition is drawn here, over a real destination: [previousContent] when the caller can
 *   render the screen underneath, otherwise a calm [targetIcon] + [targetTitle] hint so the user
 *   still sees where back is going. A cancelled gesture springs back to rest instead of snapping.
 */
@Composable
fun PredictiveBackContainer(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    targetTitle: String? = null,
    targetIcon: ImageVector? = null,
    previousContent: (@Composable () -> Unit)? = null,
    popsInAppState: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Leaving the Activity is the system's to animate — where it does. HyperOS ships with that
    // animation off (enable_back_animation = 0, seen on the POCO), and Android 14 has it behind a
    // developer option: there back closed the screen with no motion at all, so this draws it.
    val systemAnimates = remember(context) { systemAnimatesBack(context) }
    if (onBack == null || (!popsInAppState && systemAnimates)) {
        // Nothing to pop: stay out of the way so the system can animate Activity -> Activity.
        Box(modifier, propagateMinConstraints = true) { content() }
        return
    }
    val leavesActivity = !popsInAppState
    val underlay = remember(context) { BackUnderlay.showsThrough(context.findActivity()) }

    // The gesture coroutine is cancelled when the user changes their mind, so the settle
    // animation has to run somewhere that survives that.
    val settleScope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    // After the finger lets go: the page finishes its move and fades, instead of snapping away.
    val exit = remember { Animatable(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    // Settings → Appearance → Gestures → "Back with a preview" off is the same as no motion:
    // the gesture still goes back, only the page no longer shrinks under the finger.
    val previewOff = remember(context) { !Gestures.predictiveBack(context) }
    val reducedMotion = rememberReducedMotion() || previewOff

    PredictiveBackHandler(enabled = true) { backEvents ->
        try {
            backEvents.collect { backEvent ->
                swipeEdge = backEvent.swipeEdge
                progress.snapTo(backEvent.progress)
            }
            if (!reducedMotion) {
                // The page carries on the way the finger sent it — off towards the far edge,
                // fading — so letting go is the end of one motion, not a jump. (Recorded frame by
                // frame on the POCO: resetting here first put the page back at full size for a
                // frame, then the window fade showed it over the screen beneath.)
                coroutineScope {
                    launch { progress.animateTo(1f, tween(BACK_EXIT_MS, easing = FastOutSlowInEasing)) }
                    launch { exit.animateTo(1f, tween(BACK_EXIT_MS, easing = FastOutSlowInEasing)) }
                }
            }
            // The theme wraps the context (the app's own language), so the Activity is looked up
            // through the wrappers; without one, back still happens, only without the soft finish.
            val activity = if (leavesActivity) context.findActivity() else null
            if (activity != null) {
                // Gone already: nothing to reset, and no window animation on top of the page's own.
                finishSoftly(activity, onBack)
            } else {
                onBack()
                settleScope.launch { progress.snapTo(0f); exit.snapTo(0f) }
            }
        } catch (cancelled: CancellationException) {
            settleScope.launch { progress.animateTo(0f, BackSettleSpec) }
        }
    }

    // Read as a lambda: the value is sampled inside the graphics layers, so a moving finger
    // re-runs the layer blocks instead of recomposing the whole screen.
    val easedProgress: () -> Float = {
        if (reducedMotion) 0f else BackGestureEasing.transform(progress.value.coerceIn(0f, 1f))
    }
    val gestureInFlight by remember { derivedStateOf { progress.value > 0.001f } }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Leaving over a translucent window the ground is the real screen beneath (BackUnderlay);
            // otherwise the theme's background, as the page is drawn over.
            .then(if (leavesActivity && underlay) Modifier else Modifier.background(MaterialTheme.colorScheme.background))
    ) {
        if (gestureInFlight && !reducedMotion && leavesActivity && underlay) {
            // The real screen beneath, dimmed a little as the page comes off it, so the two read as
            // two layers: both are mostly black, and without it the page looked cut, not lifted.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = easedProgress() * (1f - exit.value) }
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
            )
        }
        if (gestureInFlight && !reducedMotion) {
            if (previousContent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = easedProgress()
                            val scale = 0.92f + (0.08f * p)
                            scaleX = scale
                            scaleY = scale
                            alpha = (p * 2f).coerceIn(0f, 1f)
                        }
                ) {
                    previousContent()
                }
            } else if (targetIcon != null || !targetTitle.isNullOrBlank()) {
                PredictiveBackDestinationHint(targetTitle, targetIcon, easedProgress)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = easedProgress()
                    val scale = 1f - ((1f - BACK_MIN_SCALE) * p)
                    scaleX = scale
                    scaleY = scale
                    val awayFromEdge = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                    // Let go, the page keeps going: a third of the width more as it fades out.
                    // Let go, the page slides the rest of the way off, opaque: a fade there showed it
                    // half-transparent over the screen beneath for several frames.
                    translationX = awayFromEdge * size.width * (BACK_MAX_X_SHIFT_FRACTION * p + exit.value)
                    // Rounded from the first moments of the gesture, so the page reads as a card.
                    shape = RoundedCornerShape(BackMaxCornerRadius * (p * 4f).coerceAtMost(1f))
                    clip = true
                    shadowElevation = BackMaxShadow.toPx() * p
                }
        ) {
            content()
        }
    }
}

/**
 * What sits behind the shrinking screen when the caller cannot hand us the previous screen:
 * the destination's icon and name, centred and in theme colours, fading in with the gesture.
 */
@Composable
private fun PredictiveBackDestinationHint(
    title: String?,
    icon: ImageVector?,
    progress: () -> Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress()
                alpha = (p * 2.5f).coerceIn(0f, 1f)
                val scale = 0.9f + (0.1f * p)
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(12.dp))
            }
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

data class SegmentedChipItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val containerColor: androidx.compose.ui.graphics.Color? = null,
    val contentColor: androidx.compose.ui.graphics.Color? = null,
    /** How many things the chip's filter holds, drawn after the title in a lighter face. */
    val count: Int? = null
)

/** A chip's icon, title and count, the same in both chip rows. */
@Composable
private fun SegmentedChipLabel(item: SegmentedChipItem, isSelected: Boolean, contentColor: androidx.compose.ui.graphics.Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.icon != null) {
            androidx.compose.material3.Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
        }
        Text(
            text = item.title,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
            color = contentColor
        )
        if (item.count != null) {
            Text(
                text = item.count.toString(),
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * The ring of a chip the remote is on. The chips draw no ripple — the sliding
 * pill is their only state — so without it a television would show nothing of
 * where the remote is, and the chip is picked with OK, not by moving onto it.
 * Set in by the pill's own margin, so that it follows the pill's outline.
 */
@Composable
private fun Modifier.tvChipFocusRing(source: MutableInteractionSource): Modifier =
    if (isTv) padding(2.dp).tvFocusRing(source, MaterialTheme.shapes.medium) else this

@JvmName("SlidingSegmentedChipsOptions")
@Composable
fun SlidingSegmentedChips(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    positionOffset: Float? = null,
    height: Dp = 40.dp,
    recreates: Boolean = false
) {
    SlidingSegmentedChips(
        items = options.map { SegmentedChipItem(it) },
        selectedIndex = selectedIndex,
        onOptionSelected = onOptionSelected,
        modifier = modifier,
        positionOffset = positionOffset,
        height = height,
        recreates = recreates
    )
}

@Composable
fun SlidingSegmentedChips(
    items: List<SegmentedChipItem>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    positionOffset: Float? = null,
    height: Dp = 40.dp,
    recreates: Boolean = false
) {
    // A pick that recreates the screen (the language, the scale) would leave a remote on the
    // first chip when the screen comes back, one OK from undoing the pick. The pick is kept
    // across the recreation, and the chip that was picked takes the remote back once.
    var pickedWithRemote by rememberSaveable { mutableStateOf(false) }
    val tv = isTv
    val animPosition by animateFloatAsState(
        targetValue = positionOffset ?: selectedIndex.toFloat(),
        animationSpec = tween(durationMillis = 60),
        label = "slidingPosition"
    )

    val count = items.size.coerceAtLeast(1)
    val activeIndex = animPosition.roundToInt().coerceIn(0, count - 1)
    val selectedItem = items.getOrNull(activeIndex)
    val defaultContainer = MaterialTheme.colorScheme.primaryContainer
    val targetContainer = selectedItem?.containerColor ?: defaultContainer

    val activeBgColor by androidx.compose.animation.animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 60),
        label = "activePillBg"
    )

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        val totalWidth = maxWidth
        val itemWidth = totalWidth / count

        // Smooth Continuous Drag-Bound / Animated Active Pill
        val offsetX = itemWidth * animPosition.coerceIn(0f, (count - 1).toFloat())
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(activeBgColor)
        )

        // Labels & Icons overlay
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val isSelected = (animPosition - index).let { Math.abs(it) < 0.5f }
                val defaultContent = MaterialTheme.colorScheme.onPrimaryContainer
                val targetContent = if (isSelected) (item.contentColor ?: defaultContent) else MaterialTheme.colorScheme.onSurfaceVariant
                val contentColor by androidx.compose.animation.animateColorAsState(
                    targetValue = targetContent,
                    animationSpec = tween(durationMillis = 60),
                    label = "chipContentColor"
                )

                val source = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (tv && pickedWithRemote && index == selectedIndex) Modifier.tvInitialFocus() else Modifier)
                        .onFocusChanged { if (it.isFocused) pickedWithRemote = false }
                        .tvChipFocusRing(source)
                        .selectable(
                            selected = isSelected,
                            interactionSource = source,
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Tab
                        ) {
                            if (recreates && tv) pickedWithRemote = true
                            onOptionSelected(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    SegmentedChipLabel(item, isSelected, contentColor)
                }
            }
        }
    }
}

@JvmName("ScrollableSlidingSegmentedChipsOptions")
@Composable
fun ScrollableSlidingSegmentedChips(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector?>? = null,
    height: Dp = 40.dp
) {
    val items = options.mapIndexed { index, title ->
        SegmentedChipItem(title, icons?.getOrNull(index))
    }
    ScrollableSlidingSegmentedChips(
        items = items,
        selectedIndex = selectedIndex,
        onOptionSelected = onOptionSelected,
        modifier = modifier,
        height = height
    )
}

@Composable
fun ScrollableSlidingSegmentedChips(
    items: List<SegmentedChipItem>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp
) {
    // Opened on the selected chip, so a screen that starts on a later one does not draw the
    // row from its start and then slide across.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex)
    }

    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = modifier
            .height(height)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items.size) { index ->
            val item = items[index]
            val isSelected = selectedIndex == index
            val defaultContainer = MaterialTheme.colorScheme.primaryContainer
            val defaultContent = MaterialTheme.colorScheme.onPrimaryContainer
            val itemContainer = item.containerColor ?: defaultContainer
            val itemContent = item.contentColor ?: defaultContent

            val bgColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) itemContainer else androidx.compose.ui.graphics.Color.Transparent,
                animationSpec = tween(durationMillis = 60),
                label = "chipBgColor"
            )
            val contentColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) itemContent else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(durationMillis = 60),
                label = "chipContentColor"
            )

            val source = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .tvFocusRing(source, MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .background(bgColor)
                    .selectable(
                        selected = isSelected,
                        interactionSource = source,
                        indication = null,
                        role = androidx.compose.ui.semantics.Role.Tab
                    ) {
                        onOptionSelected(index)
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                SegmentedChipLabel(item, isSelected, contentColor)
            }
        }
    }
}


