package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.release.ReleaseVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The order of versions as the app sees them: its own name against a tag on GitHub. The
 * updater offers what this order calls newer, so a beta must come before its release and
 * after everything older, and no name the app was ever published under may read wrong.
 */
class AppVersionTest {
    private fun version(name: String) = AppVersion.parse(name) ?: error("$name is not read as a version")

    @Test
    fun aBetaComesBeforeItsReleaseAndAfterTheOneBefore() {
        val inOrder = listOf("5.0.0", "5.0.1", "5.1.0-beta.1", "5.1.0-beta.2", "5.1.0-beta.10", "5.1.0", "5.1.1-beta.1", "5.1.1", "5.10.0", "6.0.0-beta.1", "6.0.0")
        for (i in inOrder.indices) for (j in inOrder.indices) {
            assertEquals("${inOrder[i]} against ${inOrder[j]}", i.compareTo(j), version(inOrder[i]).compareTo(version(inOrder[j])))
            assertEquals("${inOrder[i]} -> ${inOrder[j]}", i < j, isVersionNewer(inOrder[i], inOrder[j]))
        }
    }

    /** `git tag --sort=creatordate`, 2026-10-14: every release there has been, oldest first. */
    private val everyTagSoFar = listOf(
        "v1.1.0", "v1.2.0", "v1.2.1", "v1.2.2", "v1.2.3", "v1.2.4", "v1.2.5", "v1.3.0", "v1.3.1", "v1.4.0", "v1.4.1",
        "v1.4.2", "v1.5.0", "v1.5.1", "v1.5.2", "v1.7.0", "v1.7.1", "v1.8.0", "v1.9.0", "v2.0.0-beta", "v2.0.1-beta",
        "v2.0.2-beta", "v2.0.3-beta", "v2.0.4-beta", "v2.0.5-beta", "v2.0.7-beta", "v2.0.8-beta", "v2.0.9-beta",
        "v2.1.0-beta", "v2.1.1-beta", "v2.1.2-beta", "v2.1.3-beta", "v2.1.4-beta", "v2.2.0-beta", "v2.2.1", "v2.3.0",
        "v2.3.1-beta", "v3.0.0-beta", "v3.0.1", "v3.0.2", "v3.1.0", "v3.1.1", "v3.1.2", "v3.1.3", "v3.1.4", "v3.1.5",
        "v3.1.6", "v3.1.7", "v3.1.8-beta", "v3.2.0", "v3.3.0", "v3.4.0", "v3.5.0", "v3.5.1", "v3.5.2", "v3.5.3",
        "v3.5.4", "v4.0.0", "v4.1.0", "v4.1.1", "v4.1.2", "v4.2.0", "v4.2.1", "v4.2.2", "v4.2.3", "v4.3.0", "v4.4.0",
        "v4.4.1", "v4.4.2", "v4.4.3", "v4.4.4", "v4.5.0", "v4.5.1", "v4.5.2", "v4.5.3", "v4.6.0", "v4.6.1", "v4.6.2",
        "v4.7.0", "v4.7.1", "v4.7.2", "v4.7.3", "v4.7.4", "v4.7.5", "v4.8.0", "v4.9.0", "v4.9.1", "v4.10.0", "v4.10.1",
        "v4.10.2",
    )

    @Test
    fun everyReleaseSoFarIsNewerThanTheOneBeforeIt() {
        assertEquals(90, everyTagSoFar.size)
        everyTagSoFar.zipWithNext { earlier, later ->
            assertTrue("$later after $earlier", isVersionNewer(earlier, later))
            assertFalse("$earlier after $later", isVersionNewer(later, earlier))
        }
        for (tag in everyTagSoFar) {
            assertFalse(tag, isVersionNewer(tag, tag))
            // The "-beta" of 2.x and 3.x was part of an ordinary release's name, with no number
            // and no release to come after it: none of them is a beta of this channel.
            assertFalse(tag, version(tag).isBeta)
            // And each is older than whatever comes next, a beta included.
            assertTrue(tag, isVersionNewer(tag, "v5.0.0"))
            assertTrue(tag, isVersionNewer(tag, "v4.10.3-beta.1"))
        }
    }

    @Test
    fun whatStandsAroundAVersionIsReadPast() {
        assertEquals(AppVersion(5, 1, 0), version("v5.1.0"))
        // A debug build of a release, and of a beta.
        assertEquals(AppVersion(4, 10, 2), version("4.10.2-dev"))
        assertEquals(AppVersion(5, 1, 0, beta = 2), version("5.1.0-beta.2-dev"))
        // What builds up to 4.5.1 called themselves.
        assertEquals(AppVersion(4, 5, 1), version("v4.5.1-c76dbb.release"))
        assertEquals("5.1.0-beta.2", version("v5.1.0-beta.2-dev").toString())
        assertEquals("4.10.2", version("v4.10.2").toString())
        assertTrue(AppVersion.isBeta("5.1.0-beta.2-dev"))
        assertFalse(AppVersion.isBeta("5.1.0"))
        assertFalse(AppVersion.isBeta("?"))

        // A debug build is not older or newer than the release it was built from.
        assertFalse(isVersionNewer("5.1.0-dev", "v5.1.0"))
        assertFalse(isVersionNewer("5.1.0", "v5.1.0-dev"))
        assertTrue(isVersionNewer("5.1.0-beta.2-dev", "v5.1.0"))
        assertTrue(isVersionNewer("5.1.0-beta.2-dev", "v5.1.0-beta.3"))
        assertFalse(isVersionNewer("5.1.0-beta.2-dev", "v5.1.0-beta.2"))
    }

    @Test
    fun whatIsNotAVersionIsNeverAnUpdate() {
        for (name in listOf("", "?", "latest", "5.1", "v5", "beta", "5.1.x")) assertNull(name, AppVersion.parse(name))
        assertFalse(isVersionNewer("5.0.0", "nightly"))
        assertFalse(isVersionNewer("?", "nightly"))
        // An installed name that cannot be read is older than any release, as "0.0.0" always was.
        assertTrue(isVersionNewer("?", "v5.0.0"))
        assertTrue(isVersionNewer("0.0.0", "v1.1.0"))
    }

    /**
     * Android goes by the code and the app by the name: what one calls an update the other
     * must too. The build's rule (buildSrc: ReleaseVersion) gives the codes.
     */
    @Test
    fun theOrderOfNamesIsTheOrderOfCodes() {
        val names = ArrayList<String>()
        for (major in 4..6) for (minor in listOf(0, 1, 9, 10, 99)) for (patch in listOf(0, 1, 2, 10, 99)) {
            (1..ReleaseVersion.MAX_BETA).mapTo(names) { "$major.$minor.$patch-beta.$it" }
            names += "$major.$minor.$patch"
        }
        val byName = names.sortedBy { version(it) }
        val byCode = names.sortedBy { ReleaseVersion.parse(it).code() }
        assertEquals(byCode, byName)
        // Written oldest first, so both orders are the order they were written in.
        assertEquals(names, byName)
        assertEquals(names.size, names.map { ReleaseVersion.parse(it).code() }.toSet().size)
        for (name in names) assertEquals(name, version(name).toString())
    }
}
