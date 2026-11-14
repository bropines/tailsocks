package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData

/*
 * Keys for lazy lists. A keyed row keeps its remembered state (interaction source, marquee,
 * TV focus) and its scroll anchor with the item rather than the index when the list is
 * re-sorted or narrowed. But a key that appears twice in one LazyColumn crashes it, and the
 * data here makes no promise of uniqueness — a peer may come without an ID, two share
 * definitions may carry one name — so every list keys through [uniqueKeys].
 */

/**
 * One key per item of [items], in order, each [base] of its item where that is not taken yet;
 * a repeat gets `#2`, `#3`… after it (skipping any that a later or earlier base already is).
 * Strings, so the keys survive in a saved-state Bundle as LazyColumn requires.
 */
internal fun <T> uniqueKeys(items: List<T>, base: (T) -> String): List<String> {
    val used = HashSet<String>(items.size * 2)
    // The bases themselves are reserved first, so a repeat's "#2" cannot take the key that a
    // later item's own base is.
    val bases = items.map(base)
    val firstOf = HashSet<String>(bases)
    return bases.map { b ->
        if (used.add(b)) return@map b
        var n = 2
        var k = "$b#$n"
        while (k in firstOf || !used.add(k)) { n++; k = "$b#$n" }
        k
    }
}

/**
 * A peer row's identity: its StableNodeID, or — for a peer without one, which the status does
 * not rule out — what else tells it apart (node key, first address, host name). The prefixes
 * keep the two kinds from ever meeting.
 */
internal fun peerRowKey(p: PeerData): String =
    p.id?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
        ?: "peer:${p.publicKey.orEmpty()}|${p.getPrimaryIp()}|${p.hostName.orEmpty()}"

/** This device's own row over the peers: no [peerRowKey] has this shape. */
internal const val SELF_ROW_KEY = "self"
