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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.policy.visual.Definitions
import io.github.bropines.tailscaled.admin.policy.visual.EditorColumn
import io.github.bropines.tailscaled.admin.policy.visual.GroupEditor
import io.github.bropines.tailscaled.admin.policy.visual.HostEditor
import io.github.bropines.tailscaled.admin.policy.visual.IpSetEditor
import io.github.bropines.tailscaled.admin.policy.visual.AttrEditor
import io.github.bropines.tailscaled.admin.policy.visual.NewGroupForm
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.PostureEditor
import io.github.bropines.tailscaled.admin.policy.visual.Section
import io.github.bropines.tailscaled.admin.policy.visual.SourceTree
import io.github.bropines.tailscaled.admin.policy.visual.TagEditor
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv
import io.github.bropines.tailscaled.admin.policy.visual.VisualLayout
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.admin.policy.visual.VisualSectionBody
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The visual policy editor's definitions pages — groups, tags, hosts and IP sets, auto
 * approval, device attributes, postures — over PolicyVisualDemo's policy with the parts it
 * lacks added through the editor's own engine: on a phone (list; editors drawn as the sheet
 * they are), and on a tablet on its side (list and editor side by side).
 */

@Preview(name = "phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class DefsPhone

@Preview(name = "phone", device = "spec:width=411dp,height=891dp,dpi=420")
annotation class DefsPhoneOnly

/** An editor's whole length, as its sheet scrolls through it. */
@Preview(name = "sheet", device = "spec:width=411dp,height=1700dp,dpi=420")
annotation class DefsSheet

@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class DefsTablet

@Preview(name = "portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
annotation class DefsPortrait

/** PolicyVisualDemo's policy plus hosts, an IP set, postures, routes, a service and rules that use them. */
object DefsDemo {
    val policy: String = run {
        var t = PolicyVisualDemo.text
        t = PolicyEdits.putHost(t, "nas", "100.64.0.20")
        t = PolicyEdits.putHost(t, "office-lan", "192.168.10.0/24")
        t = PolicyEdits.putHost(t, "printer", "192.168.10.30")
        t = PolicyEdits.setComment(t, PolicyPath.of("hosts", "printer"), "The office printer, reached through the subnet router")
        t = PolicyEdits.putNamed(t, Section.IPSETS, "ipset:lab-net", listOf("add 100.64.0.40", "add 192.168.10.0/24", "remove 192.168.10.1", "host:printer"))
        t = PolicyEdits.putNamed(t, Section.POSTURES, "posture:latest", listOf("node:tsReleaseTrack == 'stable'", "node:tsVersion >= '1.80'"))
        t = PolicyEdits.putNamed(t, Section.POSTURES, "posture:desktop", listOf("node:os IN ['macos', 'windows', 'linux']", "node:tsAutoUpdate", "node:tsStateEncrypted"))
        t = Definitions.setDefaultPosture(t, listOf("posture:latest"))
        t = PolicyEdits.setRouteApprovers(t, "192.168.10.0/24", listOf("tag:homelab"))
        t = PolicyEdits.setRouteApprovers(t, "10.0.0.0/8", listOf("group:prod-admin", "tag:exit-node"))
        t = PolicyEdits.setServiceApprovers(t, "svc:jellyfin", listOf("tag:homelab"))
        t = PolicyEdits.addRule(t, Section.ACLS, PolicyEdits.aclFields(listOf("group:dev-admin"), listOf("nas:445,443", "ipset:lab-net:*")))
        t = PolicyEdits.addRule(t, Section.GRANTS, PolicyEdits.grantFields(listOf("group:guest-users"), listOf("svc:jellyfin"), listOf("tcp:443"), srcPosture = listOf("posture:desktop")))
        t = PolicyEdits.addRule(t, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(listOf("autogroup:member"), listOf("nextdns:abc123", "magicdns-aaaa")))
        t
    }

    fun env(headscale: Boolean = false, canWrite: Boolean = true, errors: Map<PolicyPath, List<String>> = emptyMap(), focus: PolicyPath? = null, text: String = policy): VisualEnv =
        PolicyVisualDemo.env(policy = text, headscale = headscale, canWrite = canWrite, base = PolicyVisualDemo.text, errors = errors, focus = focus)

    val model get() = env().model!!
    val tree: SourceTree get() = SourceTree.parse(policy)
    val hu get() = io.github.bropines.tailscaled.admin.policy.HuJson.parse(policy)
    fun places(name: String) = Definitions.places(tree, name)
}

@Composable
private fun Defs(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** A page as the shell shows it: two-pane when the window is, [selected] open. */
@Composable
private fun Page(section: VisualSection, env: VisualEnv = DefsDemo.env(), selected: PolicyPath? = null, dark: Boolean = true) = Defs(dark) {
    val window = rememberWindowLayout()
    VisualSectionBody(section, env, PolicyVisualDemo.actions, VisualLayout(twoPane = window.listDetail, selected = selected))
}

/** An editor as its bottom sheet draws it over the page. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(dark: Boolean = true, content: @Composable ColumnScope.() -> Unit) = Defs(dark) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                BottomSheetDefaults.DragHandle(Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
                EditorColumn(content = content)
            }
        }
    }
}

private val noLayout = VisualLayout()

// ---- groups ----

@PreviewTest @DefsPhone @Composable
fun DefsGroups() = Page(VisualSection.GROUPS)

@PreviewTest @DefsPhoneOnly @Composable
fun DefsGroupsLight() = Page(VisualSection.GROUPS, dark = false)

@PreviewTest @DefsTablet @Composable
fun DefsGroupsTablet() = Page(VisualSection.GROUPS, selected = PolicyPath.of("groups", "group:dev-admin"))

@PreviewTest @DefsTablet @Composable
fun DefsGroupsTabletLight() = Page(VisualSection.GROUPS, selected = PolicyPath.of("groups", "group:prod-admin"), dark = false)

@PreviewTest @DefsSheet @Composable
fun DefsGroupEditor() = Sheet {
    val g = DefsDemo.model.groups.first()
    GroupEditor(g, DefsDemo.env(), PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.places(g.name))
}

@PreviewTest @DefsPhoneOnly @Composable
fun DefsNewGroup() = Sheet { NewGroupForm(DefsDemo.env(), PolicyVisualDemo.actions) {} }

@PreviewTest @DefsPhoneOnly @Composable
fun DefsGroupsReadOnly() = Page(VisualSection.GROUPS, DefsDemo.env(canWrite = false))

@PreviewTest @DefsPhone @Composable
fun DefsGroupsEmpty() = Page(VisualSection.GROUPS, DefsDemo.env(text = "{\n\t\"acls\": [],\n}\n"))

// ---- tags ----

@PreviewTest @DefsPhone @Composable
fun DefsTags() = Page(VisualSection.TAGS)

@PreviewTest @DefsTablet @Composable
fun DefsTagsTablet() = Page(
    VisualSection.TAGS,
    DefsDemo.env(errors = mapOf(PolicyPath.of("tagOwners", "tag:server") to listOf("line 54: tag:server: owner \"group:prod-admin\" has no members on this tailnet"))),
    selected = PolicyPath.of("tagOwners", "tag:homelab"),
)

@PreviewTest @DefsSheet @Composable
fun DefsTagEditor() = Sheet {
    val t = DefsDemo.model.tagOwners.first { it.name == "tag:homelab" }
    TagEditor(t, DefsDemo.env(), PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.places(t.name))
}

@PreviewTest @DefsSheet @Composable
fun DefsTagEditorLight() = Sheet(dark = false) {
    val t = DefsDemo.model.tagOwners.first { it.name == "tag:master" }
    TagEditor(t, DefsDemo.env(), PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.places(t.name))
}

// ---- hosts and IP sets ----

@PreviewTest @DefsPhone @Composable
fun DefsHosts() = Page(VisualSection.HOSTS)

@PreviewTest @DefsTablet @Composable
fun DefsHostsTablet() = Page(VisualSection.HOSTS, selected = PolicyPath.of("ipsets", "ipset:lab-net"))

@PreviewTest @DefsPortrait @Composable
fun DefsHostsPortrait() = Page(VisualSection.HOSTS, dark = false)

@PreviewTest @DefsSheet @Composable
fun DefsHostEditor() = Sheet {
    val h = DefsDemo.model.hosts.first()
    HostEditor(h, DefsDemo.env(), PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.places(h.name))
}

@PreviewTest @DefsSheet @Composable
fun DefsIpSetEditor() = Sheet {
    val s = DefsDemo.model.ipsets.first()
    IpSetEditor(s, DefsDemo.env(), PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.places(s.name))
}

// ---- auto approval ----

@PreviewTest @DefsPhone @Composable
fun DefsApprovers() = Page(VisualSection.APPROVERS)

@PreviewTest @DefsTablet @Composable
fun DefsApproversTablet() = Page(VisualSection.APPROVERS, selected = PolicyPath.of("autoApprovers", "routes", "10.0.0.0/8"))

@PreviewTest @DefsTablet @Composable
fun DefsApproversTabletLight() = Page(VisualSection.APPROVERS, selected = PolicyPath.of("autoApprovers", "exitNode"), dark = false)

@PreviewTest @DefsPhoneOnly @Composable
fun DefsApproversHeadscale() = Page(VisualSection.APPROVERS, DefsDemo.env(headscale = true, text = PolicyVisualDemo.text))

// ---- device attributes ----

@PreviewTest @DefsPhone @Composable
fun DefsAttrs() = Page(VisualSection.ATTRIBUTES)

@PreviewTest @DefsTablet @Composable
fun DefsAttrsTablet() = Page(VisualSection.ATTRIBUTES, selected = PolicyPath.of("nodeAttrs", 2))

@PreviewTest @DefsSheet @Composable
fun DefsAttrEditor() = Sheet {
    val r = DefsDemo.model.nodeAttrs.first()
    AttrEditor(r, DefsDemo.env(), PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.hu, Definitions.findings(DefsDemo.policy)[r.origin.path].orEmpty())
}

@PreviewTest @DefsSheet @Composable
fun DefsAttrEditorHeadscale() = Sheet(dark = false) {
    val env = DefsDemo.env(headscale = true)
    val r = DefsDemo.model.nodeAttrs[1]
    AttrEditor(r, env, PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.hu, Definitions.findings(DefsDemo.policy)[r.origin.path].orEmpty())
}

// ---- postures ----

@PreviewTest @DefsPhone @Composable
fun DefsPostures() = Page(VisualSection.POSTURE)

@PreviewTest @DefsTablet @Composable
fun DefsPosturesTablet() = Page(VisualSection.POSTURE, selected = PolicyPath.of("postures", "posture:desktop"))

@PreviewTest @DefsSheet @Composable
fun DefsPostureEditor() = Sheet {
    val p = DefsDemo.model.postures.first()
    PostureEditor(p, DefsDemo.env(), PolicyVisualDemo.actions, noLayout, DefsDemo.tree, DefsDemo.places(p.name))
}

@PreviewTest @DefsPhoneOnly @Composable
fun DefsPosturesEmpty() = Page(VisualSection.POSTURE, DefsDemo.env(text = PolicyVisualDemo.text), dark = false)
