package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.ExitNodeSuggestion.Outcome
import io.github.bropines.tailscaled.models.StatusResponse

/*
 * What a television's first minutes look like: the home screen with what a remote is
 * offered there, signing in with a code, the first-start steps, the permissions, the
 * exit-node list. And the two things the same work gave a phone: "Sign in on another
 * device" on the signed-out card and the sheet behind it.
 *
 * A render cannot press a key, so nothing here is in focus; where the remote starts and how
 * it moves is checked on the emulator.
 */

/** A television in both languages. */
@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "6-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class TvBothLanguages

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class PhoneBothLanguages

@Composable
private fun Tv(data: DemoData? = DemoTailnet.data, content: @Composable () -> Unit) =
    AdaptiveShowcase(data, device = DeviceClass.TV, content = content)

@Composable
private fun Home() = MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })

private val relayTrouble = """
    [{"Code": "no-derp-connection", "Title": "No DERP connection",
      "Text": "Tailscale could not connect to any relay server. Check your internet connection.",
      "Severity": "medium", "ImpactsConnectivity": true, "BrokenSinceMs": 0}]
""".trimIndent()

// Home: four destinations instead of eight, no live state, the status card as tall as its column.

@PreviewTest @TvBothLanguages @Composable
fun TvHome() = Tv { Home() }

@PreviewTest @TvBothLanguages @Composable
fun TvHomeStopped() = Tv(DemoTailnet.data.copy(running = false, exitNodeIp = null)) { Home() }

@PreviewTest @TvSizes @Composable
fun TvHomeNoExit() = Tv(
    DemoTailnet.data.copy(
        statusJson = DemoTailnet.statusJson.replace("\"ExitNode\": true", "\"ExitNode\": false"),
        exitNodeName = null,
        exitNodeIp = null,
    )
) { Home() }

@PreviewTest @TvSizes @Composable
fun TvHomeProblem() = Tv(DemoTailnet.data.copy(healthJson = relayTrouble)) { Home() }

// Signing in: the code, the wait for one, and a server of one's own with a long link.

private const val TAILSCALE_LINK = "https://login.tailscale.com/a/1a2b3c4d5e6f7"
private const val HEADSCALE_LINK = "https://headscale.example.net/register/hskey-authreq-Qm9yZWFsaXNOb3RSZWFsS2V5"

@PreviewTest @TvBothLanguages @Composable
fun TvSignInCode() = Tv {
    TvSignInScreen(account = "Default", url = TAILSCALE_LINK, slow = false, renewing = false, error = null, onNewCode = {}, onClose = {})
}

@PreviewTest @TvBothLanguages @Composable
fun TvSignInWaitingForCode() = Tv {
    TvSignInScreen(account = "Default", url = null, slow = true, renewing = false, error = null, onNewCode = {}, onClose = {})
}

/** Headscale, a second account, a failed request for a new code, and the place for one more button taken. */
@PreviewTest @TvBothLanguages @Composable
fun TvSignInOwnServer() = Tv {
    TvSignInScreen(
        account = "Home lab",
        url = HEADSCALE_LINK,
        slow = false,
        renewing = false,
        error = "No new code: context deadline exceeded",
        onNewCode = {},
        onClose = {},
        extraAction = { OutlinedButton(onClick = {}) { Text("Use a key from your phone") } },
    )
}

/** The television interface turned on by hand on a phone. */
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun TvSignInOnPhone() = Tv {
    TvSignInScreen(account = "Default", url = TAILSCALE_LINK, slow = false, renewing = false, error = null, onNewCode = {}, onClose = {})
}

// The signed-out card: a phone's, with the new action, and a television's, with the code's button.

@Composable
private fun SignedOutCard(code: Boolean, url: String? = TAILSCALE_LINK) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.width(360.dp)) {
                LoggedOutCard(loginUrl = url, onConfigureProxy = {}, onStop = {}, onShowCode = if (code) ({}) else null)
            }
        }
    }
}

@PreviewTest @PhoneBothLanguages @Composable
fun PhoneSignedOutCard() = AdaptiveShowcase { SignedOutCard(code = false) }

/** No link yet: the action waits for one. */
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun PhoneSignedOutCardNoLink() = AdaptiveShowcase { SignedOutCard(code = false, url = null) }

@PreviewTest @TvBothLanguages @Composable
fun TvSignedOutCard() = Tv { SignedOutCard(code = true, url = null) }

// First start: every step a television walks, in the order it walks them.

@PreviewTest @TvBothLanguages @Composable
fun TvFirstStartWelcome() = Tv { FirstStartScreen(onFinished = {}) }

@PreviewTest @TvBothLanguages @Composable
fun TvFirstStartMode() = Tv { FirstStartScreen(onFinished = {}, initialPage = 1) }

@PreviewTest @TvSizes @Composable
fun TvFirstStartServer() = Tv { FirstStartScreen(onFinished = {}, initialPage = 2) }

@PreviewTest @TvSizes @Composable
fun TvFirstStartBypass() = Tv { FirstStartScreen(onFinished = {}, initialPage = 3) }

/** The last step there: the permissions have nothing to ask a television. */
@PreviewTest @TvBothLanguages @Composable
fun TvFirstStartLogin() = Tv { FirstStartScreen(onFinished = {}, initialPage = 4) }

@PreviewTest @TvBothLanguages @Composable
fun TvPermissions() = Tv { PermissionsScreen(onBack = {}) }

// The exit-node list, without its sheet.

private val exitNodes by lazy {
    AppJson.decodeFromString<StatusResponse>(DemoTailnet.statusJson).peers.orEmpty().values.filter { it.exitNodeOption == true }
}

@Composable
private fun ExitNodes(selectedIp: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.BottomCenter) {
            ExitNodePicker(
                nodes = exitNodes,
                loading = false,
                selectedIp = selectedIp,
                pings = DemoTailnet.pings,
                pingingAll = false,
                best = Outcome.Suggested("n4", "100.66.18.77", "exit-helsinki", "Helsinki, Finland"),
                strings = exitNodePickerStrings(),
                onPingAll = {},
                onPing = {},
                onRetryBest = {},
                onChoose = { _, _ -> },
                onClose = {},
                modifier = Modifier.fillMaxWidth().tvSheetInset(),
            )
        }
    }
}

@PreviewTest @TvBothLanguages @Composable
fun TvExitNodes() = Tv { ExitNodes(selectedIp = "100.94.210.8") }

@PreviewTest @PhoneBothLanguages @Composable
fun PhoneExitNodes() = AdaptiveShowcase { ExitNodes(selectedIp = "") }
