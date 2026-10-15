package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyState
import io.github.bropines.tailscaled.admin.policy.templates.ParamValue
import io.github.bropines.tailscaled.admin.policy.templates.TemplateFlow
import io.github.bropines.tailscaled.admin.policy.templates.TemplateNav
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.SourceEdits
import io.github.bropines.tailscaled.admin.policy.visual.VisualEditorScreen
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The remote-management template of the visual policy editor and the policy tab's copy
 * actions. The template (policy-templates/tailsocks-remote.json): its card under the Access
 * page's rules, and the page it opens — empty, filled (with what Apply adds), on a path a rule
 * already opens, and with a tag that cannot have "their own devices". A phone opens the page as
 * a sheet, which the renderer does not draw, so it is drawn on its own. The policy tab: Copy
 * policy, Copy for an AI agent and Share under the editing buttons. Render this class alone:
 * `--tests '*PolicyRemoteCopyPreviews*'`.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class PolicyRmPhone

@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class PolicyRmTablet

/** The form's whole length, as its sheet scrolls through it. */
@Preview(name = "sheet", device = "spec:width=411dp,height=1400dp,dpi=420")
@Preview(name = "sheet-ru", device = "spec:width=411dp,height=1400dp,dpi=420", locale = "ru")
annotation class PolicyRmSheet

private object PolicyRmDemo {
    /** The demo policy with its last ACL left and no grants: the template's card stands under the rule, in view. */
    val text: String by lazy {
        var t = PolicyVisualDemo.text
        repeat(13) { t = PolicyEdits.removeRule(t, PolicyPath.of("acls", 0)) }
        SourceEdits.remove(t, PolicyPath.of("grants"))
    }

    fun state(policy: String = text): ConsoleState {
        val file = PolicyFile(policy, "\"7c41e0a9d2\"")
        return ConsoleState(
            phase = ConsolePhase.READY,
            devices = Loadable(PolicyVisualDemo.devices, loadedAt = 1),
            users = Loadable(PolicyVisualDemo.users, loadedAt = 1),
            policy = PolicyState(
                file = Loadable(file, loadedAt = 1),
                editor = PolicyEditorState(base = file, text = policy, page = VisualSection.ACCESS),
            ),
        )
    }
}

@Composable
private fun PolicyRmTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** The form as its bottom sheet holds it: the sheet's colour, the room its handle takes. */
@Composable
private fun Sheet(dark: Boolean = true, content: @Composable () -> Unit) = PolicyRmTheme(dark) {
    Surface(Modifier.fillMaxSize().padding(top = 24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Box(Modifier.padding(top = 24.dp)) { content() }
    }
}

/** Who manages and whose devices, as the page's form holds them. */
private data class RemoteForm(val managers: List<String> = emptyList(), val managed: List<String> = emptyList())

private const val MEMBER = "autogroup:member"
private const val SELF = "autogroup:self"

@Composable
private fun Form(form: RemoteForm, policy: String = PolicyVisualDemo.text) = TemplateFlow(
    TemplateNav.Page("tailsocks-remote", mapOf("managers" to ParamValue.Items(form.managers), "managed" to ParamValue.Items(form.managed))),
    PolicyVisualDemo.env(policy = policy), PolicyVisualDemo.actions, onNav = {}, onApplied = {}, onShow = {},
)

/** The Access page: the rules, then the template's card. */
@PreviewTest @PolicyRmPhone @Composable
fun PolicyRemoteAccessPage() = PolicyRmTheme { VisualEditorScreen(PolicyRmDemo.state(), PolicyRmDemo.state().policy.editor!!, null) {} }

@PreviewTest @PolicyRmTablet @Composable
fun PolicyRemoteAccessPageTablet() = PolicyRmTheme { VisualEditorScreen(PolicyRmDemo.state(), PolicyRmDemo.state().policy.editor!!, null) {} }

/** Opened: nothing chosen yet, Create waits. */
@PreviewTest @PolicyRmPhone @Composable
fun PolicyRemoteFormEmpty() = Sheet { Form(RemoteForm()) }

/** The admins manage the guests' devices: what Create writes, rule and test. */
@PreviewTest @PolicyRmSheet @Composable
fun PolicyRemoteFormFilled() = Sheet { Form(RemoteForm(listOf("group:prod-admin"), listOf("group:guest-users", "tag:android"))) }

/** Every member, their own devices, light: no test can name them. */
@PreviewTest @PolicyRmPhone @Composable
fun PolicyRemoteFormSelf() = Sheet(dark = false) { Form(RemoteForm(listOf(MEMBER), listOf(SELF))) }

/** The admins and the homelab: the first ACL opens every port already; only the test is offered. */
@PreviewTest @PolicyRmSheet @Composable
fun PolicyRemoteFormOpen() = Sheet { Form(RemoteForm(listOf("group:prod-admin"), listOf("tag:homelab"))) }

/** A tag among the managers of "their own devices": said, and Create stays off. */
@PreviewTest @PolicyRmPhone @Composable
fun PolicyRemoteFormTagSelf() = Sheet { Form(RemoteForm(listOf("alice@example.com", "tag:master"), listOf(SELF))) }

/** The policy tab: the copy actions under the editing buttons. */
@PreviewTest @PolicyRmPhone @Composable
fun PolicyCopyTab() = PolicyRmTheme { AdminDashboard(AdminConfigDemo.state, null, {}, startTab = ConsoleTab.POLICY) }

@PreviewTest @PolicyRmTablet @Composable
fun PolicyCopyTabTablet() = PolicyRmTheme(dark = false) { AdminDashboard(AdminConfigDemo.state, null, {}, startTab = ConsoleTab.POLICY) }
