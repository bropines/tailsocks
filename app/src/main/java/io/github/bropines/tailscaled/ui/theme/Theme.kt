package io.github.bropines.tailscaled.ui.theme

import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.*

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb

// ==========================================
// COLOR PRESETS SCHEMES DEFINITIONS (M3)
// ==========================================

// 1. DEFAULT PRESET (M3 Purple/Blue)
private val DefaultLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    background = Color(0xFFFEF7FF),
    surface = Color(0xFFFEF7FF)
)
private val DefaultDark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFF141218),
    surface = Color(0xFF141218)
)

// 2. LAVENDER PRESET (Soft Violet)
private val LavenderLight = lightColorScheme(
    primary = Color(0xFF704E9B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECDCFF),
    onPrimaryContainer = Color(0xFF270057),
    secondary = Color(0xFF635B70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEADEF7),
    onSecondaryContainer = Color(0xFF1F182A),
    background = Color(0xFFFFFBFD),
    surface = Color(0xFFFFFBFD)
)
private val LavenderDark = darkColorScheme(
    primary = Color(0xFFD6BAFF),
    onPrimary = Color(0xFF3F1B69),
    primaryContainer = Color(0xFF573581),
    onPrimaryContainer = Color(0xFFECDCFF),
    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF342D40),
    secondaryContainer = Color(0xFF4B4358),
    onSecondaryContainer = Color(0xFFEADEF7),
    background = Color(0xFF1E1A22),
    surface = Color(0xFF1E1A22)
)

// 3. EMERALD PRESET (Fresh Green)
private val EmeraldLight = lightColorScheme(
    primary = Color(0xFF006B54),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7BF8D3),
    onPrimaryContainer = Color(0xFF002017),
    secondary = Color(0xFF4B635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE9DD),
    onSecondaryContainer = Color(0xFF082018),
    background = Color(0xFFF6FBF7),
    surface = Color(0xFFF6FBF7)
)
private val EmeraldDark = darkColorScheme(
    primary = Color(0xFF5CDBB8),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFF7BF8D3),
    secondary = Color(0xFFB2CCBF),
    onSecondary = Color(0xFF1E352C),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFCEE9DD),
    background = Color(0xFF151D1A),
    surface = Color(0xFF151D1A)
)

// 4. SAPPHIRE PRESET (Deep Blue)
private val SapphireLight = lightColorScheme(
    primary = Color(0xFF005FAF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    background = Color(0xFFFDFCFF),
    surface = Color(0xFFFDFCFF)
)
private val SapphireDark = darkColorScheme(
    primary = Color(0xFFA5C8FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF004786),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273140),
    secondaryContainer = Color(0xFF3E4758),
    onSecondaryContainer = Color(0xFFD7E3F7),
    background = Color(0xFF10141B),
    surface = Color(0xFF10141B)
)

// 5. AMBER PRESET (Warm Golden Orange)
private val AmberLight = lightColorScheme(
    primary = Color(0xFF825500),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB3),
    onPrimaryContainer = Color(0xFF291800),
    secondary = Color(0xFF6F5B40),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADEBC),
    onSecondaryContainer = Color(0xFF271904),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8)
)
private val AmberDark = darkColorScheme(
    primary = Color(0xFFFFB951),
    onPrimary = Color(0xFF452B00),
    primaryContainer = Color(0xFF633F00),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFFDDC2A1),
    onSecondary = Color(0xFF3E2D16),
    secondaryContainer = Color(0xFF56442A),
    onSecondaryContainer = Color(0xFFFADEBC),
    background = Color(0xFF17130E),
    surface = Color(0xFF17130E)
)

// 6. MONOCHROME PRESET (Sleek Gray Minimalist)
private val MonochromeLight = lightColorScheme(
    primary = Color(0xFF1D2023),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E2E6),
    onPrimaryContainer = Color(0xFF1A1C1E),
    secondary = Color(0xFF5D5E62),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E2E6),
    onSecondaryContainer = Color(0xFF1A1C1E),
    background = Color(0xFFF9F9FB),
    surface = Color(0xFFF9F9FB)
)
private val MonochromeDark = darkColorScheme(
    primary = Color(0xFFE2E2E6),
    onPrimary = Color(0xFF2D3033),
    primaryContainer = Color(0xFF45474A),
    onPrimaryContainer = Color(0xFFE2E2E6),
    secondary = Color(0xFFC5C6CA),
    onSecondary = Color(0xFF2F3033),
    secondaryContainer = Color(0xFF46474B),
    onSecondaryContainer = Color(0xFFE1E2E6),
)

// 7. TOKIO NIGHT PRESET (VSCode/Neovim inspired deep blue-violet)
private val TokioNightLight = lightColorScheme(
    primary = Color(0xFF3854A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001453),
    secondary = Color(0xFF0F4B6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCBE6FF),
    onSecondaryContainer = Color(0xFF001E30),
    background = Color(0xFFF9FAFB),
    surface = Color(0xFFF9FAFB)
)
private val TokioNightDark = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF15161E),
    primaryContainer = Color(0xFF2E3D5F),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFB4F9F8),
    onSecondary = Color(0xFF1A1B26),
    secondaryContainer = Color(0xFF1F2335),
    onSecondaryContainer = Color(0xFFC0CAF5),
    background = Color(0xFF1A1B26),
    surface = Color(0xFF16161E)
)


// Amoled pure black transformation
fun ColorScheme.toAmoled(): ColorScheme {
    return this.copy(
        background = Color(0xFF000000),
        surface = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF000000)
    )
}

@Composable
fun TailSocksTheme(
    appTheme: String? = null,
    themePreset: String? = null,
    dynamicColorEnabled: Boolean? = null,
    amoledModeEnabled: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Reactive Compose states initialized with current preferences
    var resolvedTheme by remember { mutableStateOf(GlobalSettings.getAppTheme(context)) }
    var resolvedPreset by remember { mutableStateOf(GlobalSettings.getThemePreset(context)) }
    var resolvedDynamic by remember { mutableStateOf(GlobalSettings.isDynamicColorEnabled(context)) }
    var resolvedAmoled by remember { mutableStateOf(GlobalSettings.getBoolean(context, "amoled_mode", false)) }

    // If explicit states are passed (e.g. inside Settings screen for real-time visual updates), use them
    LaunchedEffect(appTheme, themePreset, dynamicColorEnabled, amoledModeEnabled) {
        if (appTheme != null) resolvedTheme = appTheme
        if (themePreset != null) resolvedPreset = themePreset
        if (dynamicColorEnabled != null) resolvedDynamic = dynamicColorEnabled
        if (amoledModeEnabled != null) resolvedAmoled = amoledModeEnabled
    }

    // Shared preferences listener to instantly trigger updates on back press / background screens
    DisposableEffect(context) {
        val sharedPrefs = context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "app_theme" -> resolvedTheme = GlobalSettings.getAppTheme(context)
                "theme_preset" -> resolvedPreset = GlobalSettings.getThemePreset(context)
                "dynamic_color" -> resolvedDynamic = GlobalSettings.isDynamicColorEnabled(context)
                "amoled_mode" -> resolvedAmoled = GlobalSettings.getBoolean(context, "amoled_mode", false)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val actualAppTheme = resolvedTheme
    val actualPreset = resolvedPreset
    val actualDynamic = resolvedDynamic
    val actualAmoled = resolvedAmoled

    // Determine dark mode
    val systemDark = isSystemInDarkTheme()
    val isDark = when (actualAppTheme) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

    // Determine basic color scheme
    var colorScheme = when {
        actualDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            // Apply chosen preset
            when (actualPreset) {
                "lavender" -> if (isDark) LavenderDark else LavenderLight
                "emerald" -> if (isDark) EmeraldDark else EmeraldLight
                "sapphire" -> if (isDark) SapphireDark else SapphireLight
                "amber" -> if (isDark) AmberDark else AmberLight
                "monochrome" -> if (isDark) MonochromeDark else MonochromeLight
                "tokionight" -> if (isDark) TokioNightDark else TokioNightLight
                else -> if (isDark) DefaultDark else DefaultLight
            }
        }
    }

    // Apply Amoled pure black adjustments if enabled and dark mode is active
    if (actualAmoled && isDark) {
        colorScheme = colorScheme.toAmoled()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }
 
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
