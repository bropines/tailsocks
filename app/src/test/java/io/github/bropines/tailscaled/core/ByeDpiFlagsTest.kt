package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByeDpiFlagsTest {

    private val builtIn = ByeDpiFlags.DEFAULT.split(" ")

    @Test
    fun theBuiltInProfilePassesOurOwnAllowList() {
        val checked = ByeDpiFlags.sanitize(ByeDpiFlags.DEFAULT)
        assertEquals(builtIn, checked.accepted)
        assertEquals(emptyList<String>(), checked.rejected)
    }

    /** `-s 1 -d split -r`, the built-in flags up to 4.10.1, ended with an option
     *  that takes a value and had none. The core refuses that, and the profile
     *  has to parse there or nothing is left to fall back on. */
    @Test
    fun everyOptionOfTheBuiltInProfileCarriesItsValue() {
        for (token in builtIn) {
            assertTrue(token, Regex("-[a-zA-Z].+").matches(token))
        }
    }

    @Test
    fun anEmptyStringMeansTheBuiltInProfile() {
        for (empty in listOf("", "   ", "\n\t")) {
            val choice = ByeDpiFlags.choose(empty)
            assertEquals(builtIn, choice.flags)
            assertTrue(choice.builtIn)
            assertEquals(emptyList<String>(), choice.ignored)
        }
    }

    @Test
    fun theUsersFlagsAreUsedAsTyped() {
        val choice = ByeDpiFlags.choose("  -s1   -d 3+s --tlsrec=1+s -NU ")
        assertEquals(listOf("-s1", "-d", "3+s", "--tlsrec=1+s", "-NU"), choice.flags)
        assertFalse(choice.builtIn)
        assertEquals(emptyList<String>(), choice.ignored)
    }

    @Test
    fun whatTheAllowListDropsIsReportedAndTheRestRuns() {
        val choice = ByeDpiFlags.choose("-s1 -H /sdcard/hosts.txt -i 0.0.0.0 -d1")
        assertEquals(listOf("-s1", "-d1"), choice.flags)
        assertEquals(listOf("-H", "/sdcard/hosts.txt", "-i", "0.0.0.0"), choice.ignored)
        assertFalse(choice.builtIn)
    }

    @Test
    fun withNothingLeftTheBuiltInProfileRuns() {
        val choice = ByeDpiFlags.choose("-i 0.0.0.0 --daemon -w /data/x")
        assertEquals(builtIn, choice.flags)
        assertTrue(choice.builtIn)
        assertEquals(listOf("-i", "0.0.0.0", "--daemon", "-w", "/data/x"), choice.ignored)
    }

    /** The allow-list decides which options may reach the core, not whether their
     *  values make sense: that is the core's parser, and the run falls back when
     *  it says no (ByeDpiRunTest). */
    @Test
    fun theAllowListDoesNotJudgeValues() {
        val choice = ByeDpiFlags.choose("-s 1 -d split -r")
        assertEquals(listOf("-s", "1", "-d", "split", "-r"), choice.flags)
        assertFalse(choice.builtIn)
    }

    @Test
    fun optionsAreResolvedTheWayGetoptDoes() {
        fun rejected(flags: String) = ByeDpiFlags.sanitize(flags).rejected
        // Bundled short options: one forbidden letter drops the token, and its value with it.
        assertEquals(emptyList<String>(), rejected("-NUX"))
        assertEquals(listOf("-ND"), rejected("-ND"))
        assertEquals(listOf("-Ni", "0.0.0.0"), rejected("-Ni 0.0.0.0"))
        assertEquals(listOf("-Ni0.0.0.0"), rejected("-Ni0.0.0.0"))
        // An unknown letter never reaches the core.
        assertEquals(listOf("-N1U"), rejected("-N1U"))
        // Long options, their unambiguous prefixes, and the ambiguous ones.
        assertEquals(emptyList<String>(), rejected("--tlsrec 1+s --tlsr=1+s --disor 1"))
        assertEquals(listOf("--dis", "1"), rejected("--dis 1"))
        assertEquals(listOf("--p", "1"), rejected("--p 1"))
        assertEquals(listOf("--pidfile=/data/x"), rejected("--pidfile=/data/x"))
        // A positional argument: byedpi has none.
        assertEquals(listOf("split"), rejected("-d1 split"))
        // --fake-data reads a file unless the payload is inline.
        assertEquals(emptyList<String>(), rejected("-l :\\x16\\x03 --fake-data=:abc"))
        assertEquals(listOf("-l", "/data/data/x", "--fake-data=/etc/hosts"), rejected("-l /data/data/x --fake-data=/etc/hosts"))
    }

    @Test
    fun theCoreIsToldWhereToListenThenWhatToDo() {
        assertEquals(
            listOf("byedpi", "-i", "socks5://127.9.8.7", "-p", "40123", "-o1", "-a1"),
            ByeDpiFlags.arguments("127.9.8.7", 40123, noIpv6 = false, flags = listOf("-o1", "-a1")).toList()
        )
        assertEquals(
            listOf("byedpi", "-i", "socks5://127.9.8.7", "-p", "40123", "-X", "-d1"),
            ByeDpiFlags.arguments("127.9.8.7", 40123, noIpv6 = true, flags = listOf("-d1")).toList()
        )
    }
}
