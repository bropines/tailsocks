package io.github.bropines.tailscaled.admin.notify

import io.github.bropines.tailscaled.admin.attention.AttentionItem
import io.github.bropines.tailscaled.admin.attention.AttentionKind
import io.github.bropines.tailscaled.admin.attention.AttentionSource

/**
 * The outcome of one background check against what was already notified: the items that are
 * news ([fresh]), the remembered set to store ([seen]), and the remembered items that are gone
 * since ([resolved]) — approved in the console, deleted, renewed — whose notifications can go.
 */
data class NotifyDiff(val fresh: List<AttentionItem>, val seen: Set<String>, val resolved: Set<String>)

/**
 * Each item notifies once. An item is remembered by its [AttentionItem.notifyKey]; a
 * remembered key is forgotten only when its source was read this time and the item was not
 * in it, so a check that could not read the users does not forget them — and then notify
 * every pending user again on the next check that can.
 */
object AttentionNotifyOnce {

    fun diff(seen: Set<String>, current: List<AttentionItem>, read: Set<AttentionSource>): NotifyDiff {
        val notifiable = current.filter { it.kind.notifies && it.kind.source in read }.distinctBy { it.notifyKey }
        val currentKeys = notifiable.mapTo(mutableSetOf()) { it.notifyKey }
        val fresh = notifiable.filter { it.notifyKey !in seen }
        val kept = mutableSetOf<String>()
        val resolved = mutableSetOf<String>()
        for (key in seen) {
            // A key this version cannot place is dropped: it can never be resolved otherwise.
            val kind = kindOf(key) ?: continue
            when {
                kind.source !in read -> kept += key
                key in currentKeys -> kept += key
                else -> resolved += key
            }
        }
        return NotifyDiff(fresh, kept + fresh.map { it.notifyKey }, resolved)
    }

    /** What a person has on screen right now, as the set to start from: none of it is news. */
    fun seed(current: List<AttentionItem>): Set<String> =
        current.filter { it.kind.notifies }.mapTo(mutableSetOf()) { it.notifyKey }

    fun kindOf(key: String): AttentionKind? =
        runCatching { AttentionKind.valueOf(key.substringBefore(':')) }.getOrNull()
}
