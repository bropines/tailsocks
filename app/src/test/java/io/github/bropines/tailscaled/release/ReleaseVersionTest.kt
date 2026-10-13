package io.github.bropines.tailscaled.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/*
 * The rule between VERSION_NAME and VERSION_CODE (buildSrc: ReleaseVersion, compiled into these
 * tests by app/build.gradle.kts). What an update is to Android is a higher code, so the rule
 * has one thing to promise: codes go up in the order versions come out — every beta above
 * every release and beta before it, a release above its own betas — with room left for the
 * 1 to 4 an APK adds for its ABI, and without moving a code that is already published.
 */
class ReleaseVersionTest {
    private fun code(name: String) = ReleaseVersion.parse(name).code()

    /** Every APK of [earlier] — the universal one and the four ABIs — installs under every APK of [later]. */
    private fun assertUpdates(earlier: String, later: String) {
        assertTrue("$later (${code(later)}) must be an update over every APK of $earlier (${code(earlier)})",
            code(earlier) + ReleaseVersion.MAX_ABI_OFFSET < code(later))
    }

    @Test
    fun aPublishedCodeDoesNotMove() {
        // Every release since version.properties exists, and the one that is coming.
        val published = mapOf(
            "4.5.2" to 4050200, "4.5.3" to 4050300, "4.6.0" to 4060000, "4.6.1" to 4060100, "4.6.2" to 4060200,
            "4.7.0" to 4070000, "4.7.1" to 4070100, "4.7.2" to 4070200, "4.7.3" to 4070300, "4.7.4" to 4070400,
            "4.7.5" to 4070500, "4.8.0" to 4080000, "4.9.0" to 4090000, "4.9.1" to 4090100, "4.10.0" to 4100000,
            "4.10.1" to 4100100, "4.10.2" to 4100200, "5.0.0" to 5000000,
        )
        for ((name, code) in published) {
            assertEquals(name, code, code(name))
            assertFalse(name, ReleaseVersion.parse(name).isBeta)
        }
        // The codes before them were a commit count that ended near 1540.
        assertTrue(published.values.min() > 1540 + ReleaseVersion.MAX_ABI_OFFSET)
    }

    @Test
    fun theWorkedExampleOfTheDocument() {
        assertEquals(5000010, code("5.0.1-beta.1"))
        assertEquals(5000100, code("5.0.1"))
        assertEquals(5009910, code("5.1.0-beta.1"))
        assertEquals(5009920, code("5.1.0-beta.2"))
        assertEquals(5009990, code("5.1.0-beta.9"))
        assertEquals(5010000, code("5.1.0"))
        assertEquals(5999910, code("6.0.0-beta.1"))
        assertEquals(6000000, code("6.0.0"))
    }

    @Test
    fun codesGoUpInTheOrderVersionsComeOut() {
        val history = listOf(
            "4.10.1", "4.10.2", "5.0.0",
            "5.0.1-beta.1", "5.0.1-beta.2", "5.0.1",
            "5.1.0-beta.1", "5.1.0-beta.2", "5.1.0-beta.9", "5.1.0",
            "5.1.1-beta.1", "5.1.1", "5.1.2",
            "5.2.0-beta.1", "5.2.0",
            "6.0.0-beta.1", "6.0.0-beta.9", "6.0.0",
        )
        for (i in history.indices) for (j in i + 1 until history.size) assertUpdates(history[i], history[j])
    }

    /**
     * The same for every version the code has room for: a version with its nine betas takes
     * the codes from its own minus 90 to its own plus 4, and stays clear of the version below
     * — at patch 0 of a minor and at minor 0 of a major too, where the betas reach under the
     * round number.
     */
    @Test
    fun noVersionReachesIntoTheOneBelow() {
        var below: String? = null
        for (major in 4..6) for (minor in 0..99) for (patch in 0..99) {
            val release = "$major.$minor.$patch"
            val steps = (1..ReleaseVersion.MAX_BETA).map { "$release-beta.$it" } + release
            (listOfNotNull(below) + steps).zipWithNext { earlier, later -> assertUpdates(earlier, later) }
            below = release
        }
    }

    @Test
    fun aNameIsAReleaseOrOneOfItsNineBetas() {
        val beta = ReleaseVersion.parse("5.1.0-beta.3")
        assertTrue(beta.isBeta)
        assertEquals(listOf(5, 1, 0, 3), listOf(beta.major, beta.minor, beta.patch, beta.beta))
        assertEquals("5.1.0-beta.3", beta.name())
        assertEquals("12.34.56", ReleaseVersion.parse("12.34.56").name())

        val refused = listOf(
            // The tenth beta would have the release's own code; beta 0 the code of the patch below.
            "5.1.0-beta.10", "5.1.0-beta.0",
            // What a debug build and the tags of 2.x and 3.x were called is not what a release is.
            "5.1.0-beta", "5.1.0-dev", "5.1.0-beta.1-dev", "5.1.0-rc.1", "v5.1.0", "5.1", "5.1.0.1", "5.01.0", "", " 5.1.0",
            // No room in the code.
            "5.100.0", "5.1.100", "2100.0.0", "0.0.0", "0.0.0-beta.1",
        )
        for (name in refused) {
            assertThrows(name, IllegalArgumentException::class.java) { ReleaseVersion.parse(name) }
        }
    }

    @Test
    fun theCodeMustBeTheOneTheNameGives() {
        assertEquals(5010000, ReleaseVersion.check("5.1.0", 5010000).code())
        assertEquals(5009910, ReleaseVersion.check("v5.1.0-beta.1", "5.1.0-beta.1", 5009910).code())

        // A beta numbered as a release would outrank the release it is a beta of.
        val wrongCode = assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.check("5.1.0-beta.1", 5010010) }
        assertTrue(wrongCode.message, "5009910" in wrongCode.message!!)
        assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.check("5.1.0", 5009910) }
        // The tag is the name with a v, whatever the name is.
        val wrongTag = assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.check("v5.1.0", "5.1.0-beta.1", 5009910) }
        assertTrue(wrongTag.message, "does not match VERSION_NAME=5.1.0-beta.1" in wrongTag.message!!)
        assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.check("5.1.0", "5.1.0", 5010000) }
    }

    private val versionProperties: File = run {
        val module = listOf(File("."), File("app")).first { File(it, "src/main/res/values/strings.xml").isFile }
        File(module.canonicalFile.parentFile, "version.properties")
    }

    @Test
    fun versionPropertiesFollowsTheRule() {
        val file = Properties().apply { versionProperties.inputStream().use { load(it) } }
        ReleaseVersion.check(file.getProperty("VERSION_NAME"), file.getProperty("VERSION_CODE").toInt())
    }

    /**
     * F-Droid finds a release by searching the whole file with the two patterns of its recipe
     * (UpdateCheckData) and taking the first match of each — a comment that spells an
     * assignment out above the real one would be read in its place.
     */
    @Test
    fun fdroidReadsTheSameTwoLines() {
        val text = versionProperties.readText()
        val file = Properties().apply { load(text.reader()) }
        assertEquals(file.getProperty("VERSION_CODE"), Regex("""VERSION_CODE=(\d+)""").find(text)?.groupValues?.get(1))
        // Of a beta it would read the release's name, which is why its update check never
        // looks at a beta tag (docs/RELEASING.md).
        val name = ReleaseVersion.parse(file.getProperty("VERSION_NAME"))
        assertEquals("${name.major}.${name.minor}.${name.patch}", Regex("""VERSION_NAME=([\d.]+)""").find(text)?.groupValues?.get(1))
    }
}
