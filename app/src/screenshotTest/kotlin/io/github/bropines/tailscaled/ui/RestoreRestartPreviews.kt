package io.github.bropines.tailscaled.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// "Restart the app?" (SettingsBackup.kt), offered after a full restore, over the Settings
// screen. Drawn by what it holds — restoreRestartWords(), as the dialog resolves them — in
// the frame of SiblingDialogPreviews, since a window cannot be rendered over a page.

@PreviewTest @DialogOverPhone @Composable
fun RestoreRestartDialog() = Showcase {
    val words = restoreRestartWords()
    DialogContentOver(
        page = {
            SettingsScreen(
                onBack = {},
                currentTheme = "dark", onThemeChange = {},
                currentPreset = "emerald", onPresetChange = {},
                currentDynamicColor = false, onDynamicColorChange = {},
                currentAmoledMode = true, onAmoledModeChange = {}
            )
        },
        title = words.title,
        text = words.body,
    ) {
        TextButton(onClick = {}) { Text(words.later) }
        Button(onClick = {}) { Text(words.now) }
    }
}
