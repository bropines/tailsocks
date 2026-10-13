package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the hand-off of a share's file to a player has to tell apart; the intents themselves need a device. */
class TaildriveHandOffTest {
    /**
     * Measured on the Android TV image of API 36: a VIEW of `video/mp4` was started at this stub, which
     * showed a toast, while the player installed beside it was never asked.
     */
    @Test
    fun theTelevisionsMediaStubIsAPlaceholder() {
        val stubs = "com.android.tv.frameworkpackagestubs"
        assertTrue(isPlaceholderActivity(stubs, "$stubs.Stubs\$MediaStub"))
    }

    @Test
    fun thePlayersAreNot() {
        assertFalse(isPlaceholderActivity("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity"))
        assertFalse(isPlaceholderActivity("org.xbmc.kodi", "org.xbmc.kodi.Splash"))
        assertFalse(isPlaceholderActivity("com.brouken.player", "com.brouken.player.PlayerActivity"))
    }

    /** The type asked for first, then its family: a player that lists only some video types still takes the file. */
    @Test
    fun theFamilyIsAskedForAfterTheType() {
        assertEquals("video/*", DriveMime.family(DriveMime.guess("Dune.2021.mkv")))
        assertEquals("audio/*", DriveMime.family(DriveMime.guess("track.flac")))
        assertEquals(null, DriveMime.family(DriveMime.guess("disc.iso")))
    }
}
