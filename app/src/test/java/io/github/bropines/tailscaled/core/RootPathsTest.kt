package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Root-side file names per package: the release app keeps its own, every other package gets a suffix. */
class RootPathsTest {

    private val release = RootPaths.forPackage("io.github.bropines.tailscaled")
    private val dev = RootPaths.forPackage("io.github.bropines.tailscaled.dev")

    private fun asset(name: String): String {
        val module = listOf(File("."), File("app")).first { File(it, "src/main/assets/scripts/$name").isFile }
        return File(module, "src/main/assets/scripts/$name").readText()
    }

    @Test
    fun `the release package keeps every path it has always had`() {
        assertEquals("", release.suffix)
        assertEquals("tailscale", release.cliName)
        assertEquals("/system/bin/tailscale", release.systemCli)
        assertEquals("/product/bin/tailscale", release.productCli)
        assertEquals("/data/adb/service.d/tailscale", release.serviceDCli)
        assertEquals("tailscaled", release.moduleId)
        assertEquals("/data/adb/modules/tailscaled/system/bin/tailscale", release.moduleCli)
        assertEquals("/data/adb/service.d/tailscaled.sh", release.serviceScript)
        assertEquals("/data/adb/tailsocks", release.envDir)
        assertEquals("/data/adb/tailsocks/control_proxy.env", release.envFile)
        assertEquals("/data/adb/tailshosts/hosts", release.hostsFile)
    }

    @Test
    fun `the release module prop is the text it has always been`() {
        assertEquals(
            "id=tailscaled\nname=TailSocks CLI Integration\nversion=v1.0\nversionCode=100\n" +
                "author=TailSocks\ndescription=Tailscale CLI binary overlay\n",
            release.moduleProp
        )
    }

    @Test
    fun `the debug build gets -dev names everywhere`() {
        assertEquals("-dev", dev.suffix)
        assertEquals("tailscale-dev", dev.cliName)
        assertEquals("/system/bin/tailscale-dev", dev.systemCli)
        assertEquals("/product/bin/tailscale-dev", dev.productCli)
        assertEquals("/data/adb/service.d/tailscale-dev", dev.serviceDCli)
        assertEquals("tailscaled-dev", dev.moduleId)
        assertEquals("/data/adb/modules/tailscaled-dev/system/bin/tailscale-dev", dev.moduleCli)
        assertEquals("/data/adb/service.d/tailscaled-dev.sh", dev.serviceScript)
        assertEquals("/data/adb/tailsocks-dev", dev.envDir)
        assertEquals("/data/adb/tailsocks-dev/control_proxy.env", dev.envFile)
        assertEquals("/data/adb/tailshosts-dev/hosts", dev.hostsFile)
        assertTrue(dev.moduleProp.startsWith("id=tailscaled-dev\nname=TailSocks CLI Integration (io.github.bropines.tailscaled.dev)\n"))
    }

    @Test
    fun `no path of one package is a path of the other`() {
        val releaseFiles = release.cliPaths + listOf(release.serviceScript, release.envFile, release.hostsFile, release.moduleDir)
        val devFiles = dev.cliPaths + listOf(dev.serviceScript, dev.envFile, dev.hostsFile, dev.moduleDir)
        assertTrue(releaseFiles.intersect(devFiles.toSet()).isEmpty())
        // Not even as a directory prefix: removing one package's directory must not reach into the other's.
        for (r in releaseFiles) for (d in devFiles) {
            assertFalse("$d is under $r", d.startsWith("$r/"))
            assertFalse("$r is under $d", r.startsWith("$d/"))
        }
    }

    @Test
    fun `other packages get a suffix that is safe as a file name and a module id`() {
        assertEquals("-beta-2", RootPaths.suffixFor("io.github.bropines.tailscaled.beta_2"))
        assertEquals("-com-example-fork", RootPaths.suffixFor("com.example.Fork"))
        assertEquals("-other", RootPaths.suffixFor(""))
        val moduleId = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")
        for (pkg in listOf("io.github.bropines.tailscaled.dev", "com.example.Fork", "io.github.bropines.tailscaled.x.y")) {
            val paths = RootPaths.forPackage(pkg)
            assertTrue(paths.moduleId, moduleId.matches(paths.moduleId))
            assertTrue(paths.suffix, Regex("^-[a-z0-9-]+$").matches(paths.suffix))
        }
    }

    @Test
    fun `removing the debug build's CLI touches only its own files`() {
        val script = dev.cliRemoveScript()
        for (path in dev.cliPaths) assertTrue(path, script.contains("\"$path\""))
        for (path in release.cliPaths) assertFalse(path, script.contains("\"$path\""))
        assertFalse(script.contains("modules/tailscaled/"))
    }

    @Test
    fun `installing and checking the debug build's CLI never names the release's files`() {
        val install = dev.cliInstallScript("/data/user/0/io.github.bropines.tailscaled.dev/cache/w.sh")
        val check = dev.cliCheckScript()
        for (path in release.cliPaths) {
            assertFalse(path, install.contains("\"$path\""))
            assertFalse(path, check.contains("\"$path\""))
        }
        assertFalse(install.contains("/modules/tailscaled/"))
        assertTrue(install.contains("cat > \"${dev.moduleDir}/module.prop\" <<'TAILSOCKS_MODULE_EOF'\n${dev.moduleProp}TAILSOCKS_MODULE_EOF\n"))
        for (path in dev.cliPaths) assertTrue(path, check.contains("[ -f \"$path\" ]"))
    }

    @Test
    fun `the release's CLI install writes the four places it always did`() {
        val install = release.cliInstallScript("/tmp/w.sh")
        for (path in release.cliPaths) assertTrue(path, install.contains("\"$path\""))
        assertTrue(install.contains("rm -f \"/data/adb/modules/tailscaled/disable\" \"/data/adb/modules/tailscaled/remove\""))
    }

    @Test
    fun `the boot script reads its own package's env file and starts no other package`() {
        val template = asset("tailscaled.sh")
        val bin = "/data/app/~~a/io.github.bropines.tailscaled.dev-b/lib/arm64/libtailscale.so"
        val script = RootBootScript.render(template, dev.packageName, bin, "5.0.0-dev")
        assertTrue(script.contains("ENV_FILE=\"${dev.envFile}\""))
        assertFalse(script.contains(release.envFile))
        assertFalse(script.contains("%ENV_FILE%"))
        // The only package the script names is the one that installed it.
        assertFalse(script.contains("PKG=\"${release.packageName}\""))
        assertEquals(1, Regex("^PKG=", RegexOption.MULTILINE).findAll(script).count())

        val releaseBin = "/data/app/~~a/io.github.bropines.tailscaled-b/lib/arm64/libtailscale.so"
        val releaseScript = RootBootScript.render(template, release.packageName, releaseBin, "5.0.0")
        assertTrue(releaseScript.contains("ENV_FILE=\"/data/adb/tailsocks/control_proxy.env\""))
        assertFalse(releaseScript.contains("tailscaled.dev"))
    }

    @Test
    fun `the CLI wrapper talks to its own package's socket only`() {
        val wrapper = asset("tailscale_cli.sh").replace("%PKG_NAME%", dev.packageName)
        assertTrue(wrapper.contains("PKG=\"${dev.packageName}\""))
        assertEquals(1, Regex("^PKG=", RegexOption.MULTILINE).findAll(wrapper).count())
        assertTrue(wrapper.contains("SOCKET_PATH=\"/data/data/\$PKG/files/tailscaled.sock\""))
        assertFalse(wrapper.contains("PKG=\"${release.packageName}\""))
    }

    @Test
    fun `the saved rp_filter value stays where the shared ruleset has always kept it`() {
        assertEquals("/data/adb/tailsocks/rp_filter.orig", RootPaths.RP_FILTER_ORIG)
    }
}
