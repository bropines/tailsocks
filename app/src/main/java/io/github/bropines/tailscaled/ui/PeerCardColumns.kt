package io.github.bropines.tailscaled.ui

/**
 * Which of [columns] columns the card at [index] of a peer's details stands in: one after the
 * other, round the columns. Nothing but the card's place in the list decides it — not what is
 * open, not how tall a card has grown — so a card keeps its column, and its place in it, while
 * another opens, and only the cards under the opened one move. A grid that put each card into
 * the shortest column so far did the opposite: every opening re-measured the cards, and a card
 * could change column under the remote. With every card closed the two agree, which is how the
 * details have always looked.
 */
internal fun peerCardColumn(index: Int, columns: Int): Int = index % columns.coerceAtLeast(1)

/** [cards] dealt into [columns] columns by [peerCardColumn], each in the list's order; always that many columns. */
internal fun <T> dealPeerCards(cards: List<T>, columns: Int): List<List<T>> {
    val count = columns.coerceAtLeast(1)
    return List(count) { column -> cards.filterIndexed { index, _ -> peerCardColumn(index, count) == column } }
}
