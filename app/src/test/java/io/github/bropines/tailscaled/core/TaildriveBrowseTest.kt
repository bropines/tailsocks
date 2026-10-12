package io.github.bropines.tailscaled.core

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaildriveBrowseTest {
    private val tn = "tail4a2c9.ts.net"

    private fun dir(href: String) = WebDavFile(href, true, 0, 0)
    private fun file(href: String, size: Long = 1000, modified: Long = 0) = WebDavFile(href, false, size, modified)

    // --- URL building --------------------------------------------------------------------------

    @Test
    fun everySegmentIsEscapedOnItsOwn() {
        val location = DriveLocation(listOf(tn, "nas", "Movies", "Tom & Jerry #1 (100%)?.mkv"))
        val proxy = DriveProxy.of(true, "127.0.0.1", "33445", false, "", "")
        assertEquals(
            "http://127.0.0.1:33445/$tn/nas/Movies/Tom%20%26%20Jerry%20%231%20%28100%25%29%3F.mkv",
            proxy.urlFor(location)
        )
    }

    @Test
    fun escapingKeepsOnlyTheUnreservedCharacters() {
        assertEquals("AZaz09-._~", percentEncode("AZaz09-._~"))
        assertEquals("%20%23%3F%25%2B%2F%3A%40", percentEncode(" #?%+/:@"))
        // A plus is a plus: a player that reads %20 and + alike must not see a space.
        assertEquals("C%2B%2B%20Primer.pdf", percentEncode("C++ Primer.pdf"))
    }

    @Test
    fun unicodeIsEscapedAsUtf8() {
        assertEquals("%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC.mkv", percentEncode("Фильм.mkv"))
        // Outside the basic plane: one four-byte sequence, not two three-byte surrogates.
        assertEquals("%F0%9F%8E%AC", percentEncode("🎬"))
        assertEquals("%C3%A9", percentEncode("é"))
    }

    @Test
    fun theRootIsASlashAndNeverEmpty() {
        val proxy = DriveProxy.of(true, "127.0.0.1", "33445", false, "", "")
        assertEquals("http://127.0.0.1:33445/", proxy.urlFor(DriveLocation.ROOT))
        assertEquals("/", DriveLocation.ROOT.davPath)
        assertEquals("/$tn/nas/My Videos", DriveLocation(listOf(tn, "nas", "My Videos")).davPath)
    }

    @Test
    fun credentialsAreInTheUrlOnlyWhenTheProxyAsksForThem() {
        val file = DriveLocation(listOf(tn, "nas", "Movies", "a b.mkv"))
        val open = DriveProxy.of(true, "127.0.0.1", "33445", false, "tailsocks", "secret")
        assertNull(open.auth)
        assertFalse(open.urlFor(file, withCredentials = true).contains("secret"))
        assertFalse(open.urlFor(file, withCredentials = true).contains("@"))

        val locked = DriveProxy.of(true, "127.0.0.1", "33445", true, "tailsocks", "secret")
        assertEquals("http://tailsocks:secret@127.0.0.1:33445/$tn/nas/Movies/a%20b.mkv", locked.urlFor(file, withCredentials = true))
        // The URL for showing is the same address without the login.
        assertEquals("http://127.0.0.1:33445/$tn/nas/Movies/a%20b.mkv", locked.urlFor(file))
    }

    @Test
    fun theDaemonRunsTheProxyOpenWhenTheLoginIsIncomplete() {
        // Appctr.startDriveProxy checks a login only with both user and password, so the URL carries none.
        assertNull(DriveProxy.of(true, "127.0.0.1", "33445", true, "tailsocks", "").auth)
        assertNull(DriveProxy.of(true, "127.0.0.1", "33445", true, "", "secret").auth)
    }

    @Test
    fun theLoginIsEscapedLikeAnyOtherComponent() {
        val proxy = DriveProxy.of(true, "127.0.0.1", "33445", true, "me@home", "p:w/d#?")
        assertEquals("http://me%40home:p%3Aw%2Fd%23%3F@127.0.0.1:33445/", proxy.urlFor(DriveLocation.ROOT, withCredentials = true))
    }

    @Test
    fun theSecretDoesNotLeakThroughToString() {
        val proxy = DriveProxy.of(true, "127.0.0.1", "33445", true, "tailsocks", "hunter22")
        assertFalse(proxy.toString().contains("hunter22"))
        assertFalse(proxy.auth.toString().contains("hunter22"))
        val spec = DriveOpenSpec.of(proxy, DriveLocation(listOf(tn, "nas", "s", "f.mkv")))
        assertFalse(spec.toString().contains("hunter22"))
        assertFalse(spec.deviceUrl.contains("hunter22"))
        assertFalse(spec.tailnetUrl.contains("hunter22"))
    }

    @Test
    fun theBindAddressBecomesOneToConnectTo() {
        assertEquals("127.0.0.1", DriveProxy.connectHost("0.0.0.0"))
        assertEquals("127.0.0.1", DriveProxy.connectHost(""))
        assertEquals("::1", DriveProxy.connectHost("::"))
        assertEquals("::1", DriveProxy.connectHost("[::]"))
        assertEquals("192.168.1.9", DriveProxy.connectHost(" 192.168.1.9 "))
        assertEquals("http://[fd7a::1]:8080", DriveProxy.of(true, "fd7a::1", "8080", false, "", "").baseUrl)
    }

    @Test
    fun aBadPortFallsBackToTheDefault() {
        for (bad in listOf("", "abc", "0", "70000", "-1")) {
            assertEquals(bad, DriveProxy.DEFAULT_PORT, DriveProxy.of(true, "127.0.0.1", bad, false, "", "").port)
        }
        assertEquals(8080, DriveProxy.of(true, "127.0.0.1", " 8080 ", false, "", "").port)
    }

    @Test
    fun theTailnetAddressIsTaildrivesOwnAndHasNoLogin() {
        val proxy = DriveProxy.of(true, "127.0.0.1", "33445", true, "tailsocks", "secret")
        val spec = DriveOpenSpec.of(proxy, DriveLocation(listOf(tn, "nas", "Movies", "A #1.mkv")))
        assertEquals("http://100.100.100.100:8080/$tn/nas/Movies/A%20%231.mkv", spec.tailnetUrl)
    }

    @Test
    fun theSpecCarriesTheLoginTwiceOnlyForThePlayersThatNeedEach() {
        val proxy = DriveProxy.of(true, "127.0.0.1", "33445", true, "tailsocks", "secret")
        val spec = DriveOpenSpec.of(proxy, DriveLocation(listOf(tn, "nas", "Movies", "a.mkv")))
        assertEquals("http://tailsocks:secret@127.0.0.1:33445/$tn/nas/Movies/a.mkv", spec.url)
        // "tailsocks:secret" in Base64.
        assertEquals("Basic dGFpbHNvY2tzOnNlY3JldA==", spec.authorization)
        val open = DriveOpenSpec.of(DriveProxy.of(true, "127.0.0.1", "33445", false, "", ""), DriveLocation(listOf(tn, "nas", "Movies", "a.mkv")))
        assertNull(open.authorization)
        assertFalse(open.url.contains("@"))
    }

    @Test
    fun aFileIsAskedForByItsTypeAndThenItsFamily() {
        fun types(name: String) = DriveOpenSpec.of(
            DriveProxy.of(true, "127.0.0.1", "33445", false, "", ""), DriveLocation(listOf(tn, "n", "s", name))
        ).types()
        assertEquals(listOf("video/x-matroska", "video/*"), types("a.mkv"))
        assertEquals(listOf("audio/flac", "audio/*"), types("a.FLAC"))
        assertEquals(listOf("image/png", "image/*"), types("a.png"))
        assertEquals(listOf("application/pdf"), types("a.pdf"))
        assertEquals(listOf("application/octet-stream"), types("README"))
    }

    // --- MIME ----------------------------------------------------------------------------------

    @Test
    fun theCommonMediaExtensionsHaveTypes() {
        val expected = mapOf(
            "film.mp4" to ("video/mp4" to DriveKind.VIDEO),
            "film.MKV" to ("video/x-matroska" to DriveKind.VIDEO),
            "film.m4v" to ("video/x-m4v" to DriveKind.VIDEO),
            "film.webm" to ("video/webm" to DriveKind.VIDEO),
            "stream.ts" to ("video/mp2t" to DriveKind.VIDEO),
            "song.mp3" to ("audio/mpeg" to DriveKind.AUDIO),
            "song.flac" to ("audio/flac" to DriveKind.AUDIO),
            "song.opus" to ("audio/opus" to DriveKind.AUDIO),
            "pic.jpg" to ("image/jpeg" to DriveKind.IMAGE),
            "pic.JPEG" to ("image/jpeg" to DriveKind.IMAGE),
            "pic.heic" to ("image/heic" to DriveKind.IMAGE),
            "film.srt" to ("application/x-subrip" to DriveKind.SUBTITLES),
            "film.vtt" to ("text/vtt" to DriveKind.SUBTITLES),
            "film.ass" to ("text/x-ssa" to DriveKind.SUBTITLES),
            "notes.txt" to ("text/plain" to DriveKind.TEXT),
            "data.json" to ("application/json" to DriveKind.TEXT),
            "book.pdf" to ("application/pdf" to DriveKind.PDF),
        )
        for ((name, want) in expected) {
            val type = DriveMime.of(name)
            assertEquals(name, want.first, type.mime)
            assertEquals(name, want.second, type.kind)
        }
    }

    @Test
    fun unknownAndExtensionlessNamesAreOpaque() {
        for (name in listOf("README", "archive.xyz", "trailing.", ".mp4", "", "dir.mkv/")) {
            assertEquals(name, DriveMime.FALLBACK, DriveMime.guess(name))
            assertEquals(name, DriveKind.OTHER, DriveMime.of(name).kind)
        }
        // Only the last extension counts.
        assertEquals("video/mp4", DriveMime.guess("a.b.c.mp4"))
    }

    @Test
    fun onlyMediaHasAFamilyToFallBackTo() {
        assertEquals("video/*", DriveMime.family("video/x-matroska"))
        assertEquals("audio/*", DriveMime.family("audio/flac"))
        assertEquals("image/*", DriveMime.family("image/png"))
        assertNull(DriveMime.family("application/pdf"))
        assertNull(DriveMime.family("text/plain"))
        assertNull(DriveMime.family("application/octet-stream"))
    }

    // --- Sorting -------------------------------------------------------------------------------

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun numbersInNamesAreNumbers() {
        assertEquals(listOf("Episode 2", "Episode 10"), sorted("Episode 10", "Episode 2"))
        assertEquals(listOf("S01E09", "S01E10", "S02E01"), sorted("S02E01", "S01E10", "S01E09"))
        assertEquals(listOf("1.mkv", "2.mkv", "10.mkv", "100.mkv"), sorted("100.mkv", "10.mkv", "2.mkv", "1.mkv"))
        // A number longer than a Long still orders.
        assertEquals(
            listOf("f99999999999999999999", "f100000000000000000000"),
            sorted("f100000000000000000000", "f99999999999999999999")
        )
    }

    @Test
    fun caseDoesNotMatterButIsStillADeterministicTiebreak() {
        assertEquals(listOf("alpha", "Beta", "gamma"), sorted("gamma", "Beta", "alpha"))
        assertEquals(listOf("Abc", "abc"), sorted("abc", "Abc"))
        assertEquals(listOf("Abc", "abc"), sorted("Abc", "abc"))
        // The same number, written with and without padding.
        assertEquals(listOf("a01", "a1"), sorted("a1", "a01"))
        assertEquals(listOf("a01", "a1"), sorted("a01", "a1"))
    }

    @Test
    fun cyrillicSortsAsLetters() {
        assertEquals(listOf("альфа", "Бета", "Гамма"), sorted("Гамма", "альфа", "Бета"))
    }

    @Test
    fun aPrefixComesFirst() {
        assertEquals(listOf("a", "a b", "a1"), sorted("a1", "a b", "a"))
        assertTrue(NaturalOrder.compare("a", "ab") < 0)
        assertTrue(NaturalOrder.compare("ab", "a") > 0)
        assertEquals(0, NaturalOrder.compare("same", "same"))
    }

    @Test
    fun foldersComeFirstThenNames() {
        val entries = listOf(
            DriveEntry("10 Files", DriveKind.OTHER),
            DriveEntry("z-folder", DriveKind.FOLDER),
            DriveEntry("2 Files", DriveKind.OTHER),
            DriveEntry("a-folder", DriveKind.FOLDER),
            DriveEntry("Season 10", DriveKind.FOLDER),
            DriveEntry("Season 2", DriveKind.FOLDER),
        )
        assertEquals(
            listOf("a-folder", "Season 2", "Season 10", "z-folder", "2 Files", "10 Files"),
            entries.sortedWith(DriveEntryOrder).map { it.name }
        )
    }

    // --- Listing -------------------------------------------------------------------------------

    private val shareLocation = DriveLocation(listOf(tn, "nas", "Movies"))

    @Test
    fun aListingDropsTheFolderItselfAndSortsTheRest() {
        val entries = TaildriveBrowse.entriesOf(
            shareLocation,
            listOf(
                dir("/$tn/nas/Movies/"),
                file("/$tn/nas/Movies/Film 10.mkv", 5_000_000_000, 1_700_000_000_000),
                dir("/$tn/nas/Movies/Sci-Fi/"),
                file("/$tn/nas/Movies/Film 2.mp4"),
                file("/$tn/nas/Movies/Film 2.srt"),
            )
        )
        assertEquals(listOf("Sci-Fi", "Film 2.mp4", "Film 2.srt", "Film 10.mkv"), entries.map { it.name })
        assertEquals(DriveKind.FOLDER, entries[0].kind)
        assertTrue(entries[0].isFolder)
        assertEquals(DriveKind.VIDEO, entries[1].kind)
        assertEquals(DriveKind.SUBTITLES, entries[2].kind)
        assertEquals(5_000_000_000, entries[3].size)
        assertEquals(1_700_000_000_000, entries[3].modified)
    }

    @Test
    fun theKindOfAFolderFollowsTheLevelItIsListedAt() {
        fun kind(parent: DriveLocation) = TaildriveBrowse.entriesOf(parent, listOf(dir(parent.davPath.trimEnd('/') + "/x/")))[0].kind
        assertEquals(DriveKind.TAILNET, kind(DriveLocation.ROOT))
        assertEquals(DriveKind.DEVICE, kind(DriveLocation(listOf(tn))))
        assertEquals(DriveKind.SHARE, kind(DriveLocation(listOf(tn, "nas"))))
        assertEquals(DriveKind.FOLDER, kind(shareLocation))
        assertEquals(DriveKind.FOLDER, kind(shareLocation.child("deeper")))
    }

    @Test
    fun hiddenNamesAreLeftOutUnlessAsked() {
        val files = listOf(dir("/$tn/nas/Movies/"), file("/$tn/nas/Movies/.DS_Store"), file("/$tn/nas/Movies/._Film.mkv"), file("/$tn/nas/Movies/Film.mkv"))
        assertEquals(listOf("Film.mkv"), TaildriveBrowse.entriesOf(shareLocation, files).map { it.name })
        assertEquals(3, TaildriveBrowse.entriesOf(shareLocation, files, showHidden = true).size)
    }

    @Test
    fun onlyDirectChildrenAreRows() {
        val files = listOf(
            dir("/$tn/nas/Movies/"),
            dir("/$tn/nas/Movies/Sub/"),
            file("/$tn/nas/Movies/Sub/deep.mkv"),
            file("/$tn/nas/Movies2/other.mkv"),
            file("/$tn/nas/Movies/ok.mkv"),
        )
        assertEquals(listOf("Sub", "ok.mkv"), TaildriveBrowse.entriesOf(shareLocation, files).map { it.name })
    }

    @Test
    fun theRootListsTheTailnetFromItsSlash() {
        val entries = TaildriveBrowse.entriesOf(DriveLocation.ROOT, listOf(dir("/"), dir("/$tn/")))
        assertEquals(listOf(tn), entries.map { it.name })
    }

    @Test
    fun anAbsoluteHrefIsReadAsItsPath() {
        val entries = TaildriveBrowse.entriesOf(
            shareLocation,
            listOf(dir("http://100.100.100.100:8080/$tn/nas/Movies/"), file("http://100.100.100.100:8080/$tn/nas/Movies/a b.mkv"))
        )
        assertEquals(listOf("a b.mkv"), entries.map { it.name })
    }

    @Test
    fun aFolderHasNoSize() {
        val entries = TaildriveBrowse.entriesOf(shareLocation, listOf(WebDavFile("/$tn/nas/Movies/Sub/", true, 4096, 5)))
        assertEquals(0, entries[0].size)
        assertEquals(5, entries[0].modified)
    }

    @Test
    fun theRootWithOneTailnetIsEnteredForTheUser() {
        val asked = mutableListOf<String>()
        val browse = TaildriveBrowse { path ->
            asked += path
            when (path) {
                "/" -> listOf(dir("/"), dir("/$tn/"))
                "/$tn" -> listOf(dir("/$tn/"), dir("/$tn/nas/"), dir("/$tn/Desktop/"))
                else -> error(path)
            }
        }
        val result = browse.list(DriveLocation.ROOT) as DriveResult.Ok
        assertEquals(DriveLocation(listOf(tn)), result.value.location)
        assertEquals(listOf("Desktop", "nas"), result.value.entries.map { it.name })
        assertTrue(result.value.entries.all { it.kind == DriveKind.DEVICE })
        assertEquals(listOf("/", "/$tn"), asked)
    }

    @Test
    fun severalTailnetsAreListedAsTheyAre() {
        val browse = TaildriveBrowse { listOf(dir("/"), dir("/a.ts.net/"), dir("/b.ts.net/")) }
        val result = browse.list(DriveLocation.ROOT) as DriveResult.Ok
        assertEquals(DriveLocation.ROOT, result.value.location)
        assertEquals(listOf("a.ts.net", "b.ts.net"), result.value.entries.map { it.name })
    }

    @Test
    fun anEmptyRootIsAnEmptyListing() {
        val result = TaildriveBrowse { listOf(dir("/")) }.list(DriveLocation.ROOT) as DriveResult.Ok
        assertTrue(result.value.entries.isEmpty())
    }

    @Test
    fun aFailureIsAResultNotAnException() {
        val browse = TaildriveBrowse { throw WebDavHttpException("PROPFIND", 403, "") }
        assertEquals(DriveResult.Err(DriveError.AccessDenied), browse.list(shareLocation))
    }

    // --- Errors --------------------------------------------------------------------------------

    @Test
    fun statusCodesMapToReasons() {
        assertEquals(DriveError.AccessDenied, DriveError.fromHttp(401))
        assertEquals(DriveError.AccessDenied, DriveError.fromHttp(403))
        assertEquals(DriveError.NotFound, DriveError.fromHttp(404))
        assertEquals(DriveError.Timeout, DriveError.fromHttp(504))
        assertEquals(DriveError.Timeout, DriveError.fromHttp(408))
        // The node's file server is not there: Windows or macOS with the Tailscale app closed.
        assertEquals(DriveError.Unreachable, DriveError.fromHttp(500))
        assertEquals(DriveError.Unreachable, DriveError.fromHttp(502))
        assertEquals(DriveError.Unreachable, DriveError.fromHttp(503))
        assertEquals(DriveError.Failed("HTTP 418"), DriveError.fromHttp(418))
    }

    @Test
    fun exceptionsMapToReasons() {
        assertEquals(DriveError.AccessDenied, DriveError.from(WebDavHttpException("PROPFIND", 403, "forbidden")))
        assertEquals(DriveError.Timeout, DriveError.from(SocketTimeoutException("timed out")))
        assertEquals(DriveError.NotConnected, DriveError.from(ConnectException("Connection refused")))
        assertEquals(DriveError.NotConnected, DriveError.from(Exception("SOCKS5 proxy is not running")))
        assertEquals(DriveError.Unreachable, DriveError.from(UnknownHostException("nas")))
        assertEquals(DriveError.Unreachable, DriveError.from(SocketException("SOCKS: Host unreachable")))
        assertEquals(DriveError.Unreachable, DriveError.from(java.io.EOFException()))
        assertTrue(DriveError.from(SocketException("Broken pipe")) is DriveError.Failed)
        assertTrue(DriveError.from(IllegalStateException("boom")) is DriveError.Failed)
    }

    @Test
    fun aFailureDetailIsShortAndHasNoMessageFlood() {
        val error = DriveError.from(IllegalStateException("x".repeat(500))) as DriveError.Failed
        assertTrue(error.detail.length < 160)
        assertTrue(error.detail.startsWith("IllegalStateException"))
    }

    // --- The path bar --------------------------------------------------------------------------

    @Test
    fun aShortPathIsShownWhole() {
        assertEquals("nas / Movies / Sci-Fi", pathLine(listOf("nas", "Movies", "Sci-Fi"), 40))
        assertEquals("", pathLine(emptyList(), 40))
        assertEquals("nas", pathLine(listOf("nas"), 40))
    }

    @Test
    fun aLongPathGivesUpItsFrontFirst() {
        assertEquals("… / Movies / Sci-Fi", pathLine(listOf("nas", "Movies", "Sci-Fi"), 19))
        assertEquals("… / Sci-Fi", pathLine(listOf("nas", "Movies", "Sci-Fi"), 12))
        // The last name stays however long it is.
        assertEquals("… / A very long folder name", pathLine(listOf("nas", "A very long folder name"), 5))
    }

    // --- Places --------------------------------------------------------------------------------

    @Test
    fun placesKnowTheirParent() {
        val place = DriveLocation(listOf(tn, "nas", "Movies"))
        assertEquals(DriveLocation(listOf(tn, "nas")), place.parent)
        assertEquals("Movies", place.name)
        assertEquals(DriveLevel.FOLDER, place.level)
        assertEquals(DriveLevel.SHARES, place.parent!!.level)
        assertEquals(DriveLevel.DEVICES, place.parent!!.parent!!.level)
        assertNull(DriveLocation.ROOT.parent)
        assertEquals(DriveLevel.TAILNETS, DriveLocation.ROOT.level)
        assertEquals(place, DriveLocation(listOf(tn, "nas")).child("Movies"))
    }
}
