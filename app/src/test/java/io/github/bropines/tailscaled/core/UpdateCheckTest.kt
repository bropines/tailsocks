package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.UpdateCheck.Asset
import io.github.bropines.tailscaled.core.UpdateCheck.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Which release the updater offers. Two things decide: what is installed, and whether the
 * owner asked for betas ("Receive beta versions", off unless switched on).
 */
class UpdateCheckTest {
    private fun stable(tag: String) = Release(tag)
    private fun beta(tag: String) = Release(tag, prerelease = true)

    /** GitHub lists the newest first; 5.1.0 is still in beta, 5.0.1 is the latest release. */
    private val whileInBeta = listOf(beta("v5.1.0-beta.2"), beta("v5.1.0-beta.1"), stable("v5.0.1"), stable("v5.0.0"), stable("v4.10.2"))

    /** The same list once 5.1.0 is out. */
    private val afterTheRelease = listOf(stable("v5.1.0")) + whileInBeta

    private fun offered(installed: String, releases: List<Release>, betas: Boolean) = UpdateCheck.offer(installed, releases, betas)?.tag

    @Test
    fun aStableBuildWithoutBetasIsOfferedReleasesOnly() {
        assertEquals("v5.0.1", offered("5.0.0", whileInBeta, betas = false))
        assertNull(offered("5.0.1", whileInBeta, betas = false))
        assertEquals("v5.1.0", offered("5.0.1", afterTheRelease, betas = false))
        assertNull(offered("5.1.0", afterTheRelease, betas = false))
    }

    @Test
    fun aStableBuildWithBetasIsOfferedTheNewestOfBoth() {
        assertEquals("v5.1.0-beta.2", offered("5.0.0", whileInBeta, betas = true))
        assertEquals("v5.1.0-beta.2", offered("5.0.1", whileInBeta, betas = true))
        // The release outranks its own betas, whichever was published last.
        assertEquals("v5.1.0", offered("5.0.1", afterTheRelease, betas = true))
        assertNull(offered("5.1.0", afterTheRelease, betas = true))
    }

    @Test
    fun aBetaBuildWithoutBetasWaitsForTheReleaseThatIsNewer() {
        // 5.0.1 is the latest release and older than the beta: nothing to go back to.
        assertNull(offered("5.1.0-beta.1", whileInBeta, betas = false))
        assertNull(offered("5.1.0-beta.2", whileInBeta, betas = false))
        assertEquals("v5.1.0", offered("5.1.0-beta.1", afterTheRelease, betas = false))
        assertEquals("v5.1.0", offered("5.1.0-beta.2", afterTheRelease, betas = false))
    }

    @Test
    fun aBetaBuildWithBetasIsOfferedTheNextBetaAndThenTheRelease() {
        assertEquals("v5.1.0-beta.2", offered("5.1.0-beta.1", whileInBeta, betas = true))
        assertNull(offered("5.1.0-beta.2", whileInBeta, betas = true))
        assertEquals("v5.1.0", offered("5.1.0-beta.2", afterTheRelease, betas = true))
        // A debug build of a beta is that beta.
        assertNull(offered("5.1.0-beta.2-dev", whileInBeta, betas = true))
    }

    @Test
    fun aPreReleaseOlderThanTheInstalledReleaseIsNotOffered() {
        // A beta of a patch to the line before, published after 5.1.0: first in GitHub's list.
        val aLateBeta = listOf(beta("v5.0.2-beta.1")) + afterTheRelease
        assertNull(offered("5.1.0", aLateBeta, betas = true))
        assertNull(offered("5.1.0", aLateBeta, betas = false))
        assertEquals("v5.1.0", UpdateCheck.newest(aLateBeta, betas = true)?.tag)
        // Whoever is still on the line before is offered the newest there is, not that beta.
        assertEquals("v5.1.0", offered("5.0.1", aLateBeta, betas = true))
        // And a beta of the very version that is installed is behind it.
        assertNull(offered("5.1.0", listOf(beta("v5.1.0-beta.3"), stable("v5.1.0")), betas = true))
        assertNull(offered("5.1.0", listOf(beta("v5.1.0-beta.3")), betas = true))
    }

    @Test
    fun whatIsNotAReleaseIsNotOffered() {
        val draft = Release("v9.0.0", draft = true)
        assertNull(offered("5.0.0", listOf(draft), betas = true))
        assertNull(offered("5.0.0", listOf(Release("nightly"), Release("latest")), betas = true))
        // A beta by its name is a beta even when GitHub was not told so: without the switch
        // it is not offered, although "latest" would then be it.
        assertNull(offered("5.0.0", listOf(Release("v5.1.0-beta.1")), betas = false))
        assertEquals("v5.1.0-beta.1", offered("5.0.0", listOf(Release("v5.1.0-beta.1")), betas = true))
        // And one GitHub marks as a pre-release is, whatever it is called.
        assertNull(offered("5.0.0", listOf(Release("v5.1.0", prerelease = true)), betas = false))
        assertNull(UpdateCheck.newest(emptyList(), betas = true))
    }

    @Test
    fun githubsAnswerIsReadWhetherItIsOneReleaseOrAList() {
        val one = UpdateCheck.parse(LATEST)
        assertEquals(listOf("v5.0.0"), one.map { it.tag })
        assertFalse(one.single().isBeta)
        assertEquals(2, one.single().assets.size)

        val list = UpdateCheck.parse("[$BETA,$LATEST,{\"name\":\"no tag\"}]")
        assertEquals(listOf("v5.1.0-beta.1", "v5.0.0"), list.map { it.tag })
        assertTrue(list.first().prerelease)
        assertEquals("v5.1.0-beta.1", UpdateCheck.offer("5.0.0", list, betas = true)?.tag)
        assertNull(UpdateCheck.offer("5.0.0", list, betas = false))
        assertEquals("https://github.com/bropines/tailsocks/releases/tag/v5.1.0-beta.1", UpdateCheck.page(list.first().tag))
    }

    @Test
    fun theApkIsTheOneOfTheDevicesAbi() {
        fun apk(abi: String) = Asset("TailSocks-v5.1.0-$abi-release.apk", "https://example.test/$abi.apk")
        val release = Release("v5.1.0", assets = listOf(Asset("notes.txt", "https://example.test/notes"), apk("x86_64"), apk("arm64-v8a"), apk("armeabi-v7a"), apk("universal"), apk("x86")))
        assertEquals("https://example.test/arm64-v8a.apk", UpdateCheck.apkFor(release, "arm64-v8a"))
        // Not the x86_64 one, which has "x86" in its name and comes first.
        assertEquals("https://example.test/x86.apk", UpdateCheck.apkFor(release, "x86"))
        assertEquals("https://example.test/x86_64.apk", UpdateCheck.apkFor(release, "x86_64"))
        // An ABI no APK is named for, or none at all: whichever APK there is.
        assertEquals("https://example.test/x86_64.apk", UpdateCheck.apkFor(release, "riscv64"))
        assertEquals("https://example.test/x86_64.apk", UpdateCheck.apkFor(release, ""))
        assertNull(UpdateCheck.apkFor(Release("v5.1.0"), "arm64-v8a"))
    }

    private companion object {
        const val LATEST = """{"tag_name":"v5.0.0","draft":false,"prerelease":false,"html_url":"https://github.com/bropines/tailsocks/releases/tag/v5.0.0",
            "assets":[{"name":"TailSocks-v5.0.0-arm64-v8a-release.apk","browser_download_url":"https://example.test/a.apk"},
                      {"name":"TailSocks-v5.0.0-universal-release.apk","browser_download_url":"https://example.test/u.apk"}]}"""
        const val BETA = """{"tag_name":"v5.1.0-beta.1","draft":false,"prerelease":true,"assets":[]}"""
    }
}
