package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.core.Availability
import io.github.bropines.tailscaled.core.Capabilities
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DeviceProfile
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.GlobalSettings

/*
 * The device class (core/DeviceProfile.kt) as the interface sees it. It is
 * decided once, by TailSocksTheme at the root of every screen, and read from
 * LocalDeviceClass everywhere below — sheets and dialogs included, which are
 * windows of their own but inherit the locals.
 *
 * A screen does not branch on it. Layout asks WindowLayout (ui/Adaptive.kt),
 * which knows a television is not a phone; what to show asks a Feature; and the
 * modifiers in ui/Focus.kt do nothing off a television. `isTv` is for the
 * shared components those are built from.
 */

/** What the interface is drawn for. Handheld wherever nobody provides it: a composition without the theme is today's. */
val LocalDeviceClass = staticCompositionLocalOf { DeviceClass.HANDHELD }

/** The renderer is no television, so a preview hands the class in through this; the app never provides it. */
val LocalPreviewDeviceClass = staticCompositionLocalOf<DeviceClass?> { null }

/**
 * What the system can do, for [Feature.availability]. The theme provides it on a
 * television; a preview may provide its own to draw a device with or without a
 * picker or a browser. Null means nobody asked the system yet.
 */
val LocalCapabilities = staticCompositionLocalOf<Capabilities?> { null }

/**
 * A television's safe area: 5% of its 960 × 540 dp on every side, for the sets
 * that still cut the picture's edges off. This is the television's own; a
 * window of another size has [tvSafeArea], and the theme hands that one down
 * as [LocalTvSafeArea].
 */
val TvSafeArea = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/**
 * The safe area of a window [width] × [height] wide: [TvSafeArea], for every window a
 * television can have — down to 640 × 360dp, a 720p panel at 320 dpi, and at any scale of
 * the interface — and 7.5% of a window that is smaller, which is a phone with the TV
 * interface turned on: 48dp off each side of 411dp would be a sixth of its width.
 */
fun tvSafeArea(width: Dp, height: Dp): PaddingValues {
    if (width <= 0.dp || height <= 0.dp) return TvSafeArea
    return PaddingValues(
        horizontal = minOf(TvSafeArea.calculateLeftPadding(LayoutDirection.Ltr), width * SAFE_AREA_SHARE),
        vertical = minOf(TvSafeArea.calculateTopPadding(), height * SAFE_AREA_SHARE),
    )
}

/** 48dp of 640dp and 27dp of 360dp: the smallest window a television has. */
private const val SAFE_AREA_SHARE = 0.075f

/**
 * The safe area of this app's window. The theme applies it to a screen; a sheet
 * or a dialog is a window the theme does not reach, and pads itself with what
 * it reads here — locals pass through to them, the size of their own window
 * would not.
 */
val LocalTvSafeArea = staticCompositionLocalOf { TvSafeArea }

/**
 * The class of this device, following the Interface setting as it changes.
 * For the theme; everything under it reads [LocalDeviceClass].
 *
 * In a preview it is what [LocalPreviewDeviceClass] says, or the preview's own
 * `uiMode`; nothing is asked of a package manager the renderer does not have.
 */
@Composable
fun rememberDeviceClass(): DeviceClass {
    val uiMode = LocalConfiguration.current.uiMode
    if (LocalInspectionMode.current) {
        return LocalPreviewDeviceClass.current
            ?: DeviceProfile.deviceClass(uiMode, hasLeanback = false, hasTouchscreen = true)
    }
    val context = LocalContext.current
    var override by remember { mutableStateOf(GlobalSettings.getDeviceOverride(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == GlobalSettings.KEY_DEVICE_OVERRIDE) override = GlobalSettings.getDeviceOverride(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(context, uiMode, override) { DeviceProfile.deviceClass(context, uiMode, override) }
}

/** For shared components. A screen asks a [Feature] or [WindowLayout] instead. */
val isTv: Boolean
    @Composable @ReadOnlyComposable get() = LocalDeviceClass.current == DeviceClass.TV

/**
 * What the system can do: the provided answer, or the system's own, asked once
 * per place this is called from. A preview without one draws a television as
 * bare as they come — no browser, no picker — the case a screen has to survive.
 */
@Composable
fun rememberCapabilities(): Capabilities {
    LocalCapabilities.current?.let { return it }
    if (LocalInspectionMode.current) return Capabilities.NONE
    val context = LocalContext.current
    return remember(context) { Capabilities.of(context) }
}

/**
 * What the device is, whatever interface it is set to (Settings → Appearance → Interface).
 * A preview is a handheld: the renderer has no package manager to ask.
 */
@Composable
private fun rememberHardwareClass(): DeviceClass {
    if (LocalInspectionMode.current) return DeviceClass.HANDHELD
    val context = LocalContext.current
    val uiMode = LocalConfiguration.current.uiMode
    return remember(context, uiMode) { DeviceProfile.hardwareClass(context, uiMode) }
}

/**
 * Where this feature is to be found on this device. Always [Availability.OFFERED] in the
 * handheld interface, but for the file picker of a television drawn as a handheld
 * ([DeviceProfile.availability]).
 */
@Composable
fun Feature.availability(): Availability {
    val device = LocalDeviceClass.current
    // What the device is matters to one answer only, the handheld interface's about the picker.
    val hardware = if (device == DeviceClass.HANDHELD && this == Feature.SYSTEM_FILE_PICKER) rememberHardwareClass() else device
    // The same early answer as DeviceProfile's: a phone is never asked what it has.
    if (device == DeviceClass.HANDHELD && hardware == DeviceClass.HANDHELD) return Availability.OFFERED
    val caps = rememberCapabilities()
    return DeviceProfile.availability(this, device, hardware) { caps }
}

/** Whether this feature keeps its usual place — the question an entry point asks. */
@Composable
fun Feature.offered(): Boolean = availability() == Availability.OFFERED

/** [content] where [feature] keeps its usual place, nothing elsewhere. */
@Composable
fun IfOffered(feature: Feature, content: @Composable () -> Unit) {
    if (feature.offered()) content()
}
