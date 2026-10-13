package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The line a document picker shows under a folder that cannot be listed. */
class TaildriveReasonsTest {

    @Test
    fun everyReasonHasItsOwnWords() {
        assertEquals(R.string.taildrive_unavailable_not_connected, documentReasonRes(DriveError.NotConnected))
        assertEquals(R.string.taildrive_unavailable_not_permitted, documentReasonRes(DriveError.NotPermitted))
        assertEquals(R.string.taildrive_unavailable_no_answer, documentReasonRes(DriveError.NoAnswer))
        assertEquals(R.string.taildrive_unavailable_no_fileserver, documentReasonRes(DriveError.Unreachable))
        assertEquals(R.string.taildrive_unavailable_forbidden, documentReasonRes(DriveError.AccessDenied))
        assertEquals(R.string.taildrive_unavailable_not_found, documentReasonRes(DriveError.NotFound))
        assertEquals(R.string.taildrive_unavailable_timeout, documentReasonRes(DriveError.Timeout))
    }

    /** The author's picker said "Unavailable — unexpected end of stream on com.android.okhttp.Address@927775cb". */
    @Test
    fun anExceptionIsNeverTheText() {
        val closed = java.io.IOException("unexpected end of stream on com.android.okhttp.Address@927775cb")
        assertEquals(R.string.taildrive_unavailable_no_answer, documentReasonRes(DriveError.from(closed, depth = 0)))
        // Nothing that is not understood is passed on: the reason has no placeholder to fill with a message.
        val unknown = documentReasonRes(DriveError.from(IllegalStateException("boom at 100.64.0.1"), depth = 0))
        assertEquals(R.string.taildrive_unavailable_failed, unknown)
        assertNotEquals(R.string.taildrive_unavailable_no_fileserver, unknown)
    }

    @Test
    fun theProxyIsNotThePickersBusiness() {
        assertEquals(R.string.taildrive_unavailable_failed, documentReasonRes(DriveError.ProxyOff))
        assertEquals(R.string.taildrive_unavailable_failed, documentReasonRes(DriveError.ProxyNotListening))
    }
}
