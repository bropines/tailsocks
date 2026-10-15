package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The three layers: bundled, remote, cache — versions, minApp, sha256, limits, the six hours. */
class TemplateSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = TemplateSource.INDEX_URL.substringBeforeLast('/') + "/"

    private fun template(id: String, version: Int, title: String = id, minApp: String? = null) = """
        {"schema": 1, "id": "$id", "version": $version, ${minApp?.let { "\"minApp\": $it," } ?: ""}
         "category": "access", "title": {"en": "$title"}, "description": {"en": "d"},
         "fragments": {"access": [{"src": ["*"], "dst": ["*"]}]}}
    """.trimIndent()

    /** A set: its files by name, and an index over them (sha256 computed unless [shas] says otherwise). */
    private fun set(vararg files: Pair<String, String>, shas: Map<String, String> = emptyMap(), minApps: Map<String, String> = emptyMap()): Map<String, String> {
        val entries = files.joinToString(",\n") { (name, body) ->
            val t = TemplateJson.decodeFromString(PolicyTemplate.serializer(), body)
            val sha = shas[name] ?: TemplateSource.sha256(body.encodeToByteArray())
            """{"id": "${t.id}", "file": "$name", "version": ${t.version}, "sha256": "$sha"${minApps[name]?.let { ", \"minApp\": $it" } ?: ""}}"""
        }
        return files.toMap() + ("index.json" to """{"schema": 1, "templates": [$entries]}""")
    }

    private fun dir(files: Map<String, String>): File = tmp.newFolder().also { d -> files.forEach { (n, b) -> File(d, n).writeText(b) } }

    /** The remote set as an HTTPS server would serve it; [calls] counts what was asked. */
    private class Remote(var files: Map<String, String>, var down: Boolean = false) : TemplateFetch {
        val calls = mutableListOf<String>()
        override fun get(url: String): HttpResponse {
            calls += url
            if (down) throw TransportException("no route")
            val name = url.substringAfterLast('/')
            return files[name]?.let { HttpResponse(200, it) } ?: HttpResponse(404, "")
        }
    }

    private var now = 1_000_000_000L
    private val logs = mutableListOf<String>()
    private val cacheDir by lazy { tmp.newFolder("cache") }

    private fun source(bundled: Map<String, String>, remote: Remote? = null, app: AppIdentity = AppIdentity("5.1.0", 5010000)) =
        TemplateSource(TemplateFiles.dir(dir(bundled)), cacheDir, remote, app, { now }, { logs += it })

    private fun TemplateCatalog.ids() = templates.map { "${it.template.id}@${it.template.version}:${it.origin.name.lowercase()}" }

    private val bundledSet = set("a.json" to template("a", 1), "b.json" to template("b", 2))

    @Test
    fun theBundledSetInItsOrder() {
        assertEquals(listOf("a@1:bundled", "b@2:bundled"), source(bundledSet).local().ids())
    }

    @Test
    fun aBundledFileThatDoesNotMatchItsShaIsSkipped() {
        val bad = set("a.json" to template("a", 1), "b.json" to template("b", 2), shas = mapOf("a.json" to "0".repeat(64)))
        assertEquals(listOf("b@2:bundled"), source(bad).local().ids())
        assertTrue(logs.any { "a.json" in it && "sha256" in it })
    }

    @Test
    fun aHigherRemoteVersionReplacesTheBundledOneANewOneIsAddedAndALowerOneIgnored() {
        val remote = Remote(set("a.json" to template("a", 2, "A two"), "b.json" to template("b", 1), "c.json" to template("c", 1)))
        val c = source(bundledSet, remote).refresh()
        assertEquals(listOf("a@2:cache", "b@2:bundled", "c@1:cache"), c.ids())
        assertEquals("A two", c.byId("a")!!.title["en"])
        assertNull(c.error)
        assertEquals(now, c.updatedAt)
        // A version the bundled set has already is not even downloaded.
        assertTrue(remote.calls.none { it.endsWith("/b.json") })
        // The cache stands on its own: a new start, no network.
        assertEquals(listOf("a@2:cache", "b@2:bundled", "c@1:cache"), source(bundledSet).local().ids())
    }

    @Test
    fun minAppInTheIndexAndInTheFile() {
        val remote = Remote(
            set(
                "a.json" to template("a", 2),
                "b.json" to template("b", 3, minApp = "\"9.0.0\""),
                "c.json" to template("c", 1, minApp = "5010000"),
                "d.json" to template("d", 1),
                minApps = mapOf("d.json" to "\"6.0.0\""),
            ),
        )
        val c = source(bundledSet, remote).refresh()
        assertEquals(listOf("a@2:cache", "b@2:bundled", "c@1:cache"), c.ids())
        assertTrue(remote.calls.none { it.endsWith("/d.json") })
        // An app of an older version takes none of those that ask for more.
        val older = TemplateSource(TemplateFiles.dir(dir(bundledSet)), cacheDir, null, AppIdentity("5.0.1", 5000100), { now }, {})
        assertEquals(listOf("a@2:cache", "b@2:bundled"), older.local().ids())
        assertTrue(source(bundledSet).allows(kotlinx.serialization.json.JsonPrimitive("5.1.0")))
    }

    @Test
    fun aRemoteFileThatDoesNotMatchItsShaIsRejectedTheOthersAreTaken() {
        val files = set("a.json" to template("a", 2), "c.json" to template("c", 1))
        val remote = Remote(files + ("a.json" to template("a", 2, "tampered")))
        val c = source(bundledSet, remote).refresh()
        assertEquals(listOf("a@1:bundled", "b@2:bundled", "c@1:cache"), c.ids())
        assertTrue(logs.any { "a.json" in it && "sha256" in it })
    }

    @Test
    fun aRemoteFileThatBreaksARuleOrLiesAboutItselfIsSkipped() {
        val broken = template("c", 1).replace("\"access\"", "\"derpMap\"")
        val liar = template("e", 1)
        val remote = Remote(set("c.json" to broken, "d.json" to template("d", 1)) )
        // The index says e.json is "f" version 1.
        val index = remote.files["index.json"]!!.replace("]}", """, {"id": "f", "file": "e.json", "version": 1, "sha256": "${TemplateSource.sha256(liar.encodeToByteArray())}"}]}""")
        remote.files = remote.files + ("e.json" to liar) + ("index.json" to index)
        assertEquals(listOf("a@1:bundled", "b@2:bundled", "d@1:cache"), source(bundledSet, remote).refresh().ids())
        assertTrue(logs.any { "c.json" in it && "derpMap" in it })
        assertTrue(logs.any { "e.json" in it && "the index says f" in it })
    }

    @Test
    fun namesThatAreNotPlainFilesAreNeverFetched() {
        val good = template("c", 1)
        val sha = TemplateSource.sha256(good.encodeToByteArray())
        val index = """{"schema": 1, "templates": [
            {"id": "c", "file": "../c.json", "version": 1, "sha256": "$sha"},
            {"id": "c", "file": "https://evil.example/c.json", "version": 1, "sha256": "$sha"},
            {"id": "c", "file": "c.json", "version": 1, "sha256": "not-a-sha"}]}"""
        val remote = Remote(mapOf("index.json" to index, "c.json" to good))
        assertEquals(listOf("a@1:bundled", "b@2:bundled"), source(bundledSet, remote).refresh().ids())
        assertEquals(listOf(TemplateSource.INDEX_URL), remote.calls)
        assertTrue(remote.calls.all { it.startsWith(base) })
    }

    @Test
    fun aFileOverTheLimitIsSkipped() {
        val big = template("c", 1).replace("\"d\"", "\"" + "x".repeat(TemplateSource.MAX_FILE_BYTES) + "\"")
        assertEquals(listOf("a@1:bundled", "b@2:bundled"), source(bundledSet, Remote(set("c.json" to big))).refresh().ids())
        assertTrue(logs.any { "c.json" in it && "over" in it })
    }

    @Test
    fun aFailedRefreshKeepsTheLastGoodSetAndSaysWhy() {
        val remote = Remote(set("a.json" to template("a", 2)))
        source(bundledSet, remote).refresh()
        remote.down = true
        now += TemplateSource.REFRESH_MS
        val c = source(bundledSet, remote).refresh()
        assertEquals(listOf("a@2:cache", "b@2:bundled"), c.ids())
        assertEquals("no route", c.error)
        // A server error and an index that does not parse say so too.
        remote.down = false
        remote.files = mapOf("index.json" to "{ nope")
        assertEquals("index.json does not parse", source(bundledSet, remote).refresh(force = true).error)
        remote.files = emptyMap()
        assertEquals("index.json: HTTP 404", source(bundledSet, remote).refresh(force = true).error)
        assertEquals(listOf("a@2:cache", "b@2:bundled"), source(bundledSet).local().ids())
    }

    @Test
    fun atMostOnceInSixHoursUnlessForced() {
        val remote = Remote(set("a.json" to template("a", 2)))
        val s = source(bundledSet, remote)
        s.refresh()
        val asked = remote.calls.size
        now += TemplateSource.REFRESH_MS - 1
        s.refresh()
        assertEquals(asked, remote.calls.size)
        s.refresh(force = true)
        assertTrue(remote.calls.size > asked)
        val forced = remote.calls.size
        now += TemplateSource.REFRESH_MS
        s.refresh()
        assertTrue(remote.calls.size > forced)
    }

    @Test
    fun anUnchangedFileIsTakenFromTheCache() {
        val remote = Remote(set("a.json" to template("a", 2)))
        source(bundledSet, remote).refresh()
        remote.calls.clear()
        source(bundledSet, remote).refresh(force = true)
        assertEquals(listOf(TemplateSource.INDEX_URL), remote.calls)
    }

    @Test
    fun aTemplateDroppedFromTheRemoteSetLeavesTheCache() {
        val remote = Remote(set("a.json" to template("a", 2), "c.json" to template("c", 1)))
        source(bundledSet, remote).refresh()
        remote.files = set("c.json" to template("c", 1))
        assertEquals(listOf("a@1:bundled", "b@2:bundled", "c@1:cache"), source(bundledSet, remote).refresh(force = true).ids())
    }

    @Test
    fun withoutANetworkItIsTheLocalSet() {
        val s = source(bundledSet)
        assertEquals(s.local(), s.refresh(force = true))
        assertNotNull(TemplateSource.INDEX_URL.takeIf { it.startsWith("https://bropines.github.io/") })
    }
}
