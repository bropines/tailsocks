package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.CardState
import io.github.bropines.tailscaled.core.ExitNodeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widgets' decisions, taken outside their composition (WidgetModels.kt). */
class WidgetModelsTest {

    // --- The pending toggle ---

    @Test
    fun pendingHoldsUntilRealityMatches() {
        val starting = PendingToggle(target = true, until = 1_000)
        assertEquals(starting, nextPending(starting, running = false, now = 500))
        assertNull(nextPending(starting, running = true, now = 500))
    }

    @Test
    fun pendingEndsAtItsDeadline() {
        val stopping = PendingToggle(target = false, until = 1_000)
        assertEquals(stopping, nextPending(stopping, running = true, now = 999))
        assertNull(nextPending(stopping, running = true, now = 1_000))
        assertNull(nextPending(null, running = true, now = 0))
    }

    @Test
    fun toggleActsOnWhatIsHappening() {
        assertTrue(toggleTarget(running = false, pending = null))
        assertFalse(toggleTarget(running = true, pending = null))
        // A start under way counts as on: the second tap stops it.
        assertFalse(toggleTarget(running = false, pending = PendingToggle(true, 0)))
        assertTrue(toggleTarget(running = true, pending = PendingToggle(false, 0)))
    }

    // --- The status line ---

    @Test
    fun pendingMoveWinsOverEverything() {
        assertEquals(WidgetStatus.STARTING, widgetStatus(false, PendingToggle(true, 0), null, ""))
        assertEquals(WidgetStatus.STOPPING, widgetStatus(true, PendingToggle(false, 0), CardState.CONNECTED, "Running"))
    }

    @Test
    fun serviceMovesHoldWhileTheDaemonIsDown() {
        assertEquals(WidgetStatus.STOPPING, widgetStatus(false, null, CardState.STOPPING, ""))
        assertEquals(WidgetStatus.RECONNECTING, widgetStatus(false, null, CardState.RECONNECTING, ""))
        assertEquals(WidgetStatus.CONNECTION_LOST, widgetStatus(false, null, CardState.CONNECTION_LOST, ""))
    }

    @Test
    fun daemonStatesNeedARunningDaemon() {
        assertEquals(WidgetStatus.STOPPED, widgetStatus(false, null, CardState.CONNECTED, ""))
        assertEquals(WidgetStatus.STOPPED, widgetStatus(false, null, null, ""))
        assertEquals(WidgetStatus.CONNECTED, widgetStatus(true, null, CardState.CONNECTED, ""))
        assertEquals(WidgetStatus.NEEDS_LOGIN, widgetStatus(true, null, CardState.NEEDS_LOGIN, ""))
        assertEquals(WidgetStatus.DEGRADED, widgetStatus(true, null, CardState.DEGRADED, ""))
    }

    @Test
    fun withoutACardTheBackendStateDecides() {
        assertEquals(WidgetStatus.CONNECTED, widgetStatus(true, null, null, "Running"))
        assertEquals(WidgetStatus.NEEDS_LOGIN, widgetStatus(true, null, null, "NeedsLogin"))
        assertEquals(WidgetStatus.AWAITING_APPROVAL, widgetStatus(true, null, null, "NeedsMachineAuth"))
        assertEquals(WidgetStatus.CONNECTING, widgetStatus(true, null, null, "Starting"))
        // A Root Mode daemon this process is not attached to: alive, nothing more known.
        assertEquals(WidgetStatus.RUNNING, widgetStatus(true, null, null, ""))
    }

    // --- The exit nodes ---

    private val a = ExitNodeOption("idA", "100.64.0.1", "alpha")
    private val b = ExitNodeOption("idB", "100.64.0.2", "Bravo")
    private val c = ExitNodeOption("idC", "100.64.0.3", "charlie")

    @Test
    fun liveListReplacesTheCache() {
        assertEquals(listOf(b), exitNodeOptions(listOf(b), listOf(a, c), "idA", "100.64.0.1"))
        // An empty live list means the tailnet has none: the cache does not come back.
        assertEquals(emptyList<ExitNodeOption>(), exitNodeOptions(emptyList(), listOf(a), "", ""))
    }

    @Test
    fun cacheThenLastNodeWhenTheDaemonIsSilent() {
        assertEquals(listOf(a, c), exitNodeOptions(null, listOf(a, c), "", ""))
        assertEquals(listOf(ExitNodeOption("idL", "100.64.0.9", "100.64.0.9")), exitNodeOptions(null, emptyList(), "idL", "100.64.0.9"))
        // A last node without its ID cannot be selected, so it is not offered.
        assertEquals(emptyList<ExitNodeOption>(), exitNodeOptions(null, emptyList(), "", "100.64.0.9"))
    }

    @Test
    fun rowsSelectedFirstThenOnlineThenByName() {
        val rows = exitRows(listOf(c, b, a), mapOf("idA" to false, "idB" to false, "idC" to true), selectedId = "idB", selectedIp = "100.64.0.2")
        assertEquals(listOf("idB", "idC", "idA"), rows.map { it.id })
        assertTrue(rows[0].selected)
        assertEquals(1, rows.count { it.selected })
        assertEquals(false, rows[2].online)
    }

    @Test
    fun namesSortWithoutRegardToCase() {
        val rows = exitRows(listOf(c, b, a), emptyMap(), "", "")
        assertEquals(listOf("alpha", "Bravo", "charlie"), rows.map { it.name })
        assertNull(rows[0].online)
    }

    @Test
    fun selectedByIdBeforeAddress() {
        // Another node now holds the stored address: the ID decides.
        val moved = ExitNodeOption("idX", "100.64.0.2", "xray")
        val rows = exitRows(listOf(moved, b), emptyMap(), selectedId = "idB", selectedIp = "100.64.0.2")
        assertEquals(listOf("idB"), rows.filter { it.selected }.map { it.id })
        // With no ID stored, the address is all there is.
        assertEquals(listOf("idB"), exitRows(listOf(a, b), emptyMap(), "", "100.64.0.2").filter { it.selected }.map { it.id })
    }

    @Test
    fun unknownSelectedNodeStillGetsItsRow() {
        val rows = exitRows(listOf(a), emptyMap(), selectedId = "idGone", selectedIp = "100.64.0.7")
        assertEquals(ExitRow("idGone", "100.64.0.7", "100.64.0.7", selected = true), rows.first())
        assertEquals("100.64.0.7", selectedExitName(rows, "100.64.0.7"))
    }

    @Test
    fun entriesWithoutAnIdAreNeverListed() {
        val rows = exitRows(listOf(ExitNodeOption("", "100.64.0.5", "ghost"), a, a.copy(name = "dup")), emptyMap(), "", "")
        assertEquals(listOf("idA"), rows.map { it.id })
    }

    @Test
    fun rowsAreCapped() {
        val many = (1..20).map { ExitNodeOption("id$it", "100.64.1.$it", "node%02d".format(it)) }
        val rows = exitRows(many, emptyMap(), selectedId = "id17", selectedIp = "100.64.1.17")
        assertEquals(EXIT_ROWS_MAX, rows.size)
        assertEquals("id17", rows.first().id)
        assertEquals(3, exitRows(many, emptyMap(), "", "", limit = 3).size)
    }

    @Test
    fun selectedNameComesFromTheRow() {
        val rows = exitRows(listOf(a, b), emptyMap(), "idB", "100.64.0.2")
        assertEquals("Bravo", selectedExitName(rows, "100.64.0.2"))
        assertEquals("", selectedExitName(exitRows(listOf(a), emptyMap(), "", ""), ""))
    }

    @Test
    fun firstIpv4SkipsIpv6() {
        assertEquals("100.101.102.103", firstIpv4("fd7a:115c:a1e0::1,100.101.102.103"))
        assertEquals("100.1.2.3", firstIpv4(" 100.1.2.3 , fd7a::2"))
        assertEquals("", firstIpv4("fd7a::1"))
        assertEquals("", firstIpv4(""))
    }

    // --- The fingerprint ---

    private fun service(status: WidgetStatus = WidgetStatus.CONNECTED, text: String = "Connected") = ServiceWidgetModel(
        on = true, status = status, statusText = text, profile = "Home", exitLabel = "Exit: alpha",
        tailnetIp = "100.64.0.10", toggleLabel = "Stop", refreshLabel = "Refresh", copyLabel = "Copy", locale = "en"
    )

    @Test
    fun equalModelsGiveEqualFingerprints() {
        assertEquals(encodeModel(service()), encodeModel(service()))
        assertNotEquals(encodeModel(service()), encodeModel(service(WidgetStatus.CONNECTING, "Connecting…")))
        // The same state in another language is another picture.
        assertNotEquals(encodeModel(service()), encodeModel(service(text = "Подключено")))
    }

    @Test
    fun modelsSurviveTheirStore() {
        assertEquals(service(), decodeServiceModel(encodeModel(service())))
        val exit = ExitWidgetModel(title = "Exit node", subtitle = "Not active", rows = exitRows(listOf(a, b), emptyMap(), "", ""))
        assertEquals(exit, decodeExitModel(encodeModel(exit)))
    }

    @Test
    fun anotherBuildsModelIsRebuilt() {
        assertNull(decodeServiceModel(null))
        assertNull(decodeServiceModel("not json"))
        assertNull(decodeServiceModel(encodeModel(service().copy(schema = WIDGET_SCHEMA + 1))))
        assertNull(decodeExitModel(encodeModel(ExitWidgetModel(schema = 0))))
    }
}
