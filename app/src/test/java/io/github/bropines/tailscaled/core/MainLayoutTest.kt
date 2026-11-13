package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.MainItem.*
import io.github.bropines.tailscaled.core.MainLayout.Placed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main screen's arrangement: what an update makes of the old switches, and what editing does to it. */
class MainLayoutTest {
    private fun ids(layout: MainLayout) = layout.items.map { it.item }
    private val everything: (MainItem) -> Boolean = { true }

    // ─── Migration ───────────────────────────────────────────────────────────────────────────

    /** The order the screen had in 5.9.0, with what was on it by default. */
    private val before = listOf(EXIT_NODE, STATUS, SUMMARY, CONSOLE, PEERS, LOGS, FILES, DNS, NETCHECK, SETTINGS, SERVE, TAILNET_MAP)

    /** The same screen with the managed devices where they were drawn, between the blocks and the tiles. */
    private val beforeManaged = before.take(3) + MANAGED + before.drop(3)

    @Test
    fun aScreenNobodyTouchedIsTheScreenItWas() {
        val layout = MainLayout.legacy({ it.shownByDefault }, summary = true, remoteTile = false)
        assertEquals(before, ids(layout))
        assertEquals(MainLayout.AUTO, layout.columns)
        // The map had a row of its own under the cards, and still has.
        assertEquals(listOf(TAILNET_MAP), layout.items.filter { it.wide }.map { it.item })
        assertEquals(MainLayout.legacy({ it.shownByDefault }, summary = true, remoteTile = false, managed = true), MainLayout.default(remoteTile = false))
        assertEquals(beforeManaged, ids(MainLayout.default(remoteTile = false)))
    }

    @Test
    fun aTelevisionKeepsItsRemoteTileAboveTheMap() {
        val layout = MainLayout.default(remoteTile = true)
        assertEquals(beforeManaged.dropLast(1) + REMOTE + TAILNET_MAP, ids(layout))
        assertTrue(layout.items.first { it.item == REMOTE }.wide)
    }

    @Test
    fun whatTheOldSwitchesTookOffStaysOff() {
        val hidden = setOf(MainBlock.EXIT_NODE, MainBlock.CONSOLE, MainBlock.SERVE, MainBlock.TAILNET_MAP)
        val layout = MainLayout.legacy({ it !in hidden && it.shownByDefault }, summary = true, remoteTile = false)
        assertEquals(listOf(STATUS, SUMMARY, PEERS, LOGS, FILES, DNS, NETCHECK, SETTINGS), ids(layout))
    }

    @Test
    fun whatTheOldSwitchesTurnedOnStandsWhereItStood() {
        val layout = MainLayout.legacy({ true }, summary = true, remoteTile = false)
        assertEquals(
            listOf(EXIT_NODE, STATUS, SUMMARY, SPEED_GRAPH, TIMELINE, CONSOLE, PEERS, LOGS, FILES, DNS, NETCHECK, SETTINGS, SERVE, TAILNET_MAP),
            ids(layout)
        )
    }

    @Test
    fun theSummarySwitchIsRead() {
        val layout = MainLayout.legacy({ it.shownByDefault }, summary = false, remoteTile = false)
        assertFalse(SUMMARY in layout)
        assertEquals(before - SUMMARY, ids(layout))
    }

    @Test
    fun everythingSwitchedOffLeavesTheCardAndSettings() {
        val layout = MainLayout.legacy({ false }, summary = false, remoteTile = false)
        assertEquals(listOf(STATUS, SETTINGS), ids(layout))
    }

    @Test
    fun everyOldSwitchHasItsItemUnderTheSameId() {
        for (block in MainBlock.entries) {
            val item = MainItem.byId(block.id)
            assertTrue("no item for ${block.id}", item != null)
            val alone = MainLayout.legacy({ it == block }, summary = false, remoteTile = false)
            assertTrue("${block.id} is not read", item!! in alone)
        }
    }

    // ─── Storage ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aLayoutComesBackAsItWasStored() {
        val layout = MainLayout.default(remoteTile = true)
            .move(STATUS, TAILNET_MAP)
            .setWide(PEERS, true)
            .add(ADMIN)
            .withColumns(3)
        assertEquals(layout, MainLayout.decode(layout.encode()))
    }

    @Test
    fun nothingStoredIsNoLayout() {
        assertNull(MainLayout.decode(null))
        assertNull(MainLayout.decode(""))
        assertNull(MainLayout.decode("   "))
        assertNull(MainLayout.decode("not json"))
        assertNull(MainLayout.decode("[1,2]"))
    }

    @Test
    fun anItemOfALaterVersionIsIgnored() {
        val json = """{"v":7,"columns":2,"glow":true,"items":[
            {"id":"status"},{"id":"tile_from_the_future","wide":true,"colour":"red"},{"id":"tile_peers"},{"id":"tile_settings"}]}"""
        val layout = MainLayout.decode(json)!!
        assertEquals(listOf(STATUS, PEERS, SETTINGS), ids(layout))
        assertEquals(2, layout.columns)
    }

    @Test
    fun whatALayoutDoesNotNameIsNotPutOnTheScreen() {
        val layout = MainLayout.decode("""{"items":[{"id":"tile_settings"},{"id":"status"}]}""")!!
        // Not in the middle, not at the end: only in the list of what can be added.
        assertEquals(listOf(SETTINGS, STATUS), ids(layout))
        assertTrue(layout.addable(everything).containsAll(listOf(PEERS, TAILNET_MAP, SPEED_GRAPH, ADMIN)))
    }

    @Test
    fun aDamagedLayoutGetsBackWhatCannotBeTakenOff() {
        val layout = MainLayout.decode("""{"items":[{"id":"tile_peers"},{"id":"tile_logs"}]}""")!!
        assertEquals(listOf(STATUS, PEERS, LOGS, SETTINGS), ids(layout))
        assertEquals(listOf(STATUS, SETTINGS), ids(MainLayout.decode("""{"items":[]}""")!!))
    }

    @Test
    fun anItemNamedTwiceIsThereOnce() {
        val layout = MainLayout.decode("""{"items":[{"id":"status"},{"id":"tile_peers"},{"id":"tile_settings"},{"id":"tile_peers","wide":true}]}""")!!
        assertEquals(listOf(STATUS, PEERS, SETTINGS), ids(layout))
        assertFalse(layout.items[1].wide)
    }

    @Test
    fun aColumnCountOutOfRangeIsAutomatic() {
        for (columns in listOf(-1, 5, 99)) {
            val layout = MainLayout.decode("""{"columns":$columns,"items":[{"id":"status"},{"id":"tile_settings"}]}""")!!
            assertEquals(MainLayout.AUTO, layout.columns)
        }
        assertEquals(MainLayout.AUTO, MainLayout.default(false).withColumns(7).columns)
        assertEquals(4, MainLayout.default(false).withColumns(4).columns)
        assertEquals(1, MainLayout.default(false).withColumns(1).columns)
    }

    @Test
    fun aBlockIsNeverStoredWide() {
        val layout = MainLayout.decode("""{"items":[{"id":"status","wide":true},{"id":"tile_settings"}]}""")!!
        assertFalse(layout.items[0].wide)
        assertTrue(layout.items[0].full)
    }

    @Test
    fun idsAreUnique() {
        assertEquals(MainItem.entries.size, MainItem.entries.map { it.id }.toSet().size)
    }

    // ─── Guard rails ─────────────────────────────────────────────────────────────────────────

    @Test
    fun theCardAndSettingsCannotBeTakenOff() {
        val layout = MainLayout.default(false)
        assertSame(layout, layout.remove(STATUS))
        assertSame(layout, layout.remove(SETTINGS))
        assertEquals(listOf(STATUS, SETTINGS), MainItem.entries.filter { !it.removable })
    }

    @Test
    fun theCardAndSettingsCanBeMoved() {
        val layout = MainLayout.default(false).move(STATUS, TAILNET_MAP).move(SETTINGS, EXIT_NODE)
        assertEquals(SETTINGS, ids(layout).first())
        assertEquals(STATUS, ids(layout).last())
    }

    @Test
    fun whatADeviceIsNotOfferedIsNeitherShownNorAddable() {
        // A television: no console on its home screen, no admin console anywhere.
        val tv: (MainItem) -> Boolean = { it.feature !in setOf(Feature.CONSOLE, Feature.ADMIN_CONSOLE, Feature.QR_SCANNER) }
        val layout = MainLayout.default(remoteTile = true)
        assertFalse(layout.shown(tv).any { it.item == CONSOLE })
        assertFalse(ADMIN in layout.addable(tv))
        assertFalse(SCAN in layout.addable(tv))
        assertTrue(TAILDRIVE in layout.addable(tv))
        // It stays in the layout all the same: the same string is a phone's screen too.
        assertTrue(CONSOLE in layout)
    }

    // ─── Editing ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun removingAndAddingBack() {
        val layout = MainLayout.default(false).remove(LOGS)
        assertFalse(LOGS in layout)
        assertTrue(LOGS in layout.addable(everything))
        val back = layout.add(LOGS)
        // A tile comes back at the end, to be dragged where it is wanted.
        assertEquals(LOGS, ids(back).last())
        assertSame(back, back.add(LOGS))
    }

    @Test
    fun aNewBlockJoinsTheBlocks() {
        val layout = MainLayout.default(false).add(SPEED_GRAPH)
        assertEquals(listOf(EXIT_NODE, STATUS, SUMMARY, MANAGED, SPEED_GRAPH, CONSOLE), ids(layout).take(6))
        // With the card under the tiles, a block follows it there.
        val below = MainLayout.default(false).move(STATUS, TAILNET_MAP).add(TIMELINE)
        assertEquals(listOf(STATUS, TIMELINE), ids(below).takeLast(2))
    }

    @Test
    fun aTileStartsInItsOwnSize() {
        val layout = MainLayout.default(false).remove(TAILNET_MAP).add(TAILNET_MAP).add(ADMIN)
        assertTrue(layout.items.first { it.item == TAILNET_MAP }.wide)
        assertFalse(layout.items.first { it.item == ADMIN }.wide)
    }

    @Test
    fun movingDownLandsAfterTheTargetAndUpBeforeIt() {
        val layout = MainLayout(listOf(STATUS, CONSOLE, PEERS, LOGS, SETTINGS).map { Placed(it) })
        assertEquals(listOf(STATUS, PEERS, LOGS, CONSOLE, SETTINGS), ids(layout.move(CONSOLE, LOGS)))
        assertEquals(listOf(STATUS, LOGS, CONSOLE, PEERS, SETTINGS), ids(layout.move(LOGS, CONSOLE)))
        assertSame(layout, layout.move(LOGS, LOGS))
        assertSame(layout, layout.move(LOGS, ADMIN))
        assertSame(layout, layout.move(ADMIN, LOGS))
    }

    @Test
    fun aStepSkipsWhatTheDeviceDoesNotShow() {
        val layout = MainLayout(listOf(STATUS, PEERS, CONSOLE, LOGS, SETTINGS).map { Placed(it) })
        val noConsole: (MainItem) -> Boolean = { it != CONSOLE }
        // Past the console it cannot see, to the other side of Logs.
        assertEquals(listOf(STATUS, CONSOLE, LOGS, PEERS, SETTINGS), ids(layout.step(PEERS, +1, noConsole)))
        assertEquals(listOf(PEERS, STATUS, CONSOLE, LOGS, SETTINGS), ids(layout.step(PEERS, -1, noConsole)))
        assertSame(layout, layout.step(STATUS, -1, noConsole))
        assertSame(layout, layout.step(SETTINGS, +1, noConsole))
    }

    @Test
    fun onlyATileHasASize() {
        val layout = MainLayout.default(false)
        assertTrue(layout.setWide(PEERS, true).items.first { it.item == PEERS }.wide)
        assertFalse(layout.setWide(TAILNET_MAP, false).items.first { it.item == TAILNET_MAP }.wide)
        assertSame(layout, layout.setWide(STATUS, true))
    }

    // ─── Rows ────────────────────────────────────────────────────────────────────────────────

    private fun rows(columns: Int, vararg cells: Placed) = MainLayout.rows(cells.toList(), columns).map { row -> row.map { it.item } }

    @Test
    fun tilesFillRowsAndWhatTakesTheWidthIsARow() {
        assertEquals(
            listOf(listOf(STATUS), listOf(CONSOLE, PEERS), listOf(LOGS, FILES), listOf(TAILNET_MAP)),
            rows(2, Placed(STATUS), Placed(CONSOLE), Placed(PEERS), Placed(LOGS), Placed(FILES), Placed(TAILNET_MAP))
        )
    }

    @Test
    fun aBlockBetweenTilesSplitsThemInTwoGroups() {
        assertEquals(
            listOf(listOf(CONSOLE, PEERS, LOGS), listOf(STATUS), listOf(FILES, DNS)),
            rows(3, Placed(CONSOLE), Placed(PEERS), Placed(LOGS), Placed(STATUS), Placed(FILES), Placed(DNS))
        )
        // A wide tile in the middle of a row ends it short.
        assertEquals(
            listOf(listOf(CONSOLE), listOf(PEERS), listOf(LOGS, FILES)),
            rows(2, Placed(CONSOLE), Placed(PEERS, wide = true), Placed(LOGS), Placed(FILES))
        )
    }

    @Test
    fun oneColumnIsAList() {
        assertEquals(listOf(listOf(CONSOLE), listOf(PEERS), listOf(LOGS)), rows(1, Placed(CONSOLE), Placed(PEERS), Placed(LOGS)))
        assertEquals(listOf(listOf(CONSOLE), listOf(PEERS)), rows(0, Placed(CONSOLE), Placed(PEERS)))
    }

    // A backup carries the global preferences on its allow-list, as the strings they are
    // stored as: the layout has to be on that list, and has to survive being a string.
    @Test
    fun `a backup carries the layout`() {
        assertTrue(MainLayoutStore.KEY in GlobalSettings.EXPORTED_KEYS)
        MainBlock.prefKeys.forEach { assertTrue(it, it in GlobalSettings.EXPORTED_KEYS) }
        val arranged = MainLayout.default(remoteTile = false).withColumns(3)
        val restored = MainLayout.decode(arranged.encode())
        assertEquals(arranged.encode(), restored?.encode())
        assertEquals(3, restored?.columns)
    }

    // ─── What is off the screen (Settings → Appearance) ──────────────────────────────────────

    @Test
    fun whatWasTakenOffIsListedAndComesBackWhereThePaletteWouldPutIt() {
        val start = MainLayout.default(remoteTile = false)
        val taken = start.remove(PEERS).remove(SUMMARY)
        val hidden = taken.addable(everything)
        assertTrue(PEERS in hidden && SUMMARY in hidden)
        // Never the two that cannot be taken off, and nothing that is on the screen.
        assertFalse(STATUS in hidden || SETTINGS in hidden || CONSOLE in hidden)
        val back = taken.add(PEERS).add(SUMMARY)
        assertFalse(PEERS in back.addable(everything) || SUMMARY in back.addable(everything))
        // A tile at the end of the tiles, a block under the last block.
        assertEquals(PEERS, ids(back).last())
        assertEquals(ids(taken).indexOfLast { !it.isTile } + 1, ids(back).indexOf(SUMMARY))
    }

    @Test
    fun whatThisDeviceIsNotOfferedIsNotListed() {
        val noAdmin: (MainItem) -> Boolean = { it != ADMIN }
        assertFalse(ADMIN in MainLayout.default(remoteTile = false).addable(noAdmin))
    }

    // ─── The managed devices become an item (layout version 2) ───────────────────────────────

    @Test
    fun anArrangementFromBeforeGetsTheManagedDevicesWhereTheyWereDrawn() {
        val v1 = """{"v":1,"columns":0,"items":[{"id":"status"},{"id":"summary"},{"id":"tile_peers"},{"id":"tile_settings"}]}"""
        assertEquals(listOf(STATUS, SUMMARY, MANAGED, PEERS, SETTINGS), ids(MainLayout.decode(v1, managed = true)!!))
        // Their switch was off: they stay off.
        assertEquals(listOf(STATUS, SUMMARY, PEERS, SETTINGS), ids(MainLayout.decode(v1, managed = false)!!))
        // The card under the tiles: they stood where the tiles met it.
        val under = """{"v":1,"items":[{"id":"tile_peers"},{"id":"tile_settings"},{"id":"status"}]}"""
        assertEquals(listOf(PEERS, SETTINGS, MANAGED, STATUS), ids(MainLayout.decode(under, managed = true)!!))
    }

    @Test
    fun anArrangementOfVersionTwoIsTakenAsItIs() {
        val taken = MainLayout.default(remoteTile = false).remove(MANAGED)
        val back = MainLayout.decode(taken.encode(), managed = true)!!
        assertFalse(MANAGED in back)
        val moved = MainLayout.default(remoteTile = false).move(MANAGED, EXIT_NODE)
        assertEquals(moved, MainLayout.decode(moved.encode(), managed = true))
    }
}
