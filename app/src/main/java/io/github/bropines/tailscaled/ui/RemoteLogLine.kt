package io.github.bropines.tailscaled.ui

import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.RemoteDurationUnit
import io.github.bropines.tailscaled.core.RemoteElevateLengths

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
    "elevate_asked",
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

    // A node's, a file's or a share's name: the detail is the bridge's and is never taken
    // apart — a name may hold any sign. Only a remark of the bridge's own at its end is taken
    // off it and said in words.
    fun named(@StringRes title: Int): String {
        val remark = words.remarks.keys.firstOrNull { detail.endsWith(" $it") } ?: return titled(title, detail)
        return remarked(titled(title, detail.removeSuffix(" $remark")), remark)
    }

    // "accept_routes=true" as "accepting subnet routes (on)"; the exit node, which the log names without a value, alone.
    fun changed(of: RemoteLogDetail = parts): String {
        val what = word(of.name, words.changed)
        val value = when (of.value) {
            null -> return what
            "true" -> text(R.string.remote_log_on)
            "false" -> text(R.string.remote_log_off)
            "" -> text(R.string.remote_log_none)
            else -> of.value
        }
        return noted(what, value)
    }

    // A refusal. The detail is the reason's name from the bridge's list (its remoteDenial) and,
    // after ": ", what the refusal was about: an operation or a setting, which have words
    // here, or a name the other device sent, which is its own and is shown as it came. A
    // reason this app has no words for is shown as the bridge wrote it.
    fun denied(): String {
        val why = detail.substringBefore(": ")
        val about = detail.substringAfter(": ", "")
        // What was asked for, as it reads after "not allowed": "to see the settings", "to change settings".
        fun asked(): String = when (about) {
            in words.seen -> filled(R.string.remote_denied_to_see, word(about, words.seen))
            in words.done -> filled(R.string.remote_denied_to_do, word(about, words.done))
            else -> about
        }
        val reason = when (why) {
            "wrong_token" -> text(R.string.remote_denied_wrong_token)
            "user_changed" -> text(R.string.remote_denied_user_changed)
            "class_lost" -> when (about) {
                "owner" -> text(R.string.remote_denied_class_lost_owner)
                "guest" -> text(R.string.remote_denied_class_lost_guest)
                else -> detail
            }
            "op_needs_full" -> filled(R.string.remote_denied_op_needs_full, asked())
            "op_not_allowed" -> filled(R.string.remote_denied_op_not_allowed, asked())
            "setting_only_at_device" -> filled(R.string.remote_denied_setting_only_at_device, changed(remoteLogDetail(about)))
            "setting_needs_full" -> filled(R.string.remote_denied_setting_needs_full, changed(remoteLogDetail(about)))
            "setting_unknown" -> filled(R.string.remote_denied_setting_unknown, about)
            "action_unknown" -> filled(R.string.remote_denied_action_unknown, about)
            "permission_unknown" -> filled(R.string.remote_denied_permission_unknown, about)
            "file_outside_storage" -> filled(R.string.remote_denied_file_outside_storage, about)
            else -> detail
        }
        return titled(R.string.remote_op_denied, reason)
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

    // A length of full rights, as the bridge logs it in seconds: "1 h", or "until the pairing ends" for 0.
    fun length(seconds: String): String {
        val s = seconds.toLongOrNull() ?: return seconds
        if (s == 0L) return text(R.string.remote_elevate_len_pairing)
        val (count, unit) = RemoteElevateLengths.unit(s)
        val pattern = when (unit) {
            RemoteDurationUnit.DAYS -> R.string.remote_dur_days
            RemoteDurationUnit.HOURS -> R.string.remote_dur_hours
            RemoteDurationUnit.MINUTES -> R.string.remote_dur_minutes
        }
        return String.format(text(pattern), count.toInt())
    }

    // Full rights given for a length, with full control or not; asked for, with what came of the request; ended, and why.
    fun elevated(): String =
        filled(if (parts.value == "full") R.string.remote_log_elevated_full else R.string.remote_log_elevated, length(parts.name))

    fun elevateAsked(): String =
        noted(filled(R.string.remote_log_elevate_asked, length(parts.name)), parts.value?.let { word(it, words.results) })

    fun elevationEnded(): String = words.elevationEnds[detail]?.let(text) ?: titled(R.string.remote_log_elevation_ended, detail)

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
        "denied" -> denied()
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
        "elevated" -> elevated()
        "elevate_asked" -> remarked(elevateAsked(), parts.note)
        "elevation_ended" -> elevationEnded()
        else -> op
    }
    return if (op in CAN_FAIL && !ok) "$line — ${text(R.string.remote_log_failed)}" else line
}
