package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R

/*
 * Remote management, the line the managed device says on its own screen when a paired device
 * changes something (core/RemoteDevicePrompts.kt): what was changed, in the words the log of
 * the managed page uses (RemoteManagedScreen.kt), so that the screen and the log say one thing
 * and neither shows a person "accept_routes=true".
 */

/**
 * The bridge's [detail] of a change of the kind [op] in words: "accept_routes=true" becomes
 * "accepting subnet routes (on)", an action is called what the page calls it, and a change
 * put back is the setting's name alone — the line's first word says it was undone. A key this
 * app has no words for stays as it came, and so does every detail that is a name already: a
 * node, a file, a share. [text] reads a string resource.
 */
internal fun remoteNoticeWhat(op: String, detail: String, text: (Int) -> String): String {
    val words = RemoteAccessWords
    fun word(name: String, of: Map<String, Int>): String = of[name]?.let(text) ?: name
    return when (op) {
        "setting" -> {
            val change = remoteLogDetail(detail)
            val what = word(change.name, words.changed)
            val value = when (change.value) {
                null -> return what
                "true" -> text(R.string.remote_log_on)
                "false" -> text(R.string.remote_log_off)
                "" -> text(R.string.remote_log_none)
                else -> change.value
            }
            String.format(text(R.string.remote_log_with_note), what, value)
        }
        "action" -> word(remoteLogDetail(detail).name, words.actions)
        "reverted" -> word(remoteLogDetail(detail).name, words.changed)
        else -> detail
    }
}
