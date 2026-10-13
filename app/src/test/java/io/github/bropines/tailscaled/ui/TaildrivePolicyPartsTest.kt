package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TaildriveAccess.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The line the Taildrive screen says about what the policy lets this device do. */
class TaildrivePolicyPartsTest {

    @Test
    fun sharingHasItsOwnSentencePerAnswer() {
        assertEquals(R.string.taildrive_verdict_share_allowed, verdictText(DriveAbility.SHARE, Verdict.ALLOWED))
        assertEquals(R.string.taildrive_verdict_share_denied, verdictText(DriveAbility.SHARE, Verdict.DENIED))
    }

    @Test
    fun openingTheOthersFoldersHasItsOwnToo() {
        assertEquals(R.string.taildrive_verdict_access_allowed, verdictText(DriveAbility.ACCESS, Verdict.ALLOWED))
        assertEquals(R.string.taildrive_verdict_access_denied, verdictText(DriveAbility.ACCESS, Verdict.DENIED))
    }

    /** Before the daemon has a map there is nothing to say but how to find out, for either. */
    @Test
    fun notKnownIsTheSameSentenceForBoth() {
        assertEquals(R.string.taildrive_verdict_unknown, verdictText(DriveAbility.SHARE, Verdict.UNKNOWN))
        assertEquals(R.string.taildrive_verdict_unknown, verdictText(DriveAbility.ACCESS, Verdict.UNKNOWN))
    }

    @Test
    fun onlyARefusalHasSomethingToExplain() {
        assertNotNull(verdictHelp(DriveAbility.SHARE, Verdict.DENIED))
        assertNotNull(verdictHelp(DriveAbility.ACCESS, Verdict.DENIED))
        for (v in listOf(Verdict.ALLOWED, Verdict.UNKNOWN)) {
            assertNull(verdictHelp(DriveAbility.SHARE, v))
            assertNull(verdictHelp(DriveAbility.ACCESS, v))
        }
        assertEquals(R.string.taildrive_verdict_share_help, verdictHelp(DriveAbility.SHARE, Verdict.DENIED))
        assertEquals(R.string.taildrive_verdict_access_help, verdictHelp(DriveAbility.ACCESS, Verdict.DENIED))
    }
}
