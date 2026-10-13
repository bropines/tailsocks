package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.OpenPlan.NoApp
import io.github.bropines.tailscaled.core.OpenPlan.OnlyBrowser
import io.github.bropines.tailscaled.core.OpenPlan.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a share's file is handed over: which road first, which apps count, and when nothing is
 * started without asking. The author's phone opened a `.mp4` in the web browser, because the
 * only thing that took an `http` link with a video type was one.
 */
class TaildriveOpenPlanTest {

    private val vlc = AppRef("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity")
    private val mx = AppRef("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.ad.ActivityScreen")
    private val chrome = AppRef("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity")
    private val firefox = AppRef("org.mozilla.firefox", "org.mozilla.gecko.BrowserApp")
    private val stub = AppRef("com.google.android.tv.frameworkpackagestubs", "com.google.android.tv.frameworkpackagestubs.Stubs\$MediaStub")
    private val pdfViewer = AppRef("com.example.pdf", "com.example.pdf.Viewer")

    private fun content(vararg players: AppRef, browsers: List<AppRef> = emptyList(), type: String = "video/mp4") =
        RoadResolution(OpenRoad.CONTENT, type, Takers(players.toList(), browsers))

    private fun link(vararg players: AppRef, browsers: List<AppRef> = emptyList(), type: String = "video/mp4") =
        RoadResolution(OpenRoad.LINK, type, Takers(players.toList(), browsers))

    private fun plan(api: Int, vararg r: RoadResolution, media: Boolean = true, preferLink: Boolean = false) =
        TaildriveOpenPlan.plan(api, media, r.toList(), preferLink)

    // --- the order -------------------------------------------------------------------------------

    @Test
    fun theDocumentRoadComesFirstWhereItSeeks() {
        for (api in listOf(26, 28, 34, 36)) {
            assertEquals(listOf(OpenRoad.CONTENT, OpenRoad.LINK), TaildriveOpenPlan.roadOrder(api, media = true))
            assertEquals(listOf(OpenRoad.CONTENT, OpenRoad.LINK), TaildriveOpenPlan.roadOrder(api, media = false))
        }
    }

    /** Android 7 gives a pipe, which cannot seek; the player's own byte ranges over the link can. */
    @Test
    fun beforeAndroid8AMediaFileGoesByTheLinkFirst() {
        for (api in listOf(24, 25)) {
            assertEquals(listOf(OpenRoad.LINK, OpenRoad.CONTENT), TaildriveOpenPlan.roadOrder(api, media = true))
        }
    }

    @Test
    fun beforeAndroid8AFileThatIsNotMediaStillGoesByTheDocument() {
        for (api in listOf(24, 25)) {
            assertEquals(listOf(OpenRoad.CONTENT, OpenRoad.LINK), TaildriveOpenPlan.roadOrder(api, media = false))
        }
    }

    @Test
    fun thePersonsSwitchPutsTheLinkFirstEverywhere() {
        for (api in listOf(24, 26, 36)) for (media in listOf(true, false)) {
            assertEquals(listOf(OpenRoad.LINK, OpenRoad.CONTENT), TaildriveOpenPlan.roadOrder(api, media, preferLink = true))
        }
    }

    @Test
    fun mediaIsVideoAudioAndImages() {
        assertTrue(TaildriveOpenPlan.isMedia(DriveKind.VIDEO))
        assertTrue(TaildriveOpenPlan.isMedia(DriveKind.AUDIO))
        assertTrue(TaildriveOpenPlan.isMedia(DriveKind.IMAGE))
        for (k in listOf(DriveKind.PDF, DriveKind.TEXT, DriveKind.SUBTITLES, DriveKind.OTHER, DriveKind.FOLDER)) assertFalse("$k", TaildriveOpenPlan.isMedia(k))
    }

    // --- one player, several, none -----------------------------------------------------------------

    @Test
    fun exactlyOnePlayerIsTheOneToStart() {
        assertEquals(Start(OpenRoad.CONTENT, "video/mp4", listOf(vlc)), plan(34, content(vlc), link(vlc, browsers = listOf(chrome))))
    }

    @Test
    fun severalPlayersAreOfferedTogetherAndOnlyThey() {
        // A chooser limited to the players: the browser that also answers is not in it.
        assertEquals(
            Start(OpenRoad.CONTENT, "video/mp4", listOf(vlc, mx)),
            plan(34, content(vlc, mx, browsers = listOf(chrome)))
        )
    }

    @Test
    fun theLinkIsTakenWhenNoPlayerTakesTheDocument() {
        assertEquals(Start(OpenRoad.LINK, "video/mp4", listOf(mx)), plan(34, content(), link(mx, browsers = listOf(chrome))))
    }

    @Test
    fun theLinkIsTheFirstRoadOnAndroid7() {
        assertEquals(Start(OpenRoad.LINK, "video/mp4", listOf(mx)), plan(25, content(vlc), link(mx)))
        // …and the document is the way out when the link has no player.
        assertEquals(Start(OpenRoad.CONTENT, "video/mp4", listOf(vlc)), plan(25, content(vlc), link()))
    }

    @Test
    fun theTypeAskedForFirstWinsOverItsFamily() {
        assertEquals(
            Start(OpenRoad.CONTENT, "video/x-matroska", listOf(vlc)),
            plan(34, content(vlc, type = "video/x-matroska"), content(mx, type = "video/*"))
        )
        assertEquals(
            Start(OpenRoad.CONTENT, "video/*", listOf(mx)),
            plan(34, content(type = "video/x-matroska"), content(mx, type = "video/*"))
        )
    }

    // --- only a browser, only a placeholder, nothing -----------------------------------------------------

    /** What the author hit: a video, a browser the only app that takes the link. */
    @Test
    fun aVideoThatOnlyABrowserTakesIsNotOpenedSilently() {
        assertEquals(OnlyBrowser("video/mp4", listOf(chrome, firefox)), plan(34, content(), link(browsers = listOf(chrome, firefox))))
        assertEquals(OnlyBrowser("audio/mpeg", listOf(chrome)), plan(25, content(), link(browsers = listOf(chrome), type = "audio/mpeg")))
        assertEquals(OnlyBrowser("image/png", listOf(chrome)), plan(34, content(), link(browsers = listOf(chrome), type = "image/png")))
    }

    @Test
    fun aBrowserThatAnswersTheDocumentIsNotAReasonToOpenIt() {
        // A browser cannot read a document of ours; offering it would lead nowhere.
        assertEquals(NoApp, plan(34, content(browsers = listOf(chrome)), link()))
    }

    @Test
    fun theTelevisionsPlaceholderIsNobody() {
        // classify drops it before the plan sees it: what is left is nothing.
        val takers = TaildriveOpenPlan.takers(listOf(stub), emptySet())
        assertEquals(Takers(), takers)
        assertEquals(NoApp, plan(36, RoadResolution(OpenRoad.CONTENT, "video/mp4", takers), RoadResolution(OpenRoad.LINK, "video/mp4", takers)))
    }

    @Test
    fun nothingResolvingAtAllIsNoApp() {
        assertEquals(NoApp, plan(34))
        assertEquals(NoApp, plan(34, content(), link()))
    }

    @Test
    fun aLinkThatIsNotThereIsNotAsked() {
        // The proxy is off: the link road has no resolution at all.
        assertEquals(NoApp, plan(34, content()))
        assertEquals(Start(OpenRoad.CONTENT, "video/mp4", listOf(vlc)), plan(25, content(vlc)))
    }

    // --- files that are not media ----------------------------------------------------------------------

    @Test
    fun aDocumentGoesToAnyAppThatTakesIt() {
        assertEquals(
            Start(OpenRoad.CONTENT, "application/pdf", listOf(pdfViewer)),
            plan(34, content(pdfViewer, type = "application/pdf"), link(browsers = listOf(chrome), type = "application/pdf"), media = false)
        )
    }

    @Test
    fun aDocumentThatOnlyABrowserTakesIsOpenedThere() {
        // The present behaviour, and a fine one: a page or a text is what a browser shows.
        assertEquals(
            Start(OpenRoad.LINK, "text/html", listOf(chrome)),
            plan(34, content(type = "text/html"), link(browsers = listOf(chrome), type = "text/html"), media = false)
        )
    }

    @Test
    fun aDocumentNobodyTakesIsNoApp() {
        assertEquals(NoApp, plan(36, content(type = "application/zip"), link(type = "application/zip"), media = false))
    }

    // --- who is a browser --------------------------------------------------------------------------------

    @Test
    fun anAppThatAlsoAnswersAWebLinkIsABrowser() {
        val browsers = setOf("com.android.chrome", "org.mozilla.firefox")
        assertEquals(TaildriveOpenPlan.Kind.BROWSER, TaildriveOpenPlan.classify(chrome, browsers))
        assertEquals(TaildriveOpenPlan.Kind.BROWSER, TaildriveOpenPlan.classify(firefox, browsers))
        assertEquals(TaildriveOpenPlan.Kind.PLAYER, TaildriveOpenPlan.classify(vlc, browsers))
        assertEquals(TaildriveOpenPlan.Kind.PLACEHOLDER, TaildriveOpenPlan.classify(stub, browsers))
    }

    @Test
    fun theSameAppAnsweringTwiceCountsOnce() {
        val takers = TaildriveOpenPlan.takers(listOf(vlc, vlc, chrome, stub, mx), setOf(chrome.pkg))
        assertEquals(Takers(listOf(vlc, mx), listOf(chrome)), takers)
    }

    @Test
    fun aPlaceholderThatIsAlsoInTheBrowserSetIsStillNobody() {
        val takers = TaildriveOpenPlan.takers(listOf(stub), setOf(stub.pkg))
        assertEquals(Takers(), takers)
    }
}
