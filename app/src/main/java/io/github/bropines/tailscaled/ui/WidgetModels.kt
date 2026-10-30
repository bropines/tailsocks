package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.CardState
import io.github.bropines.tailscaled.core.ExitNodeOption
import kotlinx.serialization.Serializable

// What the widgets draw, decided outside their composition. Each widget renders one model,
// stored as a string in its Glance state: every word already in the app's language, every
// decision taken. The serialized model is also the fingerprint: a refresh repaints an
// instance only when the string changed. The functions here are pure, for the unit tests.

/** Bumped whenever a model's shape changes: a model stored by another build is then rebuilt, not misread. */
internal const val WIDGET_SCHEMA = 1

/** How long a tapped Start/Stop shows as Starting…/Stopping… while the service has not caught up. */
internal const val PENDING_TOGGLE_MS = 15_000L

/** How a status reads at a glance: the colour of its dot and of the round toggle. */
enum class WidgetTone { ON, OFF, BUSY, WARN }

/** What the service widget says about the connection; the words are those of the status card. */
enum class WidgetStatus(val tone: WidgetTone) {
    STOPPED(WidgetTone.OFF),
    /** Alive, with nothing more known: a Root Mode daemon this process is not attached to. */
    RUNNING(WidgetTone.ON),
    CONNECTED(WidgetTone.ON),
    CONNECTING(WidgetTone.BUSY),
    STARTING(WidgetTone.BUSY),
    STOPPING(WidgetTone.BUSY),
    RESTARTING(WidgetTone.BUSY),
    RECONNECTING(WidgetTone.BUSY),
    WAITING_FOR_NETWORK(WidgetTone.BUSY),
    DEGRADED(WidgetTone.WARN),
    NEEDS_LOGIN(WidgetTone.WARN),
    AWAITING_APPROVAL(WidgetTone.WARN),
    OS_REFUSED(WidgetTone.WARN),
    CONNECTION_LOST(WidgetTone.WARN),
    ROOT_FAILED(WidgetTone.WARN),
}

/** A Start or Stop tapped on a widget, shown until the service gets there or [until] passes. */
@Serializable
data class PendingToggle(val target: Boolean, val until: Long)

/** The pending toggle still worth showing: none once reality matches it or its time is up. */
internal fun nextPending(stored: PendingToggle?, running: Boolean, now: Long): PendingToggle? =
    stored?.takeIf { it.target != running && now < it.until }

/**
 * What a tap on the toggle asks for: the opposite of what is happening — a start under way
 * counts as on — read when tapped, never what an older render of the widget showed.
 */
internal fun toggleTarget(running: Boolean, pending: PendingToggle?): Boolean = !(pending?.target ?: running)

/**
 * The status line. A tap's pending move wins; then the service's own moves on the card
 * (starting, restarting, stopping…), which hold while the daemon is down; then stopped;
 * then what the card read from the daemon; and with no service holding a card, the backend
 * state as the daemon reports it.
 */
internal fun widgetStatus(running: Boolean, pending: PendingToggle?, card: CardState?, backendState: String): WidgetStatus {
    pending?.let { return if (it.target) WidgetStatus.STARTING else WidgetStatus.STOPPING }
    when (card) {
        CardState.STARTING -> return WidgetStatus.STARTING
        CardState.STOPPING -> return WidgetStatus.STOPPING
        CardState.RESTARTING -> return WidgetStatus.RESTARTING
        CardState.RECONNECTING -> return WidgetStatus.RECONNECTING
        CardState.WAITING_FOR_NETWORK -> return WidgetStatus.WAITING_FOR_NETWORK
        CardState.CONNECTION_LOST -> return WidgetStatus.CONNECTION_LOST
        CardState.ROOT_FAILED -> return WidgetStatus.ROOT_FAILED
        else -> Unit
    }
    if (!running) return WidgetStatus.STOPPED
    return when (card) {
        CardState.CONNECTED -> WidgetStatus.CONNECTED
        CardState.CONNECTING -> WidgetStatus.CONNECTING
        CardState.DEGRADED -> WidgetStatus.DEGRADED
        CardState.NEEDS_LOGIN -> WidgetStatus.NEEDS_LOGIN
        CardState.AWAITING_APPROVAL -> WidgetStatus.AWAITING_APPROVAL
        CardState.OS_REFUSED -> WidgetStatus.OS_REFUSED
        else -> when (backendState) {
            "Running" -> WidgetStatus.CONNECTED
            "NeedsLogin" -> WidgetStatus.NEEDS_LOGIN
            "NeedsMachineAuth" -> WidgetStatus.AWAITING_APPROVAL
            "Starting", "NoState" -> WidgetStatus.CONNECTING
            else -> WidgetStatus.RUNNING
        }
    }
}

/** The exit nodes to list: the daemon's when it answered, else the cache, else the last node in use. */
internal fun exitNodeOptions(live: List<ExitNodeOption>?, cached: List<ExitNodeOption>, lastId: String, lastIp: String): List<ExitNodeOption> =
    live ?: cached.ifEmpty {
        if (lastId.isNotEmpty() && lastIp.isNotEmpty()) listOf(ExitNodeOption(lastId, lastIp, lastIp)) else emptyList()
    }

/** One exit node on the widget. [online] is null when only the cache knows the node. */
@Serializable
data class ExitRow(val id: String, val ip: String, val name: String, val selected: Boolean, val online: Boolean? = null)

/** How many rows a model keeps; the widget shows as many as its size fits. */
internal const val EXIT_ROWS_MAX = 12

/**
 * The rows, selected first, then the online ones, then by name; at most [limit]. The selected
 * node is matched by StableID, by address only while no ID is stored; one that no list knows
 * still gets its row, so the widget never hides where traffic goes. Entries without an ID
 * cannot be selected and are not listed.
 */
internal fun exitRows(
    options: List<ExitNodeOption>,
    online: Map<String, Boolean>,
    selectedId: String,
    selectedIp: String,
    limit: Int = EXIT_ROWS_MAX,
): List<ExitRow> {
    fun isSelected(o: ExitNodeOption) =
        if (selectedId.isNotEmpty()) o.id == selectedId else selectedIp.isNotEmpty() && o.ip == selectedIp
    val rows = options.filter { it.id.isNotEmpty() && it.ip.isNotEmpty() }
        .distinctBy { it.id }
        .map { ExitRow(it.id, it.ip, it.name.ifEmpty { it.ip }, isSelected(it), online[it.id]) }
        .toMutableList()
    if (rows.none { it.selected } && selectedId.isNotEmpty() && selectedIp.isNotEmpty()) {
        rows += ExitRow(selectedId, selectedIp, selectedIp, selected = true)
    }
    return rows.sortedWith(
        compareByDescending<ExitRow> { it.selected }
            .thenByDescending { it.online == true }
            .thenBy { it.name.lowercase() }
            .thenBy { it.ip }
    ).take(limit)
}

/** The selected exit node's name for a label: its row's name, else its address; "" with none selected. */
internal fun selectedExitName(rows: List<ExitRow>, selectedIp: String): String =
    rows.firstOrNull { it.selected }?.name ?: selectedIp

/** The first IPv4 address in the bridge's comma-separated list, or "". */
internal fun firstIpv4(ips: String): String =
    ips.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() && ':' !in it }.orEmpty()

/** The service widget, everything it draws. */
@Serializable
data class ServiceWidgetModel(
    val schema: Int = WIDGET_SCHEMA,
    /** What the toggle shows as happening: running, or the target of a pending tap. */
    val on: Boolean = false,
    val status: WidgetStatus = WidgetStatus.STOPPED,
    val statusText: String = "",
    val profile: String = "",
    /** "Exit: <name>" while an exit node is selected, otherwise "". */
    val exitLabel: String = "",
    /** The node's tailnet IPv4 while connected, otherwise "". */
    val tailnetIp: String = "",
    val toggleLabel: String = "",
    val refreshLabel: String = "",
    val copyLabel: String = "",
    val locale: String = "",
    /** Below Android 12 the colours are resolved here, not by the launcher: the night mode is part of the picture. */
    val night: Boolean? = null,
)

/** The exit-node widget, everything it draws. */
@Serializable
data class ExitWidgetModel(
    val schema: Int = WIDGET_SCHEMA,
    val title: String = "",
    /** "Exit: <name>" or "Not active". */
    val subtitle: String = "",
    val active: Boolean = false,
    val directLabel: String = "",
    /** "Best exit node", or "" while no node is known. */
    val bestLabel: String = "",
    val emptyText: String = "",
    val refreshLabel: String = "",
    val rows: List<ExitRow> = emptyList(),
    val locale: String = "",
    val night: Boolean? = null,
)

internal fun encodeModel(model: ServiceWidgetModel): String = AppJson.encodeToString(ServiceWidgetModel.serializer(), model)
internal fun encodeModel(model: ExitWidgetModel): String = AppJson.encodeToString(ExitWidgetModel.serializer(), model)

/** The stored service model, or null when there is none or another build stored it. */
internal fun decodeServiceModel(encoded: String?): ServiceWidgetModel? =
    encoded?.let { runCatching { AppJson.decodeFromString(ServiceWidgetModel.serializer(), it) }.getOrNull() }
        ?.takeIf { it.schema == WIDGET_SCHEMA }

/** The stored exit-node model, or null when there is none or another build stored it. */
internal fun decodeExitModel(encoded: String?): ExitWidgetModel? =
    encoded?.let { runCatching { AppJson.decodeFromString(ExitWidgetModel.serializer(), it) }.getOrNull() }
        ?.takeIf { it.schema == WIDGET_SCHEMA }
