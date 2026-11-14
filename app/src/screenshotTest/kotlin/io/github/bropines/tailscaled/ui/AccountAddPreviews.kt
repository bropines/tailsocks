package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

// "Add account" (MainAccounts.kt) on a phone on its side, where the dialog has less height
// than its form, and upright, where it has all it needs. A window cannot be rendered over a
// page, so the dialog is drawn by what it holds, in a frame laid out the way AlertDialog lays
// out its own: the title and the buttons keep their height, the text slot gets what is left
// and scrolls inside it. At the top the three fields stand whole; at the end the key's hint
// and "Report the real OS" with its label, nothing over the key field's outline.

@Preview(name = "1-phone-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
@Preview(name = "2-phone-landscape-ru", device = "spec:width=891dp,height=411dp,dpi=420", locale = "ru")
internal annotation class AccountAddLandscape

@Preview(name = "3-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "4-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
internal annotation class AccountAddPortrait

@Composable
private fun AccountAdd(scroll: @Composable () -> ScrollState = { rememberScrollState() }) = AdaptiveShowcase(null) {
    val words = mainAccountAddWords()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        // A dialog window's margins on a phone; the status and navigation bars are not drawn.
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(words.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    MainAccountAddForm(
                        words = words,
                        name = "rotTest42", onName = {},
                        server = "", onServer = {},
                        authKey = "", onAuthKey = {},
                        honest = true, onHonest = {},
                        modifier = Modifier.weight(1f, fill = false),
                        scroll = scroll(),
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = {}) { Text(words.cancel) }
                        Button(onClick = {}) { Text(words.add) }
                    }
                }
            }
        }
    }
}

@PreviewTest @AccountAddLandscape @Composable
fun AccountAddLandscapeTop() = AccountAdd()

@PreviewTest @AccountAddLandscape @Composable
fun AccountAddLandscapeEnd() = AccountAdd { rememberScrollState(Int.MAX_VALUE) }

@PreviewTest @AccountAddPortrait @Composable
fun AccountAddUpright() = AccountAdd()
