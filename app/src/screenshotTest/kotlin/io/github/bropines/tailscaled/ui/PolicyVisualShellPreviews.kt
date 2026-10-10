package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyRefusal
import io.github.bropines.tailscaled.admin.policy.PolicyState
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.VisualEditorScreen
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The visual policy editor's frame over PolicyVisualDemo's policy: the page navigation (chips on
 * a phone and a medium window, the rail from an expanded one), the Network page, and the states
 * of the whole editor — a text that does not parse, a read-only credential, a section written
 * twice, the server's refusal on the cards. Pages other slices own show their placeholder until
 * those land. Render this class alone: `--tests '*PolicyVisualShellPreviews*'`.
 */

/** A phone in both languages, the upright tablet, an unfolded foldable, the tablet on its side. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class ShellSizes

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class ShellPhone

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class ShellPhoneAndTablet

object ShellDemo {
    val file = PolicyFile(PolicyVisualDemo.text, "\"7c41e0a9d2\"")

    /** The demo policy with the network options set and a relay of its own. */
    val network: String = PolicyVisualDemo.text
        .replace(
            """		"Regions": {
			"28": null,
		},""",
            """		"Regions": {
			"28": null,
			"900": {
				"RegionID":   900,
				"RegionCode": "home",
				"RegionName": "Home relay",
				"Nodes": [
					{"Name": "900a", "RegionID": 900, "HostName": "derp1.example.com"},
					{"Name": "900b", "RegionID": 900, "HostName": "derp2.example.com"},
				],
			},
		},""",
        )
        .replace(
            "\n\t// --- 1. ОПРЕДЕЛЕНИЕ ГРУПП",
            "\n\t// Старые роутеры у гостей режут порт 41641.\n\t\"randomizeClientPort\": true,\n\n\t\"OneCGNATRoute\": \"mac-always\",\n\n\t// --- 1. ОПРЕДЕЛЕНИЕ ГРУПП",
        )

    /** A key in another spelling and a section the editor does not know. */
    val odd: String = PolicyVisualDemo.text.replace(
        "\n\t// --- 1. ОПРЕДЕЛЕНИЕ ГРУПП",
        "\n\t\"DisableIPv4\": false,\n\n\t// Заметки для ревью, сервер их не читает.\n\t\"x-review\": {\n\t\t\"owner\":  \"infra\",\n\t\t\"period\": \"quarterly\",\n\t\t\"next\":   \"2026-12-01\",\n\t},\n\n\t// --- 1. ОПРЕДЕЛЕНИЕ ГРУПП",
    )

    fun state(editor: PolicyEditorState, headscale: Boolean = false, readOnly: Boolean = false): ConsoleState = ConsoleState(
        phase = ConsolePhase.READY,
        caps = when {
            readOnly -> Capabilities.fromScopes(BackendKind.TAILSCALE, setOf(BackendFeature.POLICY, BackendFeature.DEVICES, BackendFeature.USERS), listOf("all:read"))
            headscale -> Capabilities(BackendKind.HEADSCALE_V2, CredentialKind.HEADSCALE_API_KEY, setOf(BackendFeature.POLICY, BackendFeature.DEVICES, BackendFeature.USERS))
            else -> null
        },
        devices = Loadable(PolicyVisualDemo.devices, loadedAt = 1),
        users = Loadable(PolicyVisualDemo.users, loadedAt = 1),
        policy = PolicyState(file = Loadable(file, loadedAt = 1), editor = editor),
    )

    fun editor(text: String = network, page: VisualSection = VisualSection.NETWORK, focus: PolicyPath? = null, refusal: PolicyRefusal? = null) =
        PolicyEditorState(base = file, text = text, page = page, focus = focus, refusal = refusal, undoStack = if (text != file.text) listOf(file.text) else emptyList())
}

@Composable
private fun Shell(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Editor(state: ConsoleState) = VisualEditorScreen(state, state.policy.editor!!, null) {}

/** The Network page with the options set and an own relay: chips on a phone, the rail from 840dp. */
@PreviewTest @ShellSizes @Composable
fun PolicyVisualShellNetwork() = Shell { Editor(ShellDemo.state(ShellDemo.editor())) }

@PreviewTest @ShellPhoneAndTablet @Composable
fun PolicyVisualShellNetworkLight() = Shell(dark = false) { Editor(ShellDemo.state(ShellDemo.editor())) }

/** A key spelled another way and a section the editor does not know: shown, with the way to JSON. */
@PreviewTest @ShellPhone @Composable
fun PolicyVisualShellNetworkOdd() = Shell { Editor(ShellDemo.state(ShellDemo.editor(ShellDemo.odd))) }

/** Headscale: the options it lacks are not offered. */
@PreviewTest @ShellPhone @Composable
fun PolicyVisualShellHeadscale() = Shell(dark = false) {
    Editor(ShellDemo.state(ShellDemo.editor(PolicyVisualDemo.text), headscale = true))
}

/** The first page, whatever stands on it: the navigation's look on every window, a phone on its side too. */
@PreviewTest @ShellSizes @PhoneLandscape @Composable
fun PolicyVisualShellAccess() = Shell { Editor(ShellDemo.state(ShellDemo.editor(page = VisualSection.ACCESS))) }

/** Typed so in JSON that it does not read: the issue in words, the way to its line, the undo. */
@PreviewTest @ShellPhoneAndTablet @Composable
fun PolicyVisualShellUnparsable() = Shell {
    Editor(ShellDemo.state(ShellDemo.editor(PolicyVisualDemo.text.replace("\"tag:dns:4000\"],", "\"tag:dns:4000\"},"), page = VisualSection.ACCESS)))
}

/** A credential that may only read: no undo, no Review; the banner says why. */
@PreviewTest @ShellPhoneAndTablet @Composable
fun PolicyVisualShellReadOnly() = Shell(dark = false) {
    Editor(ShellDemo.state(ShellDemo.editor(page = VisualSection.NETWORK), readOnly = true))
}

/** "acls" written twice: the pages only show, the banner leads to JSON. */
@PreviewTest @ShellPhone @Composable
fun PolicyVisualShellDuplicate() = Shell {
    Editor(ShellDemo.state(ShellDemo.editor(ShellDemo.network.replace("\n\t\"ssh\": [", "\n\t\"acls\": [],\n\n\t\"ssh\": ["))))
}

/** Back from a refused review: the banner, the marked pages, the card it names outlined. */
@PreviewTest @ShellSizes @Composable
fun PolicyVisualShellRefused() = Shell {
    val text = ShellDemo.network
    val line = text.lines().indexOfFirst { "randomizeClientPort" in it } + 1
    val messages = listOf(
        "line $line: randomizeClientPort: cannot be set while a node attribute sets it",
        "derpMap: region 900: node \"900b\" has no IPv4 address",
    )
    Editor(
        ShellDemo.state(
            ShellDemo.editor(text, page = VisualSection.NETWORK, focus = PolicyPath.of("randomizeClientPort"), refusal = PolicyRefusal(text, messages)),
        ),
    )
}

/** A foldable open like a book: the chips over the list's half, no rail. */
@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun PolicyVisualShellFoldBook() = Shell {
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) {
        Editor(ShellDemo.state(ShellDemo.editor(page = VisualSection.ACCESS)))
    }
}
