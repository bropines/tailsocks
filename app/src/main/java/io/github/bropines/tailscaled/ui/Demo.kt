package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a screen shows instead of asking the daemon, when there is no daemon to
 * ask: in the preview renderer, for README and store screenshots, and to put a
 * screen into one exact state while its layout is being worked on.
 *
 * Only previews provide it. In the app [LocalDemo] is always null and every
 * screen loads its data as it always has. The values are in the daemon's own
 * formats — [statusJson] is what /localapi/v0/status returns, a ping is the
 * JSON the ping call answers — so a demo runs the real parsing and the real
 * rendering, not a stand-in for them.
 *
 * A screen reads it synchronously, into its initial state: the renderer takes
 * its picture before any coroutine it starts could come back.
 */
data class DemoData(
    /** The status document, as /localapi/v0/status returns it. */
    val statusJson: String? = null,
    /** Ping answers by address, as the ping call returns them. */
    val pings: Map<String, String> = emptyMap(),
    /** Whether the service counts as running. */
    val running: Boolean = true,
    /** The exit node in use, if any: its name and its address. */
    val exitNodeName: String? = null,
    val exitNodeIp: String? = null,
    /**
     * The netcheck answer as the bridge returns it — Appctr.getNetcheckFromAPI's
     * {Report, DERPMeta} document; the daemon has no netcheck endpoint of its own.
     */
    val netcheckJson: String? = null,
    /** Log lines, as the log store renders them. */
    val logLines: List<String> = emptyList(),
    /** The daemon's backend state — "Running", "Starting", "NeedsLogin"… */
    val backendState: String = "Running",
    /** Health warnings, as appctr's GetHealthWarningsJSON returns them. */
    val healthJson: String? = null,
)

val LocalDemo = staticCompositionLocalOf<DemoData?> { null }
