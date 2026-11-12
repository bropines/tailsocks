package io.github.bropines.tailscaled.core

import android.content.Context

/**
 * The switches the main screen had before it could be arranged (Settings → Appearance →
 * "Main screen blocks", 5.8.0): one boolean per block, on or off.
 *
 * Nothing writes them any more except a reset. They are still read — a screen nobody has
 * arranged yet is built from them ([MainLayout.legacy]), so an update changes nothing a person
 * had set — and still carried by backups, for the builds that know nothing else. The graph and
 * the timeline started hidden, and that is still what a fresh install shows. Settings was never
 * here: a screen that can hide the way back to this list is a trap, and [MainItem.removable]
 * keeps that rule now.
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
