package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiClientConnectivity
import io.github.bropines.tailscaled.admin.api.ApiDerpLatency
import io.github.bropines.tailscaled.admin.api.ApiDerpMap
import io.github.bropines.tailscaled.admin.api.ApiDerpNode
import io.github.bropines.tailscaled.admin.api.ApiDerpRegion
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.parsed
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.removeAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.setAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Relays page: excluding and using Tailscale's regions through derpMap, byte for byte, and what the page decides. */
class DerpRelaysTest {

    private fun p(vararg s: Any) = PolicyPath.of(*s)
    private fun exclude(text: String, id: String) = PolicyEdits.setDerpRegionExcluded(text, id, true)
    private fun include(text: String, id: String) = PolicyEdits.setDerpRegionExcluded(text, id, false)

    /** The author's file without its derpMap (and the comment over it). */
    private val bare = SourceEdits.remove(sample, p("derpMap"))

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("the edit should have been refused")
        } catch (_: PolicyEditException) {
        }
    }

    // ---- the edits ----

    @Test
    fun excludingARegionAddsOneLineInNumberOrder() {
        val five = exclude(sample, "5")
        assertTrue(five, five.contains("\t\t\"Regions\": {\n\t\t\t\"5\": null,\n\t\t\t\"28\": null,\n\t\t},"))
        assertEquals("only an insertion", "", window(sample, five).removed)
        assertEquals("\"5\": null,\n\t\t\t".length, window(sample, five).inserted.length)
        assertMeaning(setAt(parsed(sample), listOf("derpMap", "Regions", "5"), json("null")), five)

        val thirty = exclude(five, "30")
        assertTrue(thirty, thirty.contains("\t\t\t\"5\": null,\n\t\t\t\"28\": null,\n\t\t\t\"30\": null,\n\t\t},"))
        assertEquals("", window(five, thirty).removed)
    }

    @Test
    fun usingItAgainGivesBackTheFileByteForByte() {
        assertEquals(sample, include(exclude(sample, "5"), "5"))
        assertEquals(sample, include(include(exclude(exclude(sample, "1"), "30"), "1"), "30"))
        // Twice the same is nothing.
        assertEquals(sample, exclude(sample, "28"))
        assertEquals(sample, include(sample, "7"))
    }

    @Test
    fun usingTheOnlyExcludedRegionKeepsTheCommentAboveTheMap() {
        val all = include(sample, "28")
        // Regions goes with its last region; derpMap stays, its comment would have nothing to stand over otherwise.
        assertTrue(all, all.startsWith("{\n\t// Отключение дефолтной DERP-ноды 28 (Helsinki / HEL)\n\t\"derpMap\": {},\n\n\t// --- 1."))
        assertEquals(sample.substring(sample.indexOf("\n\n\t// --- 1.")), all.substring(all.indexOf("\n\n\t// --- 1.")))
        assertMeaning(setAt(parsed(sample), listOf("derpMap"), json("{}")), all)
        // And back: the same lines as before.
        assertEquals(sample, exclude(all, "28"))
    }

    @Test
    fun aFileWithoutARelayMapGetsOneInItsPlaceAndLosesItAgain() {
        val two = exclude(bare, "2")
        assertTrue(two, two.endsWith("\t],\n\n\t\"derpMap\": {\n\t\t\"Regions\": {\n\t\t\t\"2\": null,\n\t\t},\n\t},\n}\n"))
        assertEquals("", window(bare, two).removed)
        assertMeaning(setAt(parsed(bare), listOf("derpMap"), json("{\"Regions\": {\"2\": null}}")), two)
        assertEquals(bare, include(two, "2"))
    }

    @Test
    fun regionsAreWrittenBesideWhatTheMapAlreadySays() {
        val omit = bare.replace("\n\t\"groups\": {", "\n\t\"derpMap\": {\n\t\t\"OmitDefaultRegions\": false,\n\t},\n\n\t\"groups\": {")
        val one = exclude(omit, "1")
        assertTrue(one, one.contains("\t\"derpMap\": {\n\t\t\"Regions\": {\n\t\t\t\"1\": null,\n\t\t},\n\t\t\"OmitDefaultRegions\": false,\n\t},"))
        assertEquals(omit, include(one, "1"))

        // Go reads "regions" as Regions: the file's spelling is where the line goes.
        val lower = sample.replace("\"Regions\"", "\"regions\"")
        val lowerFive = exclude(lower, "5")
        assertTrue(lowerFive.contains("\"regions\": {\n\t\t\t\"5\": null,"))
        assertFalse(lowerFive.contains("\"Regions\""))
        assertEquals(lower, include(lowerFive, "5"))
    }

    @Test
    fun aRegionsCommentStaysAndSoDoesTheMapItIsIn() {
        val noted = sample.replace("\t\t\t\"28\": null,\n", "\t\t\t// Медленный из Москвы.\n\t\t\t\"28\": null,\n")
        val file = DerpFile.read(noted)!!
        assertEquals("Медленный из Москвы.", file.entries.single().origin.note)
        // The region's own comment goes with its line, as a rule's goes with the rule.
        val all = include(noted, "28")
        assertFalse(all.contains("Медленный"))
        assertEquals(include(sample, "28"), all)

        // A comment inside Regions that is nobody's note keeps Regions.
        val headed = sample.replace("\t\t\"Regions\": {\n", "\t\t\"Regions\": {\n\t\t\t// --- excluded ---\n\n")
        val kept = include(headed, "28")
        assertTrue(kept, kept.contains("\t\t\"Regions\": {\n\t\t\t// --- excluded ---\n"))
    }

    @Test
    fun windowsLineEndingsStayWindowsLineEndings() {
        val crlf = sample.replace("\n", "\r\n")
        val d = PolicyDraft(crlf).edit { exclude(it, "5") }
        assertTrue(d is DraftEdit.Done)
        val out = d.draft.text
        assertFalse(Regex("[^\r]\n").containsMatchIn(out))
        assertEquals(exclude(sample, "5").replace("\n", "\r\n"), out)
    }

    @Test
    fun whatTheEditorWouldGuessAboutIsRefused() {
        val own = sample.replace("\t\t\t\"28\": null,\n", "\t\t\t\"28\": null,\n\t\t\t\"900\": {\"RegionID\": 900, \"RegionCode\": \"home\", \"Nodes\": []},\n")
        refused { exclude(own, "900") }
        refused { include(own, "900") }
        assertEquals("other regions still switch", own.replace("\t\t\t\"28\": null,\n", ""), include(own, "28"))

        val odd = sample.replace("\"derpMap\"", "\"DERPMap\"")
        assertEquals(DerpLock.SPELLING, DerpFile.read(odd)!!.lock)
        assertEquals("DERPMap", DerpFile.read(odd)!!.oddKey)
        refused { exclude(odd, "5") }

        val shape = sample.replace("\"derpMap\": {\n\t\t\"Regions\": {\n\t\t\t\"28\": null,\n\t\t},\n\t},", "\"derpMap\": true,")
        assertEquals(DerpLock.SHAPE, DerpFile.read(shape)!!.lock)
        refused { exclude(shape, "5") }

        val twice = sample.replace("\t\t\t\"28\": null,\n", "\t\t\t\"28\": null,\n\t\t\t\"28\": null,\n")
        assertEquals(DerpLock.TWICE, DerpFile.read(twice)!!.lock)
        refused { include(twice, "28") }

        val spellings = sample.replace("\t\t},\n\t},\n\n\t// --- 1.", "\t\t},\n\t\t\"regions\": {},\n\t},\n\n\t// --- 1.")
        assertEquals(DerpLock.TWICE, DerpFile.read(spellings)!!.lock)
    }

    // ---- what the page reads ----

    @Test
    fun theAuthorsFileExcludesHelsinki() {
        val f = DerpFile.read(sample)!!
        assertEquals(setOf("28"), f.excluded)
        assertEquals("Regions", f.regionsKey)
        assertNull(f.lock)
        assertFalse(f.omitDefaults)
        assertEquals(3, f.origin!!.line)
        assertTrue(f.origin!!.note!!.startsWith("Отключение"))
        val none = DerpFile.read(bare)!!
        assertNull(none.origin)
        assertTrue(none.entries.isEmpty())
    }

    private fun region(id: Int, code: String, name: String, servers: Int = 3) =
        id.toString() to ApiDerpRegion(id, code, name, List(servers) { ApiDerpNode("${id}${'a' + it}", "derp$id${'a' + it}.example.com") })

    private val map = ApiDerpMap(
        mapOf(
            region(1, "nyc", "New York City", 4),
            region(4, "fra", "Frankfurt", 5),
            region(8, "lhr", "London"),
            region(22, "waw", "Warsaw"),
            region(28, "hel", "Helsinki"),
        ),
    )

    private fun device(name: String, home: String?, vararg ms: Pair<String, Double>) = ApiDevice(
        nodeId = name,
        name = "$name.tail1234.ts.net",
        clientConnectivity = ApiClientConnectivity(latency = ms.associate { (r, l) -> r to ApiDerpLatency(preferred = r == home, latencyMs = l) }),
    )

    private val devices = listOf(
        device("phone", "Frankfurt", "Frankfurt" to 30.0, "Warsaw" to 41.0, "Helsinki" to 55.0),
        device("nas", "Frankfurt", "Frankfurt" to 20.0, "Warsaw" to 35.0),
        device("laptop", "Warsaw", "Frankfurt" to 50.0, "Warsaw" to 25.0, "London" to 70.0),
        device("old", null),
        ApiDevice(nodeId = "plain", name = "plain.tail1234.ts.net"),
    )

    @Test
    fun rowsPutHomeRegionsFirstThenTheFastest() {
        val rows = Relays.rows(DerpFile.read(sample)!!, map, devices)
        assertEquals(listOf("4", "22", "28", "8", "1"), rows.map { it.id })
        val fra = rows[0]
        assertEquals(listOf("phone", "nas"), fra.homes.map { it.nodeId })
        // Median of 20, 30, 50.
        assertEquals(RelayLatency(30, 3), fra.latency)
        assertEquals(5, fra.servers)
        assertEquals("fra", fra.code)
        // Warsaw: 25, 35, 41.
        assertEquals(RelayLatency(35, 3), rows[1].latency)
        val hel = rows[2]
        assertTrue(hel.excluded)
        assertTrue(hel.switchable)
        assertEquals(RelayLatency(55, 1), hel.latency)
        assertNull(rows.last().latency)
        // Two devices: the mean of the middle pair.
        assertEquals(RelayLatency(25, 2), Relays.latency(Relays.reports(devices.take(2)), "frankfurt"))
    }

    @Test
    fun beforeTheMapComesOnlyTheFilesExclusionsAreListed() {
        val rows = Relays.rows(DerpFile.read(sample)!!, null, devices)
        assertEquals(listOf("28"), rows.map { it.id })
        assertNull(rows.single().name)
        assertNull(rows.single().servers)
        assertTrue(rows.single().excluded)
        // Turned off altogether: nothing to switch.
        val omit = sample.replace("\t\t\"Regions\": {", "\t\t\"OmitDefaultRegions\": true,\n\t\t\"Regions\": {")
        assertTrue(DerpFile.read(omit)!!.omitDefaults)
        assertTrue(Relays.rows(DerpFile.read(omit)!!, map, devices).isEmpty())
    }

    @Test
    fun aRegionTheFileDefinesReplacesTailscalesOwn() {
        val own = sample.replace("\t\t\t\"28\": null,\n", "\t\t\t\"4\": {\"RegionID\": 4, \"RegionCode\": \"myfra\", \"RegionName\": \"My Frankfurt\", \"Nodes\": [{\"Name\": \"x\"}]},\n")
        val file = DerpFile.read(own)!!
        assertEquals(1, file.ownServing)
        assertEquals("My Frankfurt", file.ownEntries.single().name)
        val fra = Relays.rows(file, map, devices).first { it.id == "4" }
        assertTrue(fra.replaced)
        assertFalse(fra.excluded)
        assertFalse(fra.switchable)
    }

    @Test
    fun excludingAsksFirstForHomesAndRefusesTheLastRelay() {
        val file = DerpFile.read(sample)!!
        val rows = Relays.rows(file, map, devices)
        val fra = Relays.impact(rows, file, "4")
        assertEquals(2, fra.homes.size)
        assertTrue(fra.asks)
        assertFalse(fra.refused)
        assertEquals(3, fra.defaultsLeft)
        assertFalse(Relays.impact(rows, file, "1").asks)

        // Everything but New York excluded: New York is the last one.
        var text = sample
        for (id in listOf("4", "8", "22")) text = exclude(text, id)
        val left = DerpFile.read(text)!!
        val lastRows = Relays.rows(left, map, devices)
        assertEquals(1, Relays.serving(lastRows, left))
        assertTrue(Relays.impact(lastRows, left, "1").refused)

        // With a region of the file's own, the last of Tailscale's may go, with a strong warning.
        val withOwn = text.replace("\t\t\t\"28\": null,\n", "\t\t\t\"28\": null,\n\t\t\t\"900\": {\"RegionID\": 900, \"Nodes\": [{\"Name\": \"900a\"}]},\n")
        val ownFile = DerpFile.read(withOwn)!!
        val ownRows = Relays.rows(ownFile, map, devices)
        val nyc = Relays.impact(ownRows, ownFile, "1")
        assertFalse(nyc.refused)
        assertTrue(nyc.onlyOwn)
        assertTrue(nyc.asks)
    }

    @Test
    fun theFilesExclusionsSurviveAMapThatLostTheRegion() {
        val file = DerpFile.read(exclude(sample, "99"))!!
        val rows = Relays.rows(file, map, devices)
        val gone = rows.single { it.id == "99" }
        assertNull(gone.name)
        assertTrue(gone.excluded)
        assertEquals("the rest of the map is listed too", 6, rows.size)
        assertMeaning(removeAt(parsed(exclude(sample, "99")), listOf("derpMap", "Regions", "99")), sample)
    }
}
