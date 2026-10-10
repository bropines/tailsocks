package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiClientConnectivity
import io.github.bropines.tailscaled.admin.api.ApiDerpLatency
import io.github.bropines.tailscaled.admin.api.ApiDerpMap
import io.github.bropines.tailscaled.admin.api.ApiDerpNode
import io.github.bropines.tailscaled.admin.api.ApiDerpRegion
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyState
import io.github.bropines.tailscaled.admin.policy.visual.ConfirmExclude
import io.github.bropines.tailscaled.admin.policy.visual.DerpFile
import io.github.bropines.tailscaled.admin.policy.visual.Relays
import io.github.bropines.tailscaled.admin.policy.visual.VisualEditorScreen
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The visual policy editor's Relays page over PolicyVisualDemo's policy (Helsinki excluded) and
 * a made-up relay map: loaded, with the devices' home relays and latencies; loading; failed;
 * a Headscale server, which only shows what the file says; the file's own regions; the question
 * before a home relay is excluded. Render this class alone: `--tests '*PolicyVisualRelaysPreviews*'`.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class RelaysSizes

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class RelaysPhone

@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class RelaysPhoneRuAndTablet

object RelaysDemo {
    private fun region(id: Int, code: String, name: String, servers: Int = 3) =
        id.toString() to ApiDerpRegion(id, code, name, List(servers) { ApiDerpNode("$id${'a' + it}", "derp$id${'a' + it}.example.net") })

    /** A relay map in the shape Tailscale serves, its regions invented for the previews. */
    val map = ApiDerpMap(
        listOf(
            region(1, "nyc", "New York City", 4), region(2, "sfo", "San Francisco"), region(3, "sin", "Singapore", 4),
            region(4, "fra", "Frankfurt", 5), region(5, "syd", "Sydney"), region(7, "tok", "Tokyo", 4),
            region(8, "lhr", "London"), region(9, "dfw", "Dallas"), region(14, "ams", "Amsterdam"),
            region(18, "par", "Paris"), region(19, "mad", "Madrid"), region(22, "waw", "Warsaw"),
            region(23, "dbi", "Dubai"), region(26, "nue", "Nuremberg"), region(28, "hel", "Helsinki"),
        ).toMap(),
    )

    private fun reported(home: String, vararg ms: Pair<String, Double>) =
        ApiClientConnectivity(latency = ms.associate { (r, l) -> r to ApiDerpLatency(preferred = r == home, latencyMs = l) })

    /** The demo tailnet, its devices reporting their relays as fields=all brings them. */
    val devices: List<ApiDevice> = PolicyVisualDemo.devices.mapIndexed { i, d ->
        val c = when (i % 4) {
            0 -> reported("Frankfurt", "Frankfurt" to 28.0 + i, "Warsaw" to 39.0, "Helsinki" to 47.0, "Amsterdam" to 41.0, "London" to 52.0)
            1 -> reported("Frankfurt", "Frankfurt" to 22.0 + i, "Nuremberg" to 26.0, "Amsterdam" to 33.0, "Paris" to 37.0)
            2 -> reported("Warsaw", "Warsaw" to 18.0, "Frankfurt" to 34.0, "Helsinki" to 41.0)
            else -> reported("Helsinki", "Helsinki" to 24.0, "Warsaw" to 36.0, "Frankfurt" to 45.0)
        }
        d.copy(clientConnectivity = c)
    }

    val file = PolicyFile(PolicyVisualDemo.text, "\"7c41e0a9d2\"")

    /** The demo policy with a relay of the file's own before the excluded Helsinki, which carries a comment. */
    val own: String = PolicyVisualDemo.text.replace(
        "\t\t\t\"28\": null,\n",
        "\t\t\t\"900\": {\n\t\t\t\t\"RegionID\":   900,\n\t\t\t\t\"RegionCode\": \"home\",\n\t\t\t\t\"RegionName\": \"Home relay\",\n" +
            "\t\t\t\t\"Nodes\": [\n\t\t\t\t\t{\"Name\": \"900a\", \"RegionID\": 900, \"HostName\": \"derp.home.example\"},\n\t\t\t\t],\n\t\t\t},\n" +
            "\t\t\t// Финский ретранслятор медленный из дома.\n\t\t\t\"28\": null,\n",
    )

    fun state(
        derp: Loadable<ApiDerpMap> = Loadable(map, loadedAt = 1),
        text: String = PolicyVisualDemo.text,
        headscale: Boolean = false,
    ): ConsoleState = ConsoleState(
        phase = ConsolePhase.READY,
        caps = if (headscale) Capabilities(BackendKind.HEADSCALE_V2, CredentialKind.HEADSCALE_API_KEY, setOf(BackendFeature.POLICY, BackendFeature.DEVICES)) else null,
        devices = Loadable(if (headscale) PolicyVisualDemo.devices else devices, loadedAt = 1),
        users = Loadable(PolicyVisualDemo.users, loadedAt = 1),
        policy = PolicyState(
            file = Loadable(file, loadedAt = 1),
            editor = PolicyEditorState(base = file, text = text, page = VisualSection.RELAYS, undoStack = if (text != file.text) listOf(file.text) else emptyList()),
            derpMap = derp,
        ),
    )
}

@Composable
private fun RelaysTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Page(state: ConsoleState) = VisualEditorScreen(state, state.policy.editor!!, null) {}

/** Tailscale's map loaded: home relays of the demo devices first, Helsinki excluded, latencies. */
@PreviewTest @RelaysSizes @Composable
fun PolicyVisualRelaysLoaded() = RelaysTheme { Page(RelaysDemo.state()) }

@PreviewTest @RelaysPhoneRuAndTablet @Composable
fun PolicyVisualRelaysLoadedLight() = RelaysTheme(dark = false) { Page(RelaysDemo.state()) }

/** The first visit of a session: the map on its way, the file's exclusion already listed. */
@PreviewTest @RelaysPhone @Composable
fun PolicyVisualRelaysLoading() = RelaysTheme { Page(RelaysDemo.state(Loadable(loading = true))) }

/** The map did not come: why, the retry, and what can still be switched. */
@PreviewTest @RelaysPhone @Composable
fun PolicyVisualRelaysFailed() = RelaysTheme(dark = false) {
    Page(RelaysDemo.state(Loadable(error = AdminApiException.Network("dial tcp controlplane.tailscale.com:443: i/o timeout"))))
}

/** Headscale: its relays come from its own configuration; the file's derpMap only shown. */
@PreviewTest @RelaysPhone @Composable
fun PolicyVisualRelaysHeadscale() = RelaysTheme { Page(RelaysDemo.state(Loadable(), RelaysDemo.own, headscale = true)) }

/** A region of the file's own and a comment over the exclusion: shown as written, the way to JSON. */
@PreviewTest @RelaysPhoneRuAndTablet @Composable
fun PolicyVisualRelaysOwn() = RelaysTheme { Page(RelaysDemo.state(text = RelaysDemo.own)) }

/** Before excluding Frankfurt, home to half the demo devices. */
@PreviewTest @RelaysPhone @Composable
fun PolicyVisualRelaysConfirm() = RelaysTheme {
    val file = DerpFile.read(PolicyVisualDemo.text)!!
    val rows = Relays.rows(file, RelaysDemo.map, RelaysDemo.devices)
    val fra = rows.first { it.id == "4" }
    ConfirmExclude(LocalContext.current, fra, Relays.impact(rows, file, "4"), onConfirm = {}, onDismiss = {})
}
