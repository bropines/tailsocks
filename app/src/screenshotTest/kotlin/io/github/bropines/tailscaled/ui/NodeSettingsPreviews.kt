package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.ClientPrefs
import io.github.bropines.tailscaled.core.TailnetLock
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The node switches in Settings → Account & connection (NodeSettings.kt) and the Tailnet Lock
 * screen, with made-up state handed in directly: the real section reads the bridge, which the
 * renderer does not have. Phone and the 1280×800 tablet, in English and Russian.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class NodeSizes

private val self = "nlpub:" + "3f9a".repeat(16)

private val signerStatus = """
    {"Enabled":true,"PublicKey":"$self","NodeKey":"nodekey:${"51c2".repeat(16)}","NodeKeySigned":true,
     "TrustedKeys":[{"Key":"$self","Votes":1},{"Key":"nlpub:${"77ab".repeat(16)}","Votes":1}],
     "FilteredPeers":[
       {"Name":"work-laptop.tail9c2e.ts.net.","StableID":"nW1","TailscaleIPs":["100.101.7.12","fd7a:115c:a1e0::6a01:70c"],"NodeKey":"nodekey:${"e0d4".repeat(16)}"},
       {"Name":"nas.tail9c2e.ts.net.","StableID":"nW2","TailscaleIPs":["100.88.0.4"],"NodeKey":"nodekey:${"19bf".repeat(16)}"}]}
""".trimIndent()

private val lockedOutStatus = """
    {"Enabled":true,"PublicKey":"$self","NodeKey":"nodekey:${"51c2".repeat(16)}","NodeKeySigned":false,
     "TrustedKeys":[{"Key":"nlpub:${"77ab".repeat(16)}","Votes":1}],"FilteredPeers":[]}
""".trimIndent()

private val offStatus = """{"Enabled":false,"PublicKey":"$self","NodeKey":"nodekey:${"51c2".repeat(16)}"}"""

@Composable
private fun Themed(content: @Composable () -> Unit) =
    TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }

/** A profile that turned shields up and the relay on; posture comes from an extra argument. */
private fun demoSwitches() = NodeSwitchesState(
    stored = ClientPrefs.Values(shieldsUp = true, relay = ClientPrefs.Relay(true, 40000)),
    relayPortText = "40000",
).apply {
    live = ClientPrefs.Values(shieldsUp = true, exitNodeAllowLan = true, reportPosture = false, relay = ClientPrefs.Relay(true, 40000), appConnector = false)
    overrides = ClientPrefs.Values(reportPosture = true)
    relaySessions = 2
    tailnetLock = TailnetLockRow.Loaded(TailnetLock.parse(signerStatus)!!)
}

@PreviewTest @NodeSizes @Composable
fun NodeSettingsCards() = Themed {
    val state = demoSwitches()
    val save: (String, Any) -> Unit = { _, _ -> }
    ReadableWidth {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SettingsCard(title = stringResource(R.string.settings_sect_account_format, "Personal")) {
                SettingsExitNodeItem(stringResource(R.string.settings_exit_node_title), "nX1", "100.90.1.2", Icons.AutoMirrored.Filled.Input) { _, _ -> }
                ExitNodeLanRow(state, save)
            }
            SettingsCardGap()
            NodeSecurityCard(state, save) {}
            SettingsCardGap()
            SettingsCard(title = stringResource(R.string.settings_sect_service_ad)) {
                NodeServiceRows(state, save)
            }
        }
    }
}

@PreviewTest @NodeSizes @Composable
fun TailnetLockSigner() = Themed {
    TailnetLockContent(TailnetLockLoad.Loaded(TailnetLock.parse(signerStatus)!!), canUnlock = true, signing = null, onBack = {}, onRefresh = {}, onCopy = { _, _ -> }, onSign = {})
}

@PreviewTest @NodeSizes @Composable
fun TailnetLockLockedOut() = Themed {
    TailnetLockContent(TailnetLockLoad.Loaded(TailnetLock.parse(lockedOutStatus)!!), canUnlock = true, signing = null, onBack = {}, onRefresh = {}, onCopy = { _, _ -> }, onSign = {})
}

@PreviewTest @NodeSizes @Composable
fun TailnetLockOff() = Themed {
    TailnetLockContent(TailnetLockLoad.Loaded(TailnetLock.parse(offStatus)!!), canUnlock = false, signing = null, onBack = {}, onRefresh = {}, onCopy = { _, _ -> }, onSign = {})
}

/** A television holding a trusted key: the status, no Sign buttons, and why the nodes are signed elsewhere. */
@PreviewTest @TvLanguages @Composable
fun TvTailnetLockSigner() = AdaptiveShowcase(device = DeviceClass.TV) {
    TailnetLockContent(TailnetLockLoad.Loaded(TailnetLock.parse(signerStatus)!!), canUnlock = false, signing = null, onBack = {}, onRefresh = {}, onCopy = { _, _ -> }, onSign = {}, signsHere = false, copies = false)
}

/** A television that is locked out: the command for a trusted device, shown as a code instead of copied. */
@PreviewTest @TvLanguages @Composable
fun TvTailnetLockLockedOut() = AdaptiveShowcase(device = DeviceClass.TV) {
    TailnetLockContent(TailnetLockLoad.Loaded(TailnetLock.parse(lockedOutStatus)!!), canUnlock = false, signing = null, onBack = {}, onRefresh = {}, onCopy = { _, _ -> }, onSign = {}, signsHere = false, copies = false)
}

/** The relay with its port row and the app connector, which the first render scrolls past. */
@PreviewTest @NodeSizes @Composable
fun NodeServiceCard() = Themed {
    val state = demoSwitches().apply { stored = stored.copy(appConnector = true) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SettingsCard(title = stringResource(R.string.settings_sect_service_ad)) {
            NodeServiceRows(state, { _, _ -> })
        }
    }
}
