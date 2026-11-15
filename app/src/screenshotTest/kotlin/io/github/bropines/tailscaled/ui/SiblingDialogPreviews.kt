package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.SiblingBuild

// "TailSocks dev is already running" (SiblingDialog.kt) over the stopped main screen it is
// raised from. A window cannot be rendered over a page, so the dialog is drawn by what it
// holds — its words from siblingDialogWords(), as the dialog resolves them — in a frame laid
// out the way AlertDialog lays out its own: icon, centred title, text, the buttons at the end.
// The conflict shown is the most severe one, the same device identity in both apps.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
internal annotation class DialogOverPhone

/**
 * An alert dialog's content as AlertDialog draws it, over [page] under the scrim of a dialog
 * window. [buttons] get the end of the last row, wrapping when they do not fit beside each other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DialogContentOver(
    page: @Composable () -> Unit,
    title: String,
    text: String,
    icon: ImageVector? = null,
    buttons: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        page()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        // A dialog window's margins on a phone: the platform dialog background is inset 16dp
        // on each side (dialog_background_material), and AlertDialog fills what is left up to
        // 560dp when its text is long. The status and navigation bars are not drawn.
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
            ) {
                Column(Modifier.padding(24.dp)) {
                    if (icon != null) {
                        Icon(
                            icon, null, tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = if (icon != null) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { buttons() }
                }
            }
        }
    }
}

@PreviewTest @DialogOverPhone @Composable
fun SiblingDialogSameNode() = Showcase {
    val words = siblingDialogWords(
        SiblingBuild.Found(packageName = "io.github.bropines.tailscaled.dev", isDev = true, conflict = SiblingBuild.Conflict.SAME_NODE)
    )
    DialogContentOver(
        page = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDemo provides DemoTailnet.data.copy(running = false, exitNodeIp = null)
            ) {
                MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })
            }
        },
        title = words.title,
        text = words.text,
        icon = Icons.Default.Warning,
    ) {
        // The dismiss button, then the confirm slot, which holds two, as SiblingDialog has it.
        TextButton(onClick = {}) { Text(words.cancel) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {}) { Text(words.startAnyway) }
            Button(onClick = {}) { Text(words.open) }
        }
    }
}
