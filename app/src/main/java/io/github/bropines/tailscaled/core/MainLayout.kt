package io.github.bropines.tailscaled.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Everything the main screen can hold: a block across its width, or a tile that opens something.
 *
 * [id] is what a stored layout names the item by, so it never changes; the ones the old
 * per-block switches had ([MainBlock]) keep theirs. A tile for what a device is not offered
 * ([feature], core/DeviceProfile.kt) is neither drawn nor offered there, but stays in a layout
 * that names it: a backup made on a phone is still that phone's screen when it comes back.
 *
 * Two of them cannot be taken off — the status card, which is what the app is for, and
 * Settings: a screen that can lose the way back to its own editor is a trap.
 */
enum class MainItem(
    val id: String,
    val group: Group,
    val feature: Feature? = null,
    val removable: Boolean = true,
    /** A tile that starts across the row; the person can make it one cell, and any other the row. */
    val wideByDefault: Boolean = false,
) {
    STATUS("status", Group.BLOCK, removable = false),
    EXIT_NODE("exit_node", Group.BLOCK),
    SUMMARY("summary", Group.BLOCK),
    SPEED_GRAPH("speed_graph", Group.BLOCK),
    TIMELINE("connection_timeline", Group.BLOCK),

    CONSOLE("tile_console", Group.SCREEN, Feature.CONSOLE),
    PEERS("tile_peers", Group.SCREEN),
    LOGS("tile_logs", Group.SCREEN, Feature.LOGS),
    FILES("tile_files", Group.SCREEN),
    DNS("tile_dns", Group.SCREEN, Feature.DNS_SCREEN),
    NETCHECK("tile_netcheck", Group.SCREEN),
    SETTINGS("tile_settings", Group.SCREEN, removable = false),
    SERVE("tile_serve", Group.SCREEN, Feature.SERVE_EDIT),
    TAILNET_MAP("tailnet_map", Group.SCREEN, wideByDefault = true),
    TAILCAT("tile_tailcat", Group.SCREEN, Feature.TAILCAT),
    TAILDRIVE("tile_taildrive", Group.SCREEN),
    ADMIN("tile_admin", Group.SCREEN, Feature.ADMIN_CONSOLE),

    EXIT_PICKER("tile_exit_picker", Group.SHORTCUT),
    SCAN("tile_scan", Group.SHORTCUT, Feature.QR_SCANNER),
    ACCOUNTS("tile_accounts", Group.SHORTCUT),

    REMOTE("tile_remote", Group.SETTING, wideByDefault = true),
    APP_SPLIT("tile_app_split", Group.SETTING),
    DNS_SETTINGS("tile_dns_settings", Group.SETTING),
    ROOT("tile_root", Group.SETTING, Feature.ROOT_MODE),
    PERMISSIONS("tile_permissions", Group.SETTING),
    TAILNET_LOCK("tile_tailnet_lock", Group.SETTING);

    /** How the list of what can be added is divided. */
    enum class Group { BLOCK, SCREEN, SHORTCUT, SETTING }

    val isTile: Boolean get() = group != Group.BLOCK

    companion object {
        fun byId(id: String): MainItem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The main screen as the person arranged it: what is on it, in order, and how many columns
 * its tiles stand in.
 *
 * One flow, top to bottom. A block takes the width; tiles that follow one another form a grid
 * of [columns], and a [Placed.wide] tile takes a row of that grid to itself. So the status card
 * can stand above the tiles, under them or between two groups of them. A window that shows the
 * status and the menu side by side reads the same list twice: the blocks in their order on one
 * side, the tiles in theirs on the other.
 *
 * Stored as one JSON string under [MainLayoutStore.KEY]:
 *
 *     {"v":1,"columns":0,"items":[{"id":"status","wide":false},{"id":"tailnet_map","wide":true}]}
 *
 * An id this build does not know — a later version's — is dropped on reading. An item this
 * build knows and the string does not name is simply not on the screen: it waits in the list of
 * what can be added, and never turns up in the middle of an arrangement by itself.
 */
data class MainLayout(val items: List<Placed>, val columns: Int = AUTO) {

    data class Placed(val item: MainItem, val wide: Boolean = item.wideByDefault) {
        /** Across the width: every block, and a tile set to. */
        val full: Boolean get() = !item.isTile || wide
    }

    operator fun contains(item: MainItem): Boolean = items.any { it.item == item }

    /** What of it [offered] leaves this device, in order. */
    fun shown(offered: (MainItem) -> Boolean): List<Placed> = items.filter { offered(it.item) }

    /** What is not on the screen and could be, in the order the items are declared. */
    fun addable(offered: (MainItem) -> Boolean): List<MainItem> = MainItem.entries.filter { it !in this && offered(it) }

    /**
     * [item] at the end of the tiles, or — a block — under the last block there is: beside the
     * status, where the blocks start out, wherever the person has put that.
     */
    fun add(item: MainItem): MainLayout {
        if (item in this) return this
        val placed = Placed(item)
        if (item.isTile) return copy(items = items + placed)
        val after = items.indexOfLast { !it.item.isTile }
        return copy(items = items.toMutableList().apply { add(after + 1, placed) })
    }

    /** Without [item]; the two that cannot be taken off stay. */
    fun remove(item: MainItem): MainLayout =
        if (!item.removable) this else copy(items = items.filter { it.item != item })

    /**
     * [item] where [target] is, the rest closing up behind it: dragged down it lands after
     * [target], dragged up before it — what a finger crossing [target] means.
     */
    fun move(item: MainItem, target: MainItem): MainLayout {
        val from = items.indexOfFirst { it.item == item }
        val to = items.indexOfFirst { it.item == target }
        if (from < 0 || to < 0 || from == to) return this
        return copy(items = items.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** [item] one place earlier ([by] -1) or later (+1) among what [offered] leaves on this device. */
    fun step(item: MainItem, by: Int, offered: (MainItem) -> Boolean): MainLayout {
        val visible = shown(offered)
        val target = visible.getOrNull(visible.indexOfFirst { it.item == item } + by) ?: return this
        return move(item, target.item)
    }

    /** A tile across the row, or back to one cell; a block has no size to set. */
    fun setWide(item: MainItem, wide: Boolean): MainLayout =
        if (!item.isTile) this else copy(items = items.map { if (it.item == item) it.copy(wide = wide) else it })

    fun withColumns(columns: Int): MainLayout = copy(columns = columns.takeIf { it in 1..MAX_COLUMNS } ?: AUTO)

    fun encode(): String = AppJson.encodeToString(
        Stored(v = VERSION, columns = columns, items = items.map { StoredItem(it.item.id, it.wide) })
    )

    companion object {
        /** [columns]: as many as the width holds, by the rule the screen always had. */
        const val AUTO = 0
        const val MAX_COLUMNS = 4
        private const val VERSION = 1

        /**
         * The layout [json] holds, or null when it holds none: an empty string, something
         * that is not a layout. What it names twice is taken once, and the two items that
         * cannot be taken off are put back if a hand-edited or damaged string lost them.
         */
        fun decode(json: String?): MainLayout? {
            if (json.isNullOrBlank()) return null
            val stored = runCatching { AppJson.decodeFromString<Stored>(json) }.getOrNull() ?: return null
            val items = stored.items
                .mapNotNull { s -> MainItem.byId(s.id)?.let { Placed(it, s.wide && it.isTile) } }
                .distinctBy { it.item }
                .toMutableList()
            if (items.none { it.item == MainItem.STATUS }) items.add(0, Placed(MainItem.STATUS))
            if (items.none { it.item == MainItem.SETTINGS }) items.add(Placed(MainItem.SETTINGS))
            return MainLayout(items, stored.columns.takeIf { it in 1..MAX_COLUMNS } ?: AUTO)
        }

        /**
         * The screen as it was before it could be arranged, for whoever has arranged nothing:
         * the order it always had, without what the old switches took off it ([shown] answers
         * for each of those, [summary] for the connection summary's own switch). [remoteTile]:
         * a device that keeps remote management on its home screen — a television — has its
         * tile; anyone else can add it.
         */
        fun legacy(shown: (MainBlock) -> Boolean, summary: Boolean, remoteTile: Boolean): MainLayout {
            val items = buildList {
                if (shown(MainBlock.EXIT_NODE)) add(MainItem.EXIT_NODE)
                add(MainItem.STATUS)
                if (summary) add(MainItem.SUMMARY)
                if (shown(MainBlock.SPEED_GRAPH)) add(MainItem.SPEED_GRAPH)
                if (shown(MainBlock.CONNECTION_TIMELINE)) add(MainItem.TIMELINE)
                if (shown(MainBlock.CONSOLE)) add(MainItem.CONSOLE)
                if (shown(MainBlock.PEERS)) add(MainItem.PEERS)
                if (shown(MainBlock.LOGS)) add(MainItem.LOGS)
                if (shown(MainBlock.FILES)) add(MainItem.FILES)
                if (shown(MainBlock.DNS)) add(MainItem.DNS)
                if (shown(MainBlock.NETCHECK)) add(MainItem.NETCHECK)
                add(MainItem.SETTINGS)
                if (shown(MainBlock.SERVE)) add(MainItem.SERVE)
                if (remoteTile) add(MainItem.REMOTE)
                if (shown(MainBlock.TAILNET_MAP)) add(MainItem.TAILNET_MAP)
            }
            return MainLayout(items.map { Placed(it) })
        }

        /** What a fresh install shows. */
        fun default(remoteTile: Boolean): MainLayout = legacy({ it.shownByDefault }, summary = true, remoteTile = remoteTile)

        /**
         * The rows [cells] make in [columns]: tiles side by side until a row is full or
         * something that takes the width comes, which is a row by itself.
         */
        fun rows(cells: List<Placed>, columns: Int): List<List<Placed>> {
            val rows = mutableListOf<List<Placed>>()
            var row = mutableListOf<Placed>()
            for (cell in cells) {
                if (cell.full) {
                    if (row.isNotEmpty()) rows += row
                    rows += listOf(cell)
                    row = mutableListOf()
                } else {
                    row += cell
                    if (row.size == columns.coerceAtLeast(1)) {
                        rows += row
                        row = mutableListOf()
                    }
                }
            }
            if (row.isNotEmpty()) rows += row
            return rows
        }
    }

    @Serializable
    private data class Stored(val v: Int = VERSION, val columns: Int = AUTO, val items: List<StoredItem> = emptyList())

    @Serializable
    private data class StoredItem(val id: String = "", val wide: Boolean = false)
}

/** Where the layout is kept: one global preference, carried by backups ([GlobalSettings.EXPORTED_KEYS]). */
object MainLayoutStore {
    const val KEY = "main_layout"

    /** Whether the person has arranged the screen; until then it follows the old switches. */
    fun isCustom(context: Context): Boolean = MainLayout.decode(GlobalSettings.getString(context, KEY, "")) != null

    /**
     * The layout to draw. [arranged] is false where the screen is not the person's to
     * arrange — a television, which has no editor: an arrangement that came with a phone's
     * backup is left in the preference and not followed.
     */
    fun load(context: Context, remoteTile: Boolean, arranged: Boolean = true): MainLayout =
        (if (arranged) MainLayout.decode(GlobalSettings.getString(context, KEY, "")) else null)
            ?: MainLayout.legacy({ it.isShown(context) }, GlobalSettings.isConnectionSummaryShown(context), remoteTile)

    fun save(context: Context, layout: MainLayout) = GlobalSettings.setString(context, KEY, layout.encode())

    /**
     * Back to what a fresh install shows. The old switches are put back too: they are what
     * an unarranged screen is read from, and one that was off would come back with it.
     */
    fun reset(context: Context) {
        GlobalSettings.setString(context, KEY, "")
        MainBlock.entries.forEach { it.setShown(context, it.shownByDefault) }
        GlobalSettings.setConnectionSummaryShown(context, true)
    }

    /**
     * The connection summary's switch in Settings → Appearance, which is older than the
     * editor and keeps working beside it: its own preference for a screen nobody arranged,
     * and the arrangement itself once there is one.
     */
    fun setSummaryShown(context: Context, remoteTile: Boolean, shown: Boolean) {
        GlobalSettings.setConnectionSummaryShown(context, shown)
        if (!isCustom(context)) return
        val layout = load(context, remoteTile)
        save(context, if (shown) layout.add(MainItem.SUMMARY) else layout.remove(MainItem.SUMMARY))
    }
}
