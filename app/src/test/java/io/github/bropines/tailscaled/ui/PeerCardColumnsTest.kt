package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The columns of a peer's details: decided by a card's place in the list, and by nothing that opens or grows. */
class PeerCardColumnsTest {
    private val six = listOf("addresses", "route", "device", "capabilities", "traffic", "timing")

    @Test
    fun twoColumnsTakeTheCardsInTurn() {
        assertEquals(
            listOf(listOf("addresses", "device", "traffic"), listOf("route", "capabilities", "timing")),
            dealPeerCards(six, 2)
        )
    }

    @Test
    fun anOddCardEndsTheFirstColumn() {
        assertEquals(
            listOf(listOf("addresses", "device", "traffic"), listOf("route", "capabilities")),
            dealPeerCards(six.dropLast(1), 2)
        )
    }

    @Test
    fun oneColumnKeepsTheOrder() {
        assertEquals(listOf(six), dealPeerCards(six, 1))
        // Fewer than one column is not a layout: it is read as one.
        assertEquals(listOf(six), dealPeerCards(six, 0))
    }

    @Test
    fun everyColumnIsThereEvenWithoutCards() {
        assertEquals(listOf(emptyList<String>(), emptyList()), dealPeerCards(emptyList<String>(), 2))
        assertEquals(listOf(listOf("addresses"), emptyList()), dealPeerCards(six.take(1), 2))
    }

    @Test
    fun aCardStaysWhereItIsWhateverTheOthersAre() {
        // The column is a function of the index and the column count alone: the card at an index
        // stands in the same column, at the same place in it, in a list of any length — and what
        // the cards hold, open or closed, is not an input at all.
        for (columns in 1..3) {
            for (index in 0..8) {
                val column = peerCardColumn(index, columns)
                for (length in (index + 1)..9) {
                    val dealt = dealPeerCards((0 until length).toList(), columns)
                    assertEquals(index, dealt[column][index / columns])
                }
            }
        }
    }
}
