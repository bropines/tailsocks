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
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyState
import io.github.bropines.tailscaled.admin.policy.templates.TemplateFlow
import io.github.bropines.tailscaled.admin.policy.templates.TemplateNav
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.SourceEdits
import io.github.bropines.tailscaled.admin.policy.visual.VisualEditorScreen
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv
import io.github.bropines.tailscaled.admin.policy.visual.VisualLaunch
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.admin.policy.visual.pv
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * Taildrive in one step on the visual policy editor's Device attributes page: the template
 * over the rules of a policy that gives Taildrive to no device, its page (policy-templates/
 * taildrive-own-devices.json) with what Apply adds, and the editor as a Taildrive screen opens
 * it — the page already up (beside the list on a tablet; a phone opens it as a sheet, which the
 * renderer does not draw, so the sheet is drawn on its own). Render this class alone:
 * `--tests '*PolicyVisualTaildrivePreviews*'`.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class DriveSizes

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class DrivePhone

@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class DriveTablet

/** The form's whole length, as its sheet scrolls through it. */
@Preview(name = "sheet", device = "spec:width=411dp,height=1500dp,dpi=420")
@Preview(name = "sheet-ru", device = "spec:width=411dp,height=1500dp,dpi=420", locale = "ru")
annotation class DriveSheet

object PolicyDriveDemo {
    private fun withoutAttrs(text: String): String {
        val attrs = PolicyModel.read(text)!!.nodeAttrs[0].attr - setOf("drive:share", "drive:access")
        return PolicyEdits.updateRule(text, PolicyPath.of("nodeAttrs", 0), listOf("attr" to attrs.pv()))
    }

    /** PolicyVisualDemo's policy as a tailnet that never set Taildrive up: neither attribute, no grant. */
    val policy: String = withoutAttrs(SourceEdits.remove(PolicyVisualDemo.text, PolicyPath.of("grants")))

    /** The same with the author's own Taildrive grant left in: only the attributes are missing. */
    val grantOnly: String = withoutAttrs(PolicyVisualDemo.text)

    /** The editor over [text] as saved, on the Device attributes page; [launch] as a Taildrive screen leaves it. */
    fun state(text: String = policy, launch: VisualLaunch? = null): ConsoleState {
        val file = PolicyFile(text, "\"7c41e0a9d2\"")
        return ConsoleState(
            phase = ConsolePhase.READY,
            devices = Loadable(PolicyVisualDemo.devices, loadedAt = 1),
            users = Loadable(PolicyVisualDemo.users, loadedAt = 1),
            policy = PolicyState(
                file = Loadable(file, loadedAt = 1),
                editor = PolicyEditorState(base = file, text = text, page = VisualSection.ATTRIBUTES, launch = launch),
            ),
        )
    }

    fun env(text: String = policy): VisualEnv = PolicyVisualDemo.env(policy = text, base = text)
}

@Composable
private fun DriveTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Screen(state: ConsoleState) = VisualEditorScreen(state, state.policy.editor!!, null) {}

/** The form as its bottom sheet draws it over the page. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(dark: Boolean = true, content: @Composable ColumnScope.() -> Unit) = DriveTheme(dark) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                // The template's page scrolls itself, as in its sheet.
                Column(Modifier.weight(1f), content = content)
            }
        }
    }
}

/** The template first on the page, over the policy's own rules; a tablet shows the first rule beside it. */
@PreviewTest @DriveSizes @Composable
fun PolicyVisualTaildriveAttrs() = DriveTheme { Screen(PolicyDriveDemo.state()) }

@PreviewTest @DrivePhone @Composable
fun PolicyVisualTaildriveAttrsLight() = DriveTheme(dark = false) { Screen(PolicyDriveDemo.state()) }

/** A policy with nothing in it: the template, the empty page, the other template. */
@PreviewTest @DrivePhone @Composable
fun PolicyVisualTaildriveEmpty() = DriveTheme { Screen(PolicyDriveDemo.state(text = "{\n}\n")) }

/** The template's page: whose devices, and what Apply adds — both attributes and the grant. */
@PreviewTest @DriveSheet @Composable
fun PolicyVisualTaildriveForm() = Sheet {
    TemplateFlow(TemplateNav.Page("taildrive-own-devices"), PolicyDriveDemo.env(), PolicyVisualDemo.actions, onNav = {}, onApplied = {}, onShow = {})
}

/** Over a policy that has a Taildrive grant of its own: the page says it stays, and Apply adds the attributes only. */
@PreviewTest @DrivePhone @Composable
fun PolicyVisualTaildriveFormGrantKept() = Sheet(dark = false) {
    TemplateFlow(TemplateNav.Page("taildrive-own-devices"), PolicyDriveDemo.env(PolicyDriveDemo.grantOnly), PolicyVisualDemo.actions, onNav = {}, onApplied = {}, onShow = {})
}

/** As a Taildrive screen opens the editor: the Device attributes page, the template's form up beside the list. */
@PreviewTest @DriveTablet @Composable
fun PolicyVisualTaildriveLanding() = DriveTheme { Screen(PolicyDriveDemo.state(launch = VisualLaunch.TAILDRIVE)) }
