package io.github.bropines.tailscaled.admin.notify

import io.github.bropines.tailscaled.admin.attention.AttentionItem
import io.github.bropines.tailscaled.admin.attention.AttentionKind
import io.github.bropines.tailscaled.admin.attention.AttentionSource
import io.github.bropines.tailscaled.admin.secure.MemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionNotifyOnceTest {

    private val all = AttentionSource.entries.toSet()

    private fun item(kind: AttentionKind, id: String, expires: String? = null) = AttentionItem(kind, id, id, expires = expires)

    private val laptop = item(AttentionKind.DEVICE_APPROVAL, "nLAPTOP")
    private val jordan = item(AttentionKind.USER_APPROVAL, "uJORDAN")
    private val nasKey = item(AttentionKind.DEVICE_KEY_EXPIRING, "nNAS", "2026-10-12T00:00:00Z")
    private val update = item(AttentionKind.UPDATE_AVAILABLE, "nPI")
    private val routes = item(AttentionKind.ROUTES_PENDING, "nROUTER")

    @Test
    fun theFirstCheckNotifiesWhatNotifiesAndRemembersIt() {
        val d = AttentionNotifyOnce.diff(emptySet(), listOf(laptop, jordan, nasKey, update, routes), all)
        assertEquals(listOf(laptop, jordan, nasKey), d.fresh)
        assertEquals(setOf(laptop.notifyKey, jordan.notifyKey, nasKey.notifyKey), d.seen)
        assertTrue(d.resolved.isEmpty())
    }

    @Test
    fun theSameItemsAgainAreNotNews() {
        val first = AttentionNotifyOnce.diff(emptySet(), listOf(laptop, jordan, nasKey), all)
        val second = AttentionNotifyOnce.diff(first.seen, listOf(jordan, nasKey, laptop), all)
        assertTrue(second.fresh.isEmpty())
        assertEquals(first.seen, second.seen)
    }

    @Test
    fun anApprovedDeviceIsResolvedAndForgotten() {
        val seen = setOf(laptop.notifyKey, jordan.notifyKey)
        val d = AttentionNotifyOnce.diff(seen, listOf(jordan), all)
        assertEquals(setOf(laptop.notifyKey), d.resolved)
        assertEquals(setOf(jordan.notifyKey), d.seen)
        // Should it ask again (deleted and back), it is news again.
        assertEquals(listOf(laptop), AttentionNotifyOnce.diff(d.seen, listOf(jordan, laptop), all).fresh)
    }

    @Test
    fun aSourceThatWasNotReadForgetsNothing() {
        val seen = setOf(laptop.notifyKey, jordan.notifyKey)
        // The users could not be read this time: no user items, and the remembered one stays.
        val d = AttentionNotifyOnce.diff(seen, listOf(laptop), all - AttentionSource.USERS)
        assertTrue(d.resolved.isEmpty())
        assertEquals(seen, d.seen)
        // The next check that reads them does not notify Jordan a second time.
        assertTrue(AttentionNotifyOnce.diff(d.seen, listOf(laptop, jordan), all).fresh.isEmpty())
    }

    @Test
    fun itemsFromAnUnreadSourceAreNotNotified() {
        val d = AttentionNotifyOnce.diff(emptySet(), listOf(laptop, jordan), setOf(AttentionSource.DEVICES))
        assertEquals(listOf(laptop), d.fresh)
        assertEquals(setOf(laptop.notifyKey), d.seen)
    }

    @Test
    fun aRenewedKeyThatComesUpAgainIsNews() {
        val first = AttentionNotifyOnce.diff(emptySet(), listOf(nasKey), all)
        val renewed = item(AttentionKind.DEVICE_KEY_EXPIRING, "nNAS", "2027-04-10T00:00:00Z")
        val d = AttentionNotifyOnce.diff(first.seen, listOf(renewed), all)
        assertEquals(listOf(renewed), d.fresh)
        assertEquals(setOf(nasKey.notifyKey), d.resolved)
        assertEquals(setOf(renewed.notifyKey), d.seen)
    }

    @Test
    fun keysThisVersionCannotPlaceAreDropped() {
        val d = AttentionNotifyOnce.diff(setOf("SOMETHING_NEW:x", laptop.notifyKey), listOf(laptop), all)
        assertEquals(setOf(laptop.notifyKey), d.seen)
        assertTrue(d.resolved.isEmpty())
    }

    @Test
    fun turningChecksOnTakesWhatIsOnScreenAsSeen() {
        val onScreen = listOf(laptop, nasKey, update)
        val seed = AttentionNotifyOnce.seed(onScreen)
        assertEquals(setOf(laptop.notifyKey, nasKey.notifyKey), seed)
        assertTrue(AttentionNotifyOnce.diff(seed, onScreen, all).fresh.isEmpty())
        assertEquals(listOf(jordan), AttentionNotifyOnce.diff(seed, onScreen + jordan, all).fresh)
    }

    @Test
    fun prefsKeepEachProfileApart() {
        val prefs = AttentionPrefs(MemoryKeyValueStore())
        assertEquals(AttentionChecks(enabled = false, intervalMinutes = 60), prefs.checks("home"))
        prefs.setChecks("home", AttentionChecks(enabled = true, intervalMinutes = 15))
        prefs.setSeen("home", setOf(laptop.notifyKey, jordan.notifyKey))
        prefs.setChecks("work", AttentionChecks(enabled = false, intervalMinutes = 360))
        assertEquals(AttentionChecks(true, 15), prefs.checks("home"))
        assertEquals(AttentionChecks(false, 360), prefs.checks("work"))
        assertEquals(setOf(laptop.notifyKey, jordan.notifyKey), prefs.seen("home"))
        assertTrue(prefs.seen("work").isEmpty())
        assertEquals(setOf("home", "work"), prefs.profileIds())
        prefs.forget("home")
        assertEquals(AttentionChecks(), prefs.checks("home"))
        assertTrue(prefs.seen("home").isEmpty())
        assertEquals(setOf("work"), prefs.profileIds())
    }

    @Test
    fun anIntervalNotOfferedFallsBackToHourly() {
        val store = MemoryKeyValueStore(mapOf("p/checks" to "1", "p/interval" to "7"))
        assertEquals(AttentionChecks(true, 60), AttentionPrefs(store).checks("p"))
    }
}
