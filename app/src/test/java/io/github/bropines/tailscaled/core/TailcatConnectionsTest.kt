package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Why a tailcat connection would not start: the bridge's verdicts, then port clashes. */
class TailcatConnectionsTest {
    // --- Tailcat: why a connection would not start ----------------------------------------------

    private val fine: (String) -> String = { "" }

    private fun conn(id: String, ports: String = "", socks: Int = 0, address: String = "tc1abc") =
        TailcatConnection(id = id, name = "conn-$id", address = address, ports = ports, socks = socks)

    @Test
    fun aConnectionWithAGoodAddressAndFreePortsHasNoProblem() {
        assertNull(TailcatConnections.problemOf(conn("a", ports = "8080:80"), emptyList(), null, fine, fine))
        assertNull("a proxy alone is a connection", TailcatConnections.problemOf(conn("a", socks = 1080), emptyList(), null, fine, fine))
    }

    @Test
    fun theBridgesVerdictsComeFirst() {
        assertEquals(TailcatConnections.Problem.Address(""), TailcatConnections.problemOf(conn("a", ports = "1", address = " "), emptyList(), null, fine, fine))
        assertEquals(TailcatConnections.Problem.Address("bad"), TailcatConnections.problemOf(conn("a", ports = "1"), emptyList(), null, { "bad" }, fine))
        assertEquals(TailcatConnections.Problem.Ports("no port mappings"), TailcatConnections.problemOf(conn("a"), emptyList(), null, fine, { "no port mappings" }))
        assertEquals(TailcatConnections.Problem.Socks, TailcatConnections.problemOf(conn("a", socks = 70_000), emptyList(), null, fine, fine))
    }

    @Test
    fun aProxyOnOneOfItsOwnPortsClashesWithItself() {
        assertEquals(
            TailcatConnections.Problem.Clash(8080, "conn-a"),
            TailcatConnections.problemOf(conn("a", ports = "8080:80", socks = 8080), emptyList(), null, fine, fine)
        )
    }

    @Test
    fun aPortAnotherConnectionHoldsClashesOnlyWhileThatOneRuns() {
        val other = conn("b", ports = "9000:22")
        val mine = conn("a", ports = "9000:80")
        val all = listOf(mine, other)
        assertEquals(TailcatConnections.Problem.Clash(9000, "conn-b"), TailcatConnections.problemOf(mine, all, null, fine, fine))
        assertEquals(TailcatConnections.Problem.Clash(9000, "conn-b"), TailcatConnections.problemOf(mine, all, setOf("b"), fine, fine))
        assertNull(TailcatConnections.problemOf(mine, all, emptySet(), fine, fine))
        assertNull("port 0 is the system's pick and cannot clash", TailcatConnections.problemOf(conn("a", ports = "0:80"), listOf(conn("b", ports = "0:22")), null, fine, fine))
    }
}
