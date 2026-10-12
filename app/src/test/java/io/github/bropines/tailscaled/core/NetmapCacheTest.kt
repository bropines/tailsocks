package io.github.bropines.tailscaled.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a backup leaves out of `files/states`: the daemon's netmap cache and nothing else. */
class NetmapCacheTest {

    @Test
    fun cacheFilesAreRecognised() {
        assertTrue(NetmapCache.isCachePath("default/profile-data/a1b2/netmap-cache/73656c66"))
        assertTrue(NetmapCache.isCachePath("acc-2/profile-data/9f/netmap-cache/sub/peer-01"))
        // A backup built on Windows-style separators must not slip through.
        assertTrue(NetmapCache.isCachePath("acc\\profile-data\\9f\\netmap-cache\\73656c66"))
    }

    @Test
    fun stateIsKept() {
        assertFalse(NetmapCache.isCachePath("default/tailscaled.state"))
        assertFalse(NetmapCache.isCachePath("default/tailscaled.log.conf"))
        // Other per-profile data (favorites, service prefs, Taildrive) stays in the backup.
        assertFalse(NetmapCache.isCachePath("default/profile-data/a1b2/favorites/abc"))
        assertFalse(NetmapCache.isCachePath("default/profile-data/a1b2/serviceclientprefs/abc"))
        // Only the daemon's own layout counts, not any folder that happens to share the name.
        assertFalse(NetmapCache.isCachePath("default/netmap-cache/x"))
        assertFalse(NetmapCache.isCachePath("default/profile-data/netmap-cache/x"))
        assertFalse(NetmapCache.isCachePath("default/profile-data/a1b2/netmap-cache"))
    }
}
