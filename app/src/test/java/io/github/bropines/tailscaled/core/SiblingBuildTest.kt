package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.SiblingBuild.Conflict
import io.github.bropines.tailscaled.core.SiblingBuild.Mode
import io.github.bropines.tailscaled.core.SiblingBuild.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the start warns about when the other build of the app runs, and the plumbing around it. */
class SiblingBuildTest {

    private fun state(
        mode: Mode = Mode.PROXY,
        running: Boolean = true,
        rootTun: Boolean = mode == Mode.ROOT,
        socks: Int = 48115,
        http: Int = 0,
        dns: Int = 1053,
        node: String = "",
    ) = State(running, mode, rootTun, socks, http, dns, node)

    private val noPorts = state(socks = 0, http = 0, dns = 0)

    @Test
    fun `conflict table`() {
        data class Row(val name: String, val mine: State, val theirs: State, val want: Conflict?)
        val rows = listOf(
            Row("sibling stopped: nothing, whatever else matches",
                state(Mode.ROOT, node = "aabbccdd"), state(Mode.ROOT, running = false, node = "aabbccdd"), null),
            Row("same node outranks everything",
                state(Mode.ROOT, node = "aabbccdd"), state(Mode.ROOT, node = "aabbccdd"), Conflict.SAME_NODE),
            Row("same node across modes, no shared port",
                noPorts.copy(mode = Mode.TUN, node = "0badf00d"), noPorts.copy(node = "0badf00d"), Conflict.SAME_NODE),
            Row("empty fingerprints are not equal",
                noPorts, noPorts, null),
            Row("different nodes, root+tailscale0 in both",
                state(Mode.ROOT, node = "11111111"), state(Mode.ROOT, node = "22222222"), Conflict.BOTH_ROOT_TUN),
            Row("root without tailscale0 on one side: only the ports",
                state(Mode.ROOT, rootTun = false), state(Mode.ROOT), Conflict.PORTS),
            Row("root without tailscale0, ports differ",
                state(Mode.ROOT, rootTun = false, socks = 1, dns = 2), state(Mode.ROOT, socks = 3, dns = 4), null),
            Row("both TUN: the VPN slot",
                state(Mode.TUN), state(Mode.TUN), Conflict.VPN_SLOT),
            Row("TUN and proxy, ports differ: nothing",
                state(Mode.TUN, socks = 1, dns = 2), state(Mode.PROXY, socks = 3, dns = 4), null),
            Row("root holds no VPN slot",
                state(Mode.TUN, socks = 1, dns = 2), state(Mode.ROOT, socks = 3, dns = 4), null),
            Row("proxy and proxy on the default ports",
                state(), state(), Conflict.PORTS),
            Row("only the HTTP port equal",
                state(socks = 1, dns = 2, http = 8118), state(socks = 3, dns = 4, http = 8118), Conflict.PORTS),
            Row("my SOCKS5 port is their DNS port",
                state(socks = 5353, dns = 0), state(socks = 1, dns = 5353), Conflict.PORTS),
            Row("zero is a listener switched off, not a port",
                state(socks = 1, http = 0, dns = 0), state(socks = 2, http = 0, dns = 0), null),
        )
        for (r in rows) assertEquals(r.name, r.want, SiblingBuild.siblingConflict(r.mine, r.theirs))
    }

    @Test
    fun `sibling package is the other build`() {
        assertEquals("io.github.bropines.tailscaled.dev", SiblingBuild.siblingPackage("io.github.bropines.tailscaled"))
        assertEquals("io.github.bropines.tailscaled", SiblingBuild.siblingPackage("io.github.bropines.tailscaled.dev"))
    }

    @Test
    fun `sibling socket keeps the user directory`() {
        val rel = "io.github.bropines.tailscaled"
        val dev = "$rel.dev"
        assertEquals("/data/user/0/$dev/files/tailscaled.sock",
            SiblingBuild.siblingSocketPath("/data/user/0/$rel/files", rel, dev))
        assertEquals("/data/user/10/$rel/files/tailscaled.sock",
            SiblingBuild.siblingSocketPath("/data/user/10/$dev/files", dev, rel))
        // The release name is a prefix of the dev name, never a path segment of it.
        assertNull(SiblingBuild.siblingSocketPath("/data/user/0/$dev/files", rel, dev))
    }

    @Test
    fun `row round trip`() {
        val s = State(true, Mode.ROOT, true, 48115, 8118, 1053, "deadbeef")
        val row = SiblingBuild.COLUMNS.zip(s.toRow().map { it.toString() }).toMap()
        assertEquals(s, State.fromRow { row[it] })
    }

    @Test
    fun `rows this build does not understand are refused or cleaned`() {
        val good = SiblingBuild.COLUMNS.zip(state().toRow().map { it.toString() }).toMap()
        assertNull(State.fromRow { (good + (SiblingBuild.COL_SCHEMA to "2"))[it] })
        assertNull(State.fromRow { (good + (SiblingBuild.COL_MODE to "warp"))[it] })
        assertNull(State.fromRow { emptyMap<String, String>()[it] })
        val odd = State.fromRow {
            (good + mapOf(SiblingBuild.COL_SOCKS to "70000", SiblingBuild.COL_NODE to "not-a-hash", SiblingBuild.COL_ROOT_TUN to "1"))[it]
        }!!
        assertEquals(0, odd.socksPort)
        assertEquals("", odd.node)
        assertEquals(false, odd.rootTun) // tailscale0 means nothing outside Root Mode
    }

    @Test
    fun `fingerprint is 8 hex of sha256`() {
        assertEquals(8, SiblingBuild.fingerprint("nTEST123CNTRL").length)
        assertEquals(SiblingBuild.fingerprint("nA"), SiblingBuild.fingerprint("nA"))
        assertEquals("ba7816bf", SiblingBuild.fingerprint("abc"))
        assertEquals("", SiblingBuild.fingerprint(""))
    }

    @Test
    fun `current node id from the state`() {
        val profiles = """{"1a2b":{"ID":"1a2b","Key":"profile-1a2b","NodeID":"nAAA"},""" +
            """"3c4d":{"ID":"3c4d","Key":"profile-3c4d","NodeID":"nBBB"}}"""
        assertEquals("nBBB", SiblingBuild.currentNodeId(mapOf("_current-profile" to "profile-3c4d", "_profiles" to profiles)))
        assertNull(SiblingBuild.currentNodeId(mapOf("_current-profile" to "profile-9999", "_profiles" to profiles)))
        assertNull(SiblingBuild.currentNodeId(mapOf("_profiles" to profiles)))
        assertNull(SiblingBuild.currentNodeId(mapOf("_current-profile" to "profile-1a2b", "_profiles" to "garbage")))
        val unregistered = """{"1a2b":{"ID":"1a2b","Key":"profile-1a2b","NodeID":""}}"""
        assertNull(SiblingBuild.currentNodeId(mapOf("_current-profile" to "profile-1a2b", "_profiles" to unregistered)))
    }
}
