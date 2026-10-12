package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyState
import io.github.bropines.tailscaled.admin.policy.visual.AccessRule
import io.github.bropines.tailscaled.admin.policy.visual.AccessRuleEditorContent
import io.github.bropines.tailscaled.admin.policy.visual.AttrEditor
import io.github.bropines.tailscaled.admin.policy.visual.Definitions
import io.github.bropines.tailscaled.admin.policy.visual.DefsEditorColumn
import io.github.bropines.tailscaled.admin.policy.visual.NewRelayForm
import io.github.bropines.tailscaled.admin.policy.visual.PeerRelays
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.PolicyValue
import io.github.bropines.tailscaled.admin.policy.visual.PostureCatalogue
import io.github.bropines.tailscaled.admin.policy.visual.PostureEditor
import io.github.bropines.tailscaled.admin.policy.visual.RelayEditor
import io.github.bropines.tailscaled.admin.policy.visual.RelayForm
import io.github.bropines.tailscaled.admin.policy.visual.RuleOffers
import io.github.bropines.tailscaled.admin.policy.visual.Section
import io.github.bropines.tailscaled.admin.policy.visual.SourceEdits
import io.github.bropines.tailscaled.admin.policy.visual.SourceTree
import io.github.bropines.tailscaled.admin.policy.visual.VisualEditorScreen
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv
import io.github.bropines.tailscaled.admin.policy.visual.VisualLayout
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * What 2026's policy grammar added to the visual policy editor: the Peer relays page (a rule
 * of relays only, one that is also an access rule, one that offers the relays to everyone),
 * its editors, the access rule's capability that leads there, the posture catalogue with
 * ip:publicAddress, netmap caching among the device attributes, declarative node sharing on the
 * Network page. Render this class alone: `--tests '*PolicyVisualPeerPreviews*'`.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class PeerSizes

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class PeerPhone

@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class PeerTablet

/** An editor's whole length, as its sheet scrolls through it. */
@Preview(name = "sheet", device = "spec:width=411dp,height=1500dp,dpi=420")
@Preview(name = "sheet-ru", device = "spec:width=411dp,height=1500dp,dpi=420", locale = "ru")
annotation class PeerSheet

object PeerDemo {
    /** The demo tailnet with client versions, an Android phone tagged as a relay and an old Pi behind the NAT. */
    val devices: List<ApiDevice> = PolicyVisualDemo.devices.map { d ->
        when (d.shortName) {
            "old-pi" -> d.copy(tags = listOf("tag:homelab"), clientVersion = "1.80.2-t5e1a2b3c4")
            else -> d.copy(clientVersion = "1.104.1-tf00dcafe0")
        }
    } + ApiDevice(
        id = "11", nodeId = "n11", name = "relay-pixel.tail1234.ts.net", user = "alice@example.com", os = "android",
        tags = listOf("tag:exit-node"), addresses = listOf("100.64.0.60"), clientVersion = "1.104.1-tf00dcafe0",
    )

    /**
     * PolicyVisualDemo's policy with three rules of relays: the homelab through the exit node (a
     * comment over it, the way the author writes them), the Taildrive grant granting relays too,
     * and the DNS servers offered as relays to every device.
     */
    val policy: String = run {
        var t = PolicyVisualDemo.text
        t = SourceEdits.put(t, PolicyPath.of("grants", 0, "app"), PeerRelays.CAP, PolicyValue.Arr(emptyList()), emptyList())
        t = PeerRelays.add(t, listOf("tag:homelab"), listOf("tag:exit-node"))
        t = PolicyEdits.setComment(t, PolicyPath.of("grants", 1), "Домашний NAS за NAT провайдера: ретранслирует exit-нода во Франкфурте")
        t = PeerRelays.add(t, listOf("*"), listOf("tag:dns"))
        t
    }

    val file = PolicyFile(PolicyVisualDemo.text, "\"7c41e0a9d2\"")

    fun state(text: String = policy, page: VisualSection = VisualSection.PEER_RELAYS, selected: PolicyPath? = null, headscale: Boolean = false): ConsoleState = ConsoleState(
        phase = ConsolePhase.READY,
        caps = if (headscale) Capabilities(BackendKind.HEADSCALE_V2, CredentialKind.HEADSCALE_API_KEY, setOf(BackendFeature.POLICY, BackendFeature.DEVICES)) else null,
        devices = Loadable(devices, loadedAt = 1),
        users = Loadable(PolicyVisualDemo.users, loadedAt = 1),
        policy = PolicyState(
            file = Loadable(file, loadedAt = 1),
            editor = PolicyEditorState(
                base = file, text = text, page = page, selected = selected,
                undoStack = if (text != file.text) listOf(file.text) else emptyList(),
            ),
        ),
    )

    fun env(text: String = policy, headscale: Boolean = false): VisualEnv = VisualEnv(
        draft = PolicyVisualDemo.draft(text),
        devices = devices,
        users = PolicyVisualDemo.users,
        headscale = headscale,
        risks = VisualEnv.risks(PolicyVisualDemo.text, text),
    )

    /** The Network page's sharing: one tailnet trusted, its group named by a grant. */
    val shared: String = run {
        var t = PolicyVisualDemo.text
        t = PolicyEdits.setOption(
            t, Section.EXTERNAL_TAILNETS,
            PolicyValue.Raw(
                "{\n\t\t\"partner\": {\n\t\t\t\"externalID\": \"T7fq2Xk3CNTRL\",\n\t\t\t\"allowIncomingConnections\": true,\n" +
                    "\t\t\t\"allowExternalReferencesTo\": [\"group:prod-admin\", \"tag:server\"],\n\t\t},\n\t}"
            ),
        )
        PolicyEdits.addRule(t, Section.GRANTS, PolicyEdits.grantFields(listOf("group://partner/sre"), listOf("tag:dns"), listOf("tcp:443")))
    }

    /** Postures with the new public address condition; nodeAttrs with netmap caching for members. */
    val defs: String = run {
        var t = PolicyVisualDemo.text
        t = PolicyEdits.putNamed(t, Section.POSTURES, "posture:office", listOf("node:os IN ['macos', 'windows', 'linux']", "ip:publicAddress IN ['203.0.113.0/24']", "falcon:ztaScore >= 70"))
        t = PolicyEdits.addRule(t, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(listOf("autogroup:member", "tag:homelab"), listOf("cache-network-maps", "disable-cache-network-maps")))
        t
    }
}

@Composable
private fun PeerTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Screen(state: ConsoleState) = VisualEditorScreen(state, state.policy.editor!!, null) {}

/** An editor as its bottom sheet draws it over the page. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(dark: Boolean = true, content: @Composable ColumnScope.() -> Unit) = PeerTheme(dark) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                DefsEditorColumn(content = content)
            }
        }
    }
}

private val noLayout = VisualLayout()

// ---- the Peer relays page ----

/** Three rules: relays only (with a comment), an access rule that grants relays too, relays for everyone (a new risk). */
@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerRelays() = PeerTheme { Screen(PeerDemo.state()) }

/** The same beside the editor of the rule of relays only. A phone opens it as a sheet, which the renderer does not draw. */
@PreviewTest @PeerTablet @Composable
fun PolicyVisualPeerRelaysTablet() = PeerTheme { Screen(PeerDemo.state(selected = PolicyPath.of("grants", 1))) }

@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerRelaysLight() = PeerTheme(dark = false) { Screen(PeerDemo.state()) }

/** No rules yet: what that means, Add, the two templates. */
@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerRelaysEmpty() = PeerTheme { Screen(PeerDemo.state(text = PolicyVisualDemo.text)) }

/** Headscale takes relay grants as well: the same page. */
@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerRelaysHeadscale() = PeerTheme(dark = false) { Screen(PeerDemo.state(headscale = true)) }

@PreviewTest @PeerSheet @Composable
fun PolicyVisualPeerEditor() = Sheet {
    val env = PeerDemo.env()
    val r = env.model!!.grants[1]
    RelayEditor(r, env, PolicyVisualDemo.actions, noLayout, SourceTree.parse(PeerDemo.policy), HuJson.parse(PeerDemo.policy), Definitions.findings(PeerDemo.policy)[r.origin.path].orEmpty())
}

/** The access rule that grants relays too: its sides shown, split or open it in Access. */
@PreviewTest @PeerSheet @Composable
fun PolicyVisualPeerEditorMixed() = Sheet(dark = false) {
    val env = PeerDemo.env()
    val r = env.model!!.grants[0]
    RelayEditor(r, env, PolicyVisualDemo.actions, noLayout, SourceTree.parse(PeerDemo.policy), HuJson.parse(PeerDemo.policy), Definitions.findings(PeerDemo.policy)[r.origin.path].orEmpty())
}

/** Relays for everyone: the lint's word on the rule. */
@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerEditorBroad() = Sheet {
    val env = PeerDemo.env()
    val r = env.model!!.grants[2]
    RelayEditor(r, env, PolicyVisualDemo.actions, noLayout, SourceTree.parse(PeerDemo.policy), HuJson.parse(PeerDemo.policy), Definitions.findings(PeerDemo.policy)[r.origin.path].orEmpty())
}

@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerNew() = Sheet {
    NewRelayForm(RelayForm(relays = listOf("tag:exit-node"), clients = listOf("autogroup:member")), PeerDemo.env(), PolicyVisualDemo.actions, onChange = {}) {}
}

/** On the Access page, the grant's relay capability leads to the Peer relays page. */
@PreviewTest @PeerSheet @Composable
fun PolicyVisualPeerAccessGrant() = PeerTheme {
    val env = PeerDemo.env()
    // The rule editor scrolls itself: the sheet's colour and room, without a second scrolling column.
    Surface(Modifier.fillMaxSize().padding(top = 24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Box(Modifier.padding(top = 24.dp)) { AccessGrant(env) }
    }
}

@Composable
private fun AccessGrant(env: VisualEnv) {
    AccessRuleEditorContent(AccessRule.Grant(env.model!!.grants[0]), env, PolicyVisualDemo.actions, isNew = false, offers = RuleOffers(), onChange = {}, onAction = {})
}

// ---- postures, attributes, sharing ----

@PreviewTest @PeerSheet @Composable
fun PolicyVisualPeerPosture() = Sheet {
    val env = PeerDemo.env(PeerDemo.defs)
    val p = env.model!!.postures.single()
    PostureEditor(p, env, PolicyVisualDemo.actions, noLayout, SourceTree.parse(PeerDemo.defs), emptyList())
}

/** The catalogue opened: what each attribute holds, Tailscale's example a tap away. */
@PreviewTest @PeerSheet @Composable
fun PolicyVisualPeerCatalogue() = Sheet { PostureCatalogue(enabled = true, onUse = {}, open = true) }

/** Netmap caching among the attributes; the faster-start template under the list of a file without it. */
@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerAttrs() = PeerTheme { Screen(PeerDemo.state(text = PolicyVisualDemo.text, page = VisualSection.ATTRIBUTES)) }

@PreviewTest @PeerSheet @Composable
fun PolicyVisualPeerAttrEditor() = Sheet {
    val env = PeerDemo.env(PeerDemo.defs)
    val r = env.model!!.nodeAttrs.last()
    AttrEditor(r, env, PolicyVisualDemo.actions, noLayout, SourceTree.parse(PeerDemo.defs), HuJson.parse(PeerDemo.defs), emptyList())
}

@PreviewTest @PeerPhone @Composable
fun PolicyVisualPeerAttrEditorHeadscale() = Sheet(dark = false) {
    val env = PeerDemo.env(PeerDemo.defs, headscale = true)
    val r = env.model!!.nodeAttrs.last()
    AttrEditor(r, env, PolicyVisualDemo.actions, noLayout, SourceTree.parse(PeerDemo.defs), HuJson.parse(PeerDemo.defs), emptyList())
}

/** Declarative node sharing on the Network page: the trusted tailnet read, edited in JSON. */
@PreviewTest @PeerSizes @Composable
fun PolicyVisualPeerSharing() = PeerTheme { Screen(PeerDemo.state(text = PeerDemo.shared, page = VisualSection.NETWORK)) }
