package io.github.bropines.tailscaled.ui

import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R

/*
 * Remote management, one line of "recent remote changes" in words: what a paired device did,
 * and to what. The managed page's log draws it (RemoteManagedScreen.kt), and the line the
 * device says on its own screen at the moment of a change is the same one
 * (core/RemoteDevicePrompts.kt): the two cannot say different things, and neither shows a
 * person "accept_routes=true". The words are RemoteAccessWords'; RemoteLogLineTest holds
 * every name the bridge can put in a line to them, in both languages.
 */

/** The log's lines that are something asked of this device, which can fail. */
private val CAN_FAIL = setOf(
    "exit_node", "setting", "action", "file_deleted", "reverted", "share_added", "share_removed", "file_sent", "permission", "identify",
)

/**
 * The line for [op] with the bridge's [detail], and whether it was done ([ok]): what happened
 * and to what, in this app's words where it has them. A name this app has no words for is
 * shown as the bridge names it. [text] reads a string resource.
 */
internal fun remoteEventLine(op: String, detail: String, ok: Boolean, text: (Int) -> String): String {
    val words = RemoteAccessWords
    val parts = remoteLogDetail(detail)

    fun word(name: String, of: Map<String, Int>): String = of[name]?.let(text) ?: name
    fun filled(@StringRes pattern: Int, vararg args: String): String = String.format(text(pattern), *args)
    fun titled(@StringRes title: Int, what: String): String = if (what.isEmpty()) text(title) else "${text(title)}: $what"
    fun noted(line: String, note: String?): String = if (note.isNullOrEmpty()) line else filled(R.string.remote_log_with_note, line, note)

    // The bridge's remark after a line: in words for the ones this app knows, as it came otherwise.
    fun remarked(line: String, remark: String): String = when {
        remark.isEmpty() -> line
        remark in words.remarks -> noted(line, word(remark, words.remarks))
        else -> "$line $remark"
    }

    // A node's, a file's or a share's name, or a refusal's reason: the detail is the bridge's
    // and is never taken apart — a name may hold any sign. Only a remark of the bridge's own
    // at its end is taken off it and said in words.
    fun named(@StringRes title: Int): String {
        val remark = words.remarks.keys.firstOrNull { detail.endsWith(" $it") } ?: return titled(title, detail)
        return remarked(titled(title, detail.removeSuffix(" $remark")), remark)
    }

    // "accept_routes=true" as "accepting subnet routes (on)"; the exit node, which the log names without a value, alone.
    fun changed(): String {
        val what = word(parts.name, words.changed)
        val value = when (parts.value) {
            null -> return what
            "true" -> text(R.string.remote_log_on)
            "false" -> text(R.string.remote_log_off)
            "" -> text(R.string.remote_log_none)
            else -> parts.value
        }
        return noted(what, value)
    }

    // A change the device put back by itself: because nobody confirmed it, in the bridge's two wordings of that, or in its own words otherwise.
    fun reverted(): String {
        val what = word(parts.name, words.changed)
        return when (parts.value) {
            "not confirmed" -> filled(R.string.remote_log_reverted_unconfirmed, what)
            "not confirmed; the one before is gone, so none" -> text(R.string.remote_log_reverted_none)
            null, "" -> filled(R.string.remote_log_reverted, what)
            else -> filled(R.string.remote_log_reverted, "$what: ${parts.value}")
        }
    }

    val line = when (op) {
        "paired" -> text(R.string.remote_op_paired)
        "pair_failed" -> text(R.string.remote_op_pair_failed)
        "pair_locked" -> text(R.string.remote_op_pair_locked)
        "unpaired" -> text(R.string.remote_op_unpaired)
        "revoked" -> text(R.string.remote_op_revoked)
        "revoked_all" -> text(R.string.remote_op_revoked_all)
        "expired" -> text(R.string.remote_op_expired)
        "reset" -> text(R.string.remote_op_reset)
        "browse" -> text(R.string.remote_log_browse)
        "exit_node" -> named(R.string.remote_op_exit_node)
        "file_deleted" -> named(R.string.remote_op_file_deleted)
        "denied" -> named(R.string.remote_op_denied)
        "share_added" -> named(R.string.remote_log_share_added)
        "share_removed" -> named(R.string.remote_log_share_removed)
        "file_sent" -> named(R.string.remote_log_file_sent)
        // A key or an id, which has words here, and the bridge's remark after it.
        "setting" -> remarked(titled(R.string.remote_op_setting, changed()), parts.note)
        "action" -> remarked(titled(R.string.remote_op_action, word(parts.name, words.actions)), parts.note)
        "confirmed" -> remarked(filled(R.string.remote_log_confirmed, changed()), parts.note)
        "reverted" -> remarked(reverted(), parts.note)
        "permission" -> remarked(
            filled(R.string.remote_log_permission, noted(word(parts.name, words.permissions), parts.value?.let { word(it, words.results) })),
            parts.note
        )
        "identify" -> remarked(
            noted(text(R.string.remote_log_identify), parts.name.takeIf { it.isNotEmpty() }?.let { word(it, words.results) }),
            parts.note
        )
        "diag" -> when (detail) {
            "log" -> text(R.string.remote_log_diag_log)
            "report" -> text(R.string.remote_log_diag_report)
            else -> titled(R.string.remote_log_diag, detail)
        }
        else -> op
    }
    return if (op in CAN_FAIL && !ok) "$line — ${text(R.string.remote_log_failed)}" else line
}
