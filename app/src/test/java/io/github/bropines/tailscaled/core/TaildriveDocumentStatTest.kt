package io.github.bropines.tailscaled.core

import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

/**
 * How the documents provider describes one document: a Depth-0 PROPFIND first, the Depth-1
 * listing it always used when Depth 0 answers with anything unexpected. The answers are
 * recorded PROPFIND bodies, parsed by the app's own parser (parsePropFindXml) over the JVM's
 * XML parser standing in for the platform's pull parser.
 */
class TaildriveDocumentStatTest {
    private val mimeOf: (String) -> String = DriveMime::guess
    private val film = DriveDocId("default", "/home.tailnet/laptop/downloads/film.mp4")
    private val share = DriveDocId("default", "/home.tailnet/laptop/downloads")

    // --- Recorded answers --------------------------------------------------------------------------

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:">${responses.joinToString("")}</D:multistatus>"""

    private fun fileResponse(href: String, length: Long? = 38_189_343) = """
        <D:response><D:href>$href</D:href><D:propstat><D:prop>
          <D:resourcetype/>
          ${length?.let { "<D:getcontentlength>$it</D:getcontentlength>" } ?: ""}
          <D:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</D:getlastmodified>
        </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"""

    private fun folderResponse(href: String) = """
        <D:response><D:href>$href</D:href><D:propstat><D:prop>
          <D:resourcetype><D:collection/></D:resourcetype>
          <D:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</D:getlastmodified>
        </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"""

    /** A file whose length the server would not give: the property comes back empty under a 404 propstat. */
    private fun fileWithoutLength(href: String) = """
        <D:response><D:href>$href</D:href>
          <D:propstat><D:prop><D:resourcetype/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          <D:propstat><D:prop><D:getcontentlength/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
        </D:response>"""

    /** The share's Depth-1 listing: the share and a thousand files. */
    private val shareListing = multistatus(
        folderResponse("/home.tailnet/laptop/downloads/"),
        *Array(1000) { fileResponse("/home.tailnet/laptop/downloads/clip%20$it.mp4", length = 1000L + it) },
        fileResponse("/home.tailnet/laptop/downloads/film.mp4"),
    )

    // --- A fake bridge that answers PROPFIND by depth ------------------------------------------------

    private class Asked(val depth: String, val path: String)

    private fun client(depth0: (String) -> DriveAnswer, depth1: (String) -> DriveAnswer, asked: MutableList<Asked>): TaildriveClient {
        val calls = object : DriveCalls {
            override fun newCall(): DriveCalls.Call = object : DriveCalls.Call {
                override fun request(method: String, path: String, depth: String, destination: String, timeoutMs: Int): DriveAnswer {
                    assertEquals("PROPFIND", method)
                    asked += Asked(depth, path)
                    return if (depth == "0") depth0(path) else depth1(path)
                }
                override fun range(path: String, offset: Long, length: Int, timeoutMs: Int) = throw UnsupportedOperationException()
                override fun open(path: String, timeoutMs: Int) = throw UnsupportedOperationException()
                override fun upload(path: String) = throw UnsupportedOperationException()
                override fun cancel() {}
            }
        }
        return TaildriveClient(calls) { jvmPullParser() }
    }

    private fun answer(status: Int, body: String = "") = DriveAnswer(status = status, body = body.toByteArray())

    private fun resolve(id: DriveDocId, depth0: (String) -> DriveAnswer, depth1: (String) -> DriveAnswer = { answer(207, shareListing) }): Pair<DriveDocument, List<Asked>> {
        val asked = ArrayList<Asked>()
        val c = client(depth0, depth1, asked)
        return DriveDocuments.resolve(id, c::stat, c::list, mimeOf) to asked
    }

    // --- Depth 0 that answers --------------------------------------------------------------------

    @Test
    fun aFileIsDescribedFromItsOwnEntryWithoutListingItsFolder() {
        val (doc, asked) = resolve(film, { answer(207, multistatus(fileResponse(it))) })
        assertEquals("film.mp4", doc.name)
        assertEquals("video/mp4", doc.mime)
        assertEquals(38_189_343L, doc.size)
        assertFalse(doc.isDirectory)
        assertEquals(1_700_000_000_000L, doc.modified)
        assertEquals(listOf("0"), asked.map { it.depth })
    }

    @Test
    fun aFolderOfAThousandFilesIsOneSmallAnswer() {
        val (doc, asked) = resolve(share, { answer(207, multistatus(folderResponse("$it/"))) })
        assertTrue(doc.isDirectory)
        assertEquals(0L, doc.size)
        assertEquals("one request, at depth 0", listOf("0"), asked.map { it.depth })
    }

    // --- Depth 0 that surprises: the Depth-1 road, as before ----------------------------------------

    @Test
    fun aRefusedDepthZeroFallsBackToTheListing() {
        for (status in listOf(400, 403, 405, 500, 501, 502)) {
            val (doc, asked) = resolve(film, { answer(status, "nope") })
            assertEquals("HTTP $status", 38_189_343L, doc.size)
            assertEquals("HTTP $status", listOf("0", "1"), asked.map { it.depth })
        }
    }

    @Test
    fun aDepthZeroWithoutTheLengthOfAFileFallsBackRatherThanSayingZeroBytes() {
        val (doc, asked) = resolve(film, { answer(207, multistatus(fileWithoutLength(it))) })
        assertEquals(38_189_343L, doc.size)
        assertEquals(listOf("0", "1"), asked.map { it.depth })
    }

    @Test
    fun aDepthZeroAboutAnotherPathOrEmptyFallsBack() {
        for (body in listOf(
            multistatus(fileResponse("/home.tailnet/laptop/downloads/other.mp4")),
            multistatus(),
            "",
        )) {
            val (doc, asked) = resolve(film, { answer(207, body) })
            assertEquals("film.mp4", doc.name)
            assertEquals(38_189_343L, doc.size)
            assertEquals(listOf("0", "1"), asked.map { it.depth })
        }
    }

    @Test
    fun aFileThatIsNotThereIsStillNotFoundAfterBothRequests() {
        val asked = ArrayList<Asked>()
        val c = client({ answer(404) }, { answer(404) }, asked)
        try {
            DriveDocuments.resolve(film, c::stat, c::list, mimeOf)
            fail("expected FileNotFoundException")
        } catch (e: FileNotFoundException) {
        }
        assertEquals(listOf("0", "1"), asked.map { it.depth })
    }

    @Test
    fun whenBothFailTheAnswerIsTheFailuresAsBefore() {
        val (doc, _) = resolve(film, { answer(502) }, { answer(502) })
        assertEquals("film.mp4", doc.name)
        assertNull("not known is not 0", doc.size)
        assertEquals(0, doc.flags)
    }

    @Test
    fun statEntryIsThePathsOwnEntryOnly() {
        val folderDoc = DriveDocuments.statEntry(share) { listOf(WebDavFile("/home.tailnet/laptop/downloads/", true, 0, 0, sized = false)) }
        assertTrue("a folder needs no length", folderDoc!!.isDirectory)
        assertNull(DriveDocuments.statEntry(film) { throw java.io.IOException("gone") })
        assertNull(DriveDocuments.statEntry(film) { listOf(WebDavFile(film.path, false, 0, 0, sized = false)) })
    }

    // --- The recorded bodies, as the parser reads them -------------------------------------------

    @Test
    fun theParserTellsALengthOfZeroFromNoLength() {
        val parsed = parsePropFindXml(
            multistatus(fileResponse("/a/empty.txt", length = 0), fileWithoutLength("/a/unknown.txt")).byteInputStream(),
            jvmPullParser()
        )
        assertEquals(listOf(true, false), parsed.map { it.sized })
        assertEquals(listOf(0L, 0L), parsed.map { it.size })
    }

    @Test
    fun theParserDecodesHrefsAndKeepsAPlus() {
        val parsed = parsePropFindXml(multistatus(fileResponse("/a/b%20c+d.mp4")).byteInputStream(), jvmPullParser())
        assertEquals("/a/b c+d.mp4", parsed.single().href)
    }

    // --- The listing's quiet second ask ----------------------------------------------------------

    @Test
    fun aStaleGatewayFromADeviceIsAskedAgainOnce() {
        var calls = 0
        val result = TaildriveBrowse.retryingStale(depth = 3, delayMs = 0) {
            if (++calls == 1) throw WebDavHttpException("PROPFIND", 502, "") else "listed"
        }
        assertEquals("listed", result)
        assertEquals(2, calls)
    }

    @Test
    fun aSecondStaleGatewayOrAnyOtherFailureIsThrown() {
        var calls = 0
        try {
            TaildriveBrowse.retryingStale(depth = 3, delayMs = 0) { calls++; throw WebDavHttpException("PROPFIND", 503, "") }
            fail("expected the second failure")
        } catch (e: WebDavHttpException) {
            assertEquals(2, calls)
        }
        calls = 0
        try {
            TaildriveBrowse.retryingStale(depth = 3, delayMs = 0) { calls++; throw WebDavHttpException("PROPFIND", 500, "") }
            fail("expected the failure")
        } catch (e: WebDavHttpException) {
            assertEquals("a 500 is not asked again", 1, calls)
        }
    }

    @Test
    fun aboveADeviceNothingIsAskedAgain() {
        var calls = 0
        try {
            TaildriveBrowse.retryingStale(depth = 1, delayMs = 0) { calls++; throw WebDavHttpException("PROPFIND", 502, "") }
            fail("expected the failure")
        } catch (e: WebDavHttpException) {
            assertEquals(1, calls)
        }
    }

    companion object {
        /** One event of a pull parser: its type, and the element's name or the text. */
        private class Event(val type: Int, val name: String? = null, val text: String? = null)

        /**
         * An org.xmlpull parser for the calls parsePropFindXml makes (setInput, getEventType,
         * getName, getText, next), over the JVM's DOM parser: the platform's pull parser is not
         * on a unit test's classpath. The document is read whole and walked into the events a
         * pull parser would give.
         */
        fun jvmPullParser(): XmlPullParser {
            var events: List<Event> = emptyList()
            var at = 0
            fun walk(node: Node, out: MutableList<Event>) {
                when (node.nodeType) {
                    Node.ELEMENT_NODE -> {
                        val name = node.localName ?: node.nodeName
                        out += Event(XmlPullParser.START_TAG, name = name)
                        val children = node.childNodes
                        for (i in 0 until children.length) walk(children.item(i), out)
                        out += Event(XmlPullParser.END_TAG, name = name)
                    }
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out += Event(XmlPullParser.TEXT, text = node.nodeValue)
                }
            }
            return Proxy.newProxyInstance(XmlPullParser::class.java.classLoader, arrayOf(XmlPullParser::class.java)) { _, method, args ->
                when (method.name) {
                    "setInput" -> {
                        val bytes = (args!![0] as InputStream).readBytes()
                        val out = mutableListOf(Event(XmlPullParser.START_DOCUMENT))
                        if (bytes.isNotEmpty()) {
                            val factory = DocumentBuilderFactory.newInstance().apply {
                                isNamespaceAware = true
                                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                            }
                            walk(factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement, out)
                        }
                        out += Event(XmlPullParser.END_DOCUMENT)
                        events = out
                        at = 0
                        null
                    }
                    "getEventType" -> events[at].type
                    "next" -> {
                        if (at < events.lastIndex) at++
                        events[at].type
                    }
                    "getName" -> events[at].name
                    "getText" -> events[at].text
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as XmlPullParser
        }

    }
}
