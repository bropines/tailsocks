package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `/system/etc/hosts` bind: only ours ever comes off. */
class RootHostsTest {

    private val file = "/data/adb/tailshosts/hosts"

    @Test
    fun `our copy is recognised by its mount source or its marker, nothing else`() {
        val script = RootUtils.hostsUnbindScript(file, RootUtils.hostsMarker(file))
        assertTrue(script, script.contains("case \"\$top\" in '/data/adb/tailshosts/hosts'|'/adb/tailshosts/hosts') ours=1;; esac"))
        assertTrue(script, script.contains("grep -qxF '# TailSocks MagicDNS names, /data/adb/tailshosts/hosts' /system/etc/hosts"))
        // The only umount is behind the ours-test, and stops at the first one that fails.
        val umounts = script.lines().filter { it.contains("umount") }
        assertEquals(listOf("    umount /system/etc/hosts 2>/dev/null || break"), umounts)
        // Bounded: a mount that keeps coming back cannot loop the shell.
        assertTrue(script.contains("while [ \$i -lt 4 ]; do"))
    }

    @Test
    fun `the release and the debug build never take each other's copy for their own`() {
        assertFalse(RootUtils.hostsMarker("/data/adb/tailshosts-dev/hosts") == RootUtils.hostsMarker(file))
    }

    @Test
    fun `the stop's cleanup no longer unmounts whatever is there`() {
        val cleanup = RootUtils.routingCleanupScript()
        assertFalse(cleanup.lines().any { it.trim() == "umount /system/etc/hosts 2>/dev/null || true" })
        assertTrue(cleanup.contains("umount /system/etc/hosts 2>/dev/null || break"))
    }
}
