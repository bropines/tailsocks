package io.github.bropines.tailscaled.core

import android.content.Context

/**
 * A piece of the main screen a person can take off it: Settings → Appearance →
 * "Main screen blocks".
 *
 * The screen grew a part at a time and every part is somebody's favourite and
 * somebody else's clutter. The pictures added last — the speed graph and the
 * connection timeline — start hidden: the screen a person already knows, the
 * status and the two columns of cards, looks the same after an update, and what
 * is new is one switch away. Settings itself is not here: a screen that can
 * hide the way back to this list is a trap.
 */
enum class MainBlock(val id: String, val shownByDefault: Boolean, val isTile: Boolean = false) {
    EXIT_NODE("exit_node", true),
    SPEED_GRAPH("speed_graph", false),
    CONNECTION_TIMELINE("connection_timeline", false),
    /** A row of its own under the cards, like the remote-management one: the grid keeps its shape. */
    TAILNET_MAP("tailnet_map", true),
    CONSOLE("tile_console", true, isTile = true),
    PEERS("tile_peers", true, isTile = true),
    LOGS("tile_logs", true, isTile = true),
    FILES("tile_files", true, isTile = true),
    DNS("tile_dns", true, isTile = true),
    NETCHECK("tile_netcheck", true, isTile = true),
    SERVE("tile_serve", true, isTile = true);

    val prefKey: String get() = "main_block_$id"

    fun isShown(context: Context): Boolean = GlobalSettings.getBoolean(context, prefKey, shownByDefault)
    fun setShown(context: Context, shown: Boolean) = GlobalSettings.setBoolean(context, prefKey, shown)

    companion object {
        /** The stored keys, for the backup's list of what it carries. */
        val prefKeys: List<String> get() = entries.map { it.prefKey }

        /** What is on the screen now, read once: a recomposition must not hit the preferences per block. */
        fun shown(context: Context): Set<MainBlock> = entries.filterTo(mutableSetOf()) { it.isShown(context) }
    }
}
