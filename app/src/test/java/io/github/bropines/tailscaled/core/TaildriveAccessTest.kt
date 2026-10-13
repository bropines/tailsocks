package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.TaildriveAccess.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What the screens make of the bridge's report on Taildrive: three answers, never a guess from "false". */
class TaildriveAccessTest {

    private fun report(json: String) = TaildriveAccess.parse(json)

    @Test
    fun aMapThatGrantsBothIsAllowedBoth() {
        val r = report("""{"known":true,"canShare":true,"canAccess":true}""")
        assertEquals(Verdict.ALLOWED, r.share)
        assertEquals(Verdict.ALLOWED, r.access)
    }

    @Test
    fun theTwoAttributesAreIndependent() {
        val share = report("""{"known":true,"canShare":true,"canAccess":false}""")
        assertEquals(Verdict.ALLOWED, share.share)
        assertEquals(Verdict.DENIED, share.access)
        val access = report("""{"known":true,"canShare":false,"canAccess":true}""")
        assertEquals(Verdict.DENIED, access.share)
        assertEquals(Verdict.ALLOWED, access.access)
    }

    /** The author's second device before the policy was changed: a map, and nothing of Taildrive in it. */
    @Test
    fun aMapWithoutTheAttributesIsDeniedNotUnknown() {
        val r = report("""{"known":true,"canShare":false,"canAccess":false}""")
        assertEquals(Verdict.DENIED, r.share)
        assertEquals(Verdict.DENIED, r.access)
    }

    @Test
    fun noMapYetIsUnknownWhateverTheFlagsSay() {
        val r = report("""{"known":false,"canShare":true,"canAccess":true}""")
        assertEquals(Verdict.UNKNOWN, r.share)
        assertEquals(Verdict.UNKNOWN, r.access)
        assertEquals(Verdict.UNKNOWN, report("{}").share)
    }

    @Test
    fun whatTheBridgeCannotSayIsUnknown() {
        assertSame(TaildriveAccess.UNKNOWN, TaildriveAccess.parse(null))
        assertSame(TaildriveAccess.UNKNOWN, TaildriveAccess.parse(""))
        assertSame(TaildriveAccess.UNKNOWN, TaildriveAccess.parse("not json"))
        assertEquals(Verdict.UNKNOWN, TaildriveAccess.UNKNOWN.share)
        assertEquals(Verdict.UNKNOWN, TaildriveAccess.UNKNOWN.access)
    }

    @Test
    fun unknownFieldsFromANewerBridgeAreIgnored() {
        val r = report("""{"known":true,"canShare":true,"canAccess":false,"canTaildropIt":true}""")
        assertEquals(Verdict.ALLOWED, r.share)
    }

    /** No native library in a JVM test, and no bridge call in an old build: both are "cannot tell". */
    @Test
    fun aBridgeThatIsNotThereIsUnknown() {
        assertEquals(Verdict.UNKNOWN, TaildriveAccess.report().access)
    }
}
