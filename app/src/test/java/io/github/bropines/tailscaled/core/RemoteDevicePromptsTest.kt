package io.github.bropines.tailscaled.core

import android.os.Build
import io.github.bropines.tailscaled.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the managed device decides before it shows anything of a paired device's request
 * (core/RemoteDevicePrompts.kt): which way a request takes when the first is closed, how long
 * it waits, which changes are said aloud and in what words. The showing itself needs a device.
 */
class RemoteDevicePromptsTest {

    private fun ask(route: RemoteAskRoute = RemoteAskRoute.NOTIFIED, at: Long = 1_000) =
        PendingAsk(AppPermission.STORAGE, "pixel-9-pro", route, at)

    // ─── The route ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aRouteThatCannotBeTakenGivesWayToTheNextOne() {
        assertEquals(RemoteAskRoute.NOTIFIED, RemotePromptRules.below(RemoteAskRoute.SHOWN, canNotify = true))
        assertEquals(RemoteAskRoute.WAITING, RemotePromptRules.below(RemoteAskRoute.SHOWN, canNotify = false))
        assertEquals(RemoteAskRoute.WAITING, RemotePromptRules.below(RemoteAskRoute.NOTIFIED, canNotify = true))
        // Nothing is below waiting, whatever the device allows.
        assertEquals(RemoteAskRoute.WAITING, RemotePromptRules.below(RemoteAskRoute.WAITING, canNotify = true))
        assertEquals(RemoteAskRoute.WAITING, RemotePromptRules.below(RemoteAskRoute.WAITING, canNotify = false))
    }

    @Test
    fun aPageThatDidNotOpenOnAnOldAndroidStillEndsInANotification() {
        // Below Android 10 a page is started from the background; when that start fails the
        // request goes the way it would have gone on a newer Android.
        val first = RemoteAskRoute.of(Build.VERSION_CODES.P, onScreen = false, canNotify = true)
        assertEquals(RemoteAskRoute.SHOWN, first)
        assertEquals(
            RemoteAskRoute.of(Build.VERSION_CODES.Q, onScreen = false, canNotify = true),
            RemotePromptRules.below(first, canNotify = true),
        )
    }

    @Test
    fun identifyingNeedsTheScreenOrANotificationWhateverTheAndroid() {
        assertEquals(RemoteAskRoute.SHOWN, RemotePromptRules.identifyRoute(onScreen = true, canNotify = false))
        assertEquals(RemoteAskRoute.SHOWN, RemotePromptRules.identifyRoute(onScreen = true, canNotify = true))
        assertEquals(RemoteAskRoute.NOTIFIED, RemotePromptRules.identifyRoute(onScreen = false, canNotify = true))
        assertEquals(RemoteAskRoute.WAITING, RemotePromptRules.identifyRoute(onScreen = false, canNotify = false))
        // Which the bridge is told as "unseen".
        assertEquals("unseen", RemotePromptRules.identifyRoute(onScreen = false, canNotify = false).identifyWord())
    }

    // ─── Where a line is said ────────────────────────────────────────────────────────────────

    private val inTheBar = RemotePromptRules.Said(bar = true)
    private val notified = RemotePromptRules.Said(notification = true)
    private val nowhere = RemotePromptRules.Said()

    /** "Managed from", as [RemoteDevicePrompts.identify] routes it. */
    private fun identifySaid(onScreen: Boolean, canNotify: Boolean, tv: Boolean) =
        RemotePromptRules.said(RemotePromptRules.identifyRoute(onScreen, canNotify), onScreen, tv)

    @Test
    fun withTheAppInFrontTheLineIsInItsBarAndNowhereElse() {
        for (tv in listOf(false, true)) for (canNotify in listOf(false, true)) {
            assertEquals("tv=$tv canNotify=$canNotify", inTheBar, identifySaid(onScreen = true, canNotify = canNotify, tv = tv))
        }
        // Which the manager is told as "shown".
        assertEquals("shown", RemotePromptRules.identifyRoute(onScreen = true, canNotify = false).identifyWord())
    }

    @Test
    fun withTheAppOutOfSightAPhoneGetsTheNotificationAndNoToast() {
        assertEquals(notified, identifySaid(onScreen = false, canNotify = true, tv = false))
        assertEquals("notified", RemotePromptRules.identifyRoute(onScreen = false, canNotify = true).identifyWord())
    }

    @Test
    fun aTelevisionWithTheAppOutOfSightGetsTheNotificationAndAToast() {
        // It has no shade to find a notification in: the toast is the one thing a person sees.
        assertEquals(
            RemotePromptRules.Said(notification = true, toast = true),
            identifySaid(onScreen = false, canNotify = true, tv = true),
        )
    }

    @Test
    fun whereNothingCanBeShownNothingIsSaidOnAnyDevice() {
        // Android drops the toast of an app in the background whose notifications are off.
        assertEquals(nowhere, identifySaid(onScreen = false, canNotify = false, tv = false))
        assertEquals(nowhere, identifySaid(onScreen = false, canNotify = false, tv = true))
        assertEquals(nowhere, RemotePromptRules.said(RemoteAskRoute.WAITING, onScreen = true, tv = true))
    }

    @Test
    fun aRequestWhosePageDidNotOpenOverTheAppIsNotifiedAndSaidInTheBar() {
        // The app is in front, the page could not be started: the notification opens it at a
        // press, and the bar says so on the screen the person is looking at — on any device.
        val route = RemotePromptRules.below(RemoteAskRoute.SHOWN, canNotify = true)
        val said = RemotePromptRules.Said(bar = true, notification = true)
        assertEquals(said, RemotePromptRules.said(route, onScreen = true, tv = false))
        assertEquals(said, RemotePromptRules.said(route, onScreen = true, tv = true))
        // Out of sight it is a request like "managed from": notified, and a toast on a television.
        assertEquals(notified, RemotePromptRules.said(route, onScreen = false, tv = false))
        assertEquals(notified.copy(toast = true), RemotePromptRules.said(route, onScreen = false, tv = true))
    }

    @Test
    fun aToastIsNeverSaidTogetherWithTheBar() {
        for (route in RemoteAskRoute.entries) for (onScreen in listOf(false, true)) for (tv in listOf(false, true)) {
            val said = RemotePromptRules.said(route, onScreen, tv)
            assertFalse("$route onScreen=$onScreen tv=$tv", said.bar && said.toast)
            // The bar is inside the app, and only there.
            if (said.bar) assertTrue(onScreen)
            // A notification is what "notified" means, no more and no less.
            assertEquals(route == RemoteAskRoute.NOTIFIED, said.notification)
        }
    }

    @Test
    fun everyPermissionButTheVpnConsentHasANameToBeAskedBy() {
        AppPermission.entries.forEach { permission ->
            val title = RemotePromptRules.title(permission, Build.VERSION_CODES.BAKLAVA)
            if (permission == AppPermission.VPN) assertNull(title) else assertNotNull(permission.id, title)
        }
        // Access to files is two different grants, and is called by the one this Android has.
        assertEquals(R.string.remote_prompt_perm_all_files, RemotePromptRules.title(AppPermission.STORAGE, Build.VERSION_CODES.R))
        assertEquals(R.string.remote_prompt_perm_storage, RemotePromptRules.title(AppPermission.STORAGE, Build.VERSION_CODES.Q))
    }

    // ─── A request that waits ────────────────────────────────────────────────────────────────

    @Test
    fun aRequestWaitsTenMinutesAndNotASecondMore() {
        val ask = ask(at = 5_000)
        assertTrue(ask.alive(5_000))
        assertTrue(ask.alive(5_000 + RemotePromptRules.ASK_WAITS_MS - 1))
        assertFalse(ask.alive(5_000 + RemotePromptRules.ASK_WAITS_MS))
        assertFalse(ask.alive(5_000 + 24 * 3_600_000L))
        // A clock that went back is not a reason to show a page.
        assertFalse(ask.alive(4_999))
        assertEquals(600_000L, RemotePromptRules.ASK_WAITS_MS)
    }

    @Test
    fun theReportNamesAWaitingRequestItsRouteAndItsAge() {
        val waiting = ask(RemoteAskRoute.WAITING, at = 10_000)
        assertEquals(RemoteDeviceAsked(id = "storage", result = "waiting", age = 0), waiting.report(10_000, granted = false))
        assertEquals(RemoteDeviceAsked(id = "storage", result = "waiting", age = 42), waiting.report(52_999, granted = false))
        assertEquals("notified", ask(RemoteAskRoute.NOTIFIED).report(2_000, granted = false)?.result)
        assertEquals(
            "notifications",
            PendingAsk(AppPermission.NOTIFICATIONS, "tv", RemoteAskRoute.WAITING, 0).report(1, granted = false)?.id,
        )
    }

    @Test
    fun aRequestThatWasAnsweredOrOutlivedItsTimeIsNotReported() {
        val ask = ask(at = 0)
        assertNull(ask.report(30_000, granted = true))
        assertNull(ask.report(RemotePromptRules.ASK_WAITS_MS, granted = false))
        assertEquals(599L, ask.report(RemotePromptRules.ASK_WAITS_MS - 1, granted = false)?.age)
    }

    // ─── Which of the app's screens is in front ──────────────────────────────────────────────

    @Test
    fun beforeTheFirstCallbackTheProcessAnswersForTheScreen() {
        val watch = ScreenWatch()
        assertTrue(watch.onScreen { true })
        assertFalse(watch.onScreen { false })
    }

    @Test
    fun afterACallbackOnlyResumedActivitiesCount() {
        val watch = ScreenWatch()
        watch.resumed()
        assertTrue(watch.onScreen { false })
        watch.paused()
        // The process is still "foreground" for a moment after Home; the activity is not.
        assertFalse(watch.onScreen { true })
        watch.resumed()
        watch.resumed()
        watch.paused()
        assertTrue(watch.onScreen { false })
    }

    @Test
    fun anActivityResumedBeforeTheWatchBeganIsNotCountedTwiceWhenItLeaves() {
        val watch = ScreenWatch()
        // It was up before anyone watched: its pause is the first thing heard of it.
        watch.paused()
        assertFalse(watch.onScreen { true })
        watch.resumed()
        assertTrue(watch.onScreen { false })
        watch.paused()
        assertFalse(watch.onScreen { false })
    }

    // ─── A change, in one line ───────────────────────────────────────────────────────────────

    // What the line says is the log's own (ui/RemoteLogLineTest); here, how it is put on the screen.

    @Test
    fun theLineIsTheDeviceAndWhatItDid() {
        assertEquals("pixel-9-pro: Exit node: exit-frankfurt", RemotePromptRules.noticeText("pixel-9-pro", "Exit node: exit-frankfurt"))
        assertEquals("pixel-9-pro: Action", RemotePromptRules.noticeText("pixel-9-pro", "  Action "))
        // A device with no name still says what happened.
        assertEquals("File sent to it: a.jpg", RemotePromptRules.noticeText("", "File sent to it: a.jpg"))
    }

    @Test
    fun aLongOrManyLinedChangeStaysOneShortLine() {
        val line = RemotePromptRules.noticeText("tv", "Setting: the subnet routes it offers (" + "10.0.0.0/8,".repeat(40) + ")")
        assertFalse(line.contains('\n'))
        assertTrue(line.endsWith("…"))
        assertEquals("tv: ".length + RemotePromptRules.NOTICE_LINE_CHARS, line.length)
        assertEquals("tv: File deleted: a b", RemotePromptRules.noticeText("tv", "File deleted: a\nb"))
    }

    @Test
    fun theBarsSecondLineIsTheChangeWithoutTheDevice() {
        // The device's name is the bar's first line; the sentence a screen reader says has both.
        assertEquals("Exit node: exit-frankfurt", RemotePromptRules.noticeLine(" Exit node:\texit-frankfurt\n"))
        val long = RemotePromptRules.noticeLine("x".repeat(500))
        assertEquals(RemotePromptRules.NOTICE_LINE_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("tv: " + RemotePromptRules.noticeLine("a  b"), RemotePromptRules.noticeText("tv", "a  b"))
    }

    @Test
    fun changesThatComeInABurstAreSaidOnce() {
        assertFalse(RemotePromptRules.noticeDropped(previous = null, now = 0))
        assertTrue(RemotePromptRules.noticeDropped(previous = 10_000, now = 10_000))
        assertTrue(RemotePromptRules.noticeDropped(previous = 10_000, now = 10_000 + RemotePromptRules.NOTICE_GAP_MS - 1))
        assertFalse(RemotePromptRules.noticeDropped(previous = 10_000, now = 10_000 + RemotePromptRules.NOTICE_GAP_MS))
        // Ten in a second: the first is said, the nine after it are not.
        var shown: Long? = null
        val said = (0 until 10).count { i ->
            val now = 50_000L + i * 100
            val dropped = RemotePromptRules.noticeDropped(shown, now)
            if (!dropped) shown = now
            !dropped
        }
        assertEquals(1, said)
    }
}
