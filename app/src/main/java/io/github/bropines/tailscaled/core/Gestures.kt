package io.github.bropines.tailscaled.core

import android.content.Context

/**
 * What a swipe on a device of the Peers list does. [id] is what is stored, so it never changes.
 */
enum class PeerSwipe(val id: String) {
    NONE("none"),
    SEND_FILE("send_file"),
    PING("ping"),
    COPY_IP("copy_ip"),
    EXIT_NODE("exit_node"),
    DETAILS("details"),
    COPY_NAME("copy_name"),
    COPY_AS("copy_as"),
    OPEN_WEB("open_web");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** What a swipe on the main screen's status card does. */
enum class StatusSwipe(val id: String) {
    NONE("none"),
    NEXT_ACCOUNT("next_account"),
    EXIT_NODE_TOGGLE("exit_node_toggle"),
    EXIT_NODE_PICKER("exit_node_picker"),
    PREV_ACCOUNT("prev_account"),
    RECONNECT_RELAYS("reconnect_relays"),
    OPEN_PEERS("open_peers"),
    OPEN_FILES("open_files"),
    OPEN_MAP("open_map"),
    OPEN_LOGS("open_logs"),
    OPEN_SETTINGS("open_settings"),
    SCAN_QR("scan_qr");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** What a swipe on a received or saved Taildrop file does. */
enum class FileSwipe(val id: String) {
    NONE("none"),
    HIDE("hide"),
    OPEN("open"),
    SHARE("share"),
    SHOW_IN_FOLDER("show_in_folder"),
    COPY_FOLDER_PATH("copy_folder_path");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Settings → Appearance → Gestures: which gestures the app answers and how.
 *
 * A swipe has two directions, named as the finger moves in a left-to-right layout: "to end"
 * is a swipe towards the end of the line (rightwards), "to start" back towards its start. In a
 * right-to-left layout they follow the reading direction, as Compose's own swipe APIs do.
 * [NONE][PeerSwipe.NONE] in both directions leaves the row as it was, with no drag at all.
 *
 * Global preferences, carried by backups ([GlobalSettings.EXPORTED_KEYS] via [KEYS]).
 */
object Gestures {
    const val KEY_PREDICTIVE_BACK = "gesture_predictive_back"
    const val KEY_PEER_TO_END = "gesture_peer_to_end"
    const val KEY_PEER_TO_START = "gesture_peer_to_start"
    const val KEY_STATUS_TO_END = "gesture_status_to_end"
    const val KEY_STATUS_TO_START = "gesture_status_to_start"
    const val KEY_FILE_TO_END = "gesture_file_to_end"
    const val KEY_FILE_TO_START = "gesture_file_to_start"

    val KEYS = listOf(
        KEY_PREDICTIVE_BACK,
        KEY_PEER_TO_END, KEY_PEER_TO_START,
        KEY_STATUS_TO_END, KEY_STATUS_TO_START,
        KEY_FILE_TO_END, KEY_FILE_TO_START,
    )

    /** The back gesture inside a screen (a settings page, a folder) previews where it leads. */
    fun predictiveBack(context: Context) = GlobalSettings.getBoolean(context, KEY_PREDICTIVE_BACK, true)
    fun setPredictiveBack(context: Context, on: Boolean) = GlobalSettings.setBoolean(context, KEY_PREDICTIVE_BACK, on)

    fun peerToEnd(context: Context) = PeerSwipe.byId(GlobalSettings.getString(context, KEY_PEER_TO_END, "")) ?: PeerSwipe.SEND_FILE
    fun peerToStart(context: Context) = PeerSwipe.byId(GlobalSettings.getString(context, KEY_PEER_TO_START, "")) ?: PeerSwipe.COPY_IP
    fun statusToEnd(context: Context) = StatusSwipe.byId(GlobalSettings.getString(context, KEY_STATUS_TO_END, "")) ?: StatusSwipe.NEXT_ACCOUNT
    fun statusToStart(context: Context) = StatusSwipe.byId(GlobalSettings.getString(context, KEY_STATUS_TO_START, "")) ?: StatusSwipe.EXIT_NODE_PICKER
    fun fileToEnd(context: Context) = FileSwipe.byId(GlobalSettings.getString(context, KEY_FILE_TO_END, "")) ?: FileSwipe.OPEN
    fun fileToStart(context: Context) = FileSwipe.byId(GlobalSettings.getString(context, KEY_FILE_TO_START, "")) ?: FileSwipe.HIDE

    fun set(context: Context, key: String, id: String) = GlobalSettings.setString(context, key, id)
}
