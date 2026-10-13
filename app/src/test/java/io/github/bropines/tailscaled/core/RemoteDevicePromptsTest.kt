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

    @Test
    fun everyChangeTheBridgeReportsHasAWord() {
        assertEquals(R.string.remote_op_exit_node, RemotePromptRules.opWord("exit_node"))
        assertEquals(R.string.remote_op_setting, RemotePromptRules.opWord("setting"))
        assertEquals(R.string.remote_op_action, RemotePromptRules.opWord("action"))
        assertEquals(R.string.remote_op_file_deleted, RemotePromptRules.opWord("file_deleted"))
        assertEquals(R.string.remote_prompt_op_reverted, RemotePromptRules.opWord("reverted"))
        assertEquals(R.string.remote_prompt_op_share_added, RemotePromptRules.opWord("share_added"))
        assertEquals(R.string.remote_prompt_op_share_removed, RemotePromptRules.opWord("share_removed"))
        assertEquals(R.string.remote_prompt_op_file_sent, RemotePromptRules.opWord("file_sent"))
        // One the app has not heard of is shown as the bridge names it.
        assertNull(RemotePromptRules.opWord("something_new"))
        assertNull(RemotePromptRules.opWord(""))
    }

    @Test
    fun theLineIsTheDeviceTheWordAndTheBridgesDetail() {
        assertEquals("pixel-9-pro: Exit node · exit-frankfurt", RemotePromptRules.noticeText("pixel-9-pro", "Exit node", "exit-frankfurt"))
        assertEquals("pixel-9-pro: Setting · tun_mode=true", RemotePromptRules.noticeText("pixel-9-pro", "Setting", "tun_mode=true"))
        assertEquals(
            "pixel-9-pro: Change undone · exit_node: not confirmed",
            RemotePromptRules.noticeText("pixel-9-pro", "Change undone", "exit_node: not confirmed"),
        )
        assertEquals("pixel-9-pro: something_new", RemotePromptRules.noticeText("pixel-9-pro", "something_new", ""))
        assertEquals("pixel-9-pro: Action", RemotePromptRules.noticeText("pixel-9-pro", "Action", "  "))
        // A device with no name still says what happened.
        assertEquals("File sent · a.jpg", RemotePromptRules.noticeText("", "File sent", "a.jpg"))
    }

    @Test
    fun aLongOrManyLinedDetailStaysOneShortLine() {
        val line = RemotePromptRules.noticeText("tv", "Setting", "advertise_routes=" + "10.0.0.0/8,".repeat(40))
        assertFalse(line.contains('\n'))
        assertTrue(line.endsWith("…"))
        assertEquals("tv: Setting · ".length + RemotePromptRules.NOTICE_DETAIL_CHARS, line.length)
        assertEquals("tv: File deleted · a b", RemotePromptRules.noticeText("tv", "File deleted", "a\nb"))
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
