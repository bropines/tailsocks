package io.github.bropines.tailscaled.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.PermissionsActivity
import io.github.bropines.tailscaled.ui.RemoteElevateActivity
import io.github.bropines.tailscaled.ui.remoteEventLine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Remote management, what the managed device puts on its own screen because a paired device
 * asked or changed something (appctr/remote_app.go): a permission's page, "managed from
 * <name>", and a line for each change. Called by core/RemoteDeviceHost.kt on a thread of the
 * bridge's; nothing here may block for long, and whatever touches the screen is posted to the
 * main thread.
 *
 * A line is said in the app's own bar while one of its screens is in front
 * (core/RemoteDeviceNotice.kt, ui/RemoteNoticeBar.kt), and in a notification when none is. A
 * toast is left for a television with the app out of sight ([RemotePromptRules.said]).
 *
 * Nothing is ever granted from afar: [RemoteDevicePrompts.ask] brings Android's own page or
 * dialog in front of whoever is at the device, as far as Android lets an app do that
 * ([RemoteAskRoute.of]), and says which of the three happened. The page itself is opened by
 * the Permissions screen (ui/PermissionsActivity.kt), which shows who asks above its rows.
 */

/** The decisions of [RemoteDevicePrompts] that need no device, so that each of them has a test. */
internal object RemotePromptRules {
    /** How long a request waits for TailSocks to be opened: a page jumping out tomorrow would be a surprise. */
    const val ASK_WAITS_MS = 10 * 60_000L

    /** How long "managed from" stays among the notifications. */
    const val IDENTIFY_STAYS_MS = 30_000L

    /** How long "managed from", or a request, stays in the app's bar: a long toast's time, which it was. */
    const val IDENTIFY_BAR_MS = 3_500L

    /** How long a change stays there: a short toast's. */
    const val NOTICE_BAR_MS = 2_000L

    /** Changes closer together than this are said once: ten lines would queue for half a minute. */
    const val NOTICE_GAP_MS = 1_500L

    /** As much of a change as one line on the screen carries, after the device's name. */
    const val NOTICE_LINE_CHARS = 100

    /** The route left to a request that could not take [route] after all. */
    fun below(route: RemoteAskRoute, canNotify: Boolean): RemoteAskRoute = when (route) {
        RemoteAskRoute.SHOWN -> if (canNotify) RemoteAskRoute.NOTIFIED else RemoteAskRoute.WAITING
        RemoteAskRoute.NOTIFIED, RemoteAskRoute.WAITING -> RemoteAskRoute.WAITING
    }

    /**
     * How "managed from" is said. A line needs no activity started, so the Android version
     * does not come into it: over the app's own screen, else among the notifications, else
     * not at all — Android drops the line of an app in the background whose notifications
     * are off.
     */
    fun identifyRoute(onScreen: Boolean, canNotify: Boolean): RemoteAskRoute = when {
        onScreen -> RemoteAskRoute.SHOWN
        canNotify -> RemoteAskRoute.NOTIFIED
        else -> RemoteAskRoute.WAITING
    }

    /** Where a line is said; nowhere when nothing is set. */
    data class Said(val bar: Boolean = false, val notification: Boolean = false, val toast: Boolean = false)

    /**
     * Where the line of a request or of "managed from" is said, by the [route] it took. With
     * one of the app's screens in front ([onScreen]) it is the bar inside the app, on whatever
     * screen is open. With none it is the notification — and on a television ([tv]), which has
     * no shade to find one in, a toast over whatever is on as well: the one thing a person
     * there sees. A phone's notification is enough, and it gets no toast.
     *
     * The route keeps its meaning: [RemoteAskRoute.SHOWN] is the bar (or, for a request, the
     * page, which says it all itself), [RemoteAskRoute.NOTIFIED] always has its notification —
     * with the bar too when a request's page did not open over the app's own screen — and
     * [RemoteAskRoute.WAITING] is said nowhere.
     */
    fun said(route: RemoteAskRoute, onScreen: Boolean, tv: Boolean): Said = when (route) {
        RemoteAskRoute.SHOWN -> Said(bar = onScreen)
        RemoteAskRoute.NOTIFIED -> Said(bar = onScreen, notification = true, toast = tv && !onScreen)
        RemoteAskRoute.WAITING -> Said()
    }

    /** Whether a change is left unsaid: one was said less than [NOTICE_GAP_MS] before, at [previous]. */
    fun noticeDropped(previous: Long?, now: Long): Boolean = previous != null && now - previous in 0 until NOTICE_GAP_MS

    /**
     * "pixel-9-pro: Exit node: exit-frankfurt": who, and [line] — what it did, as the log of
     * the managed page says it (ui/RemoteLogLine.kt) — held to one short line.
     */
    fun noticeText(from: String, line: String): String {
        val short = noticeLine(line)
        return if (from.isBlank()) short else "$from: $short"
    }

    /** [line] as the bar's second line, under the device's name: one line, and short. */
    fun noticeLine(line: String): String {
        val one = line.replace(Regex("\\s+"), " ").trim()
        return if (one.length > NOTICE_LINE_CHARS) one.take(NOTICE_LINE_CHARS - 1) + "…" else one
    }

    /**
     * What [permission] is called where a request for it is shown; null for the VPN consent,
     * which is never asked from afar. Access to files is two different things by Android [sdk].
     */
    @StringRes
    fun title(permission: AppPermission, sdk: Int = Build.VERSION.SDK_INT): Int? = when (permission) {
        AppPermission.STORAGE ->
            if (sdk >= Build.VERSION_CODES.R) R.string.remote_prompt_perm_all_files else R.string.remote_prompt_perm_storage
        AppPermission.NOTIFICATIONS -> R.string.perm_notifications_title
        AppPermission.BATTERY -> R.string.perm_battery_title
        AppPermission.ALARMS -> R.string.perm_alarms_title
        AppPermission.INSTALL -> R.string.perm_install_title
        AppPermission.AUTOSTART -> R.string.perm_autostart_title
        AppPermission.VPN -> null
    }
}

/**
 * A request that could not be put in front of a person at once ([route] is why) and waits for
 * one of the app's screens. [at] is on the clock that keeps counting while the device sleeps.
 */
internal data class PendingAsk(val permission: AppPermission, val from: String, val route: RemoteAskRoute, val at: Long) {
    /** Young enough to be shown still. */
    fun alive(now: Long): Boolean = now - at in 0 until RemotePromptRules.ASK_WAITS_MS

    /** The request as the report says it; null once it is [granted] some other way, or too old. */
    fun report(now: Long, granted: Boolean): RemoteDeviceAsked? =
        if (alive(now) && !granted) RemoteDeviceAsked(id = permission.id, result = route.word, age = (now - at) / 1000) else null
}

/** A guest's request for full rights that waits for one of the app's screens, as a [PendingAsk] does. */
internal data class PendingElevate(val nodeId: String, val from: String, val seconds: Long, val at: Long) {
    fun alive(now: Long): Boolean = now - at in 0 until RemotePromptRules.ASK_WAITS_MS
}

/**
 * Whether one of the app's own screens is in front, counted from the activities' resumes and
 * pauses. An activity resumed before the watch began was never counted: until the first
 * callback the process answers instead, and that activity's pause, when it comes, takes
 * nothing off.
 */
internal class ScreenWatch {
    private var resumed = 0
    private var told = false

    @Synchronized
    fun resumed() {
        told = true
        resumed++
    }

    @Synchronized
    fun paused() {
        told = true
        if (resumed > 0) resumed--
    }

    @Synchronized
    fun onScreen(foregroundProcess: () -> Boolean): Boolean = resumed > 0 || (!told && foregroundProcess())
}

object RemoteDevicePrompts {
    private const val TAG = "RemotePrompts"

    /** A channel of its own, of high importance: a request comes up over whatever is on the screen. */
    private const val CHANNEL_ID = "tailsocks_remote_requests"
    private const val NOTIF_ASK = 0x7E40
    private const val NOTIF_IDENTIFY = 0x7E41
    private const val NOTIF_ELEVATE = 0x7E42

    private val lock = Any()

    /** The request that waits, one at most: a newer one takes its place. Guarded by [lock]. */
    private var pending: PendingAsk? = null

    /** The same for a guest's request for full rights. Guarded by [lock]. */
    private var pendingElevate: PendingElevate? = null

    /** When a change was last said. Guarded by [lock]. */
    private var noticeAt: Long? = null

    private val screen = ScreenWatch()
    private val watching = AtomicBoolean(false)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Brings [permission]'s page or dialog in front of whoever is at the device, naming
     * [from] as the device that asks: at once where Android lets it, otherwise through a
     * notification — with a line in the app's bar, or on a television's screen — and in any
     * case when TailSocks is next opened within a few minutes.
     */
    fun ask(context: Context, permission: AppPermission, from: String): RemoteAskRoute {
        val app = context.applicationContext
        watch(app)
        val res = inAppLanguage(app)
        // The VPN consent has no page and no place on the Permissions screen; the bridge and
        // the host both refuse it before this is reached.
        val title = RemotePromptRules.title(permission)?.let { res.getString(it) } ?: return RemoteAskRoute.WAITING
        val who = who(res, from)
        val canNotify = canNotify(app)
        val onScreen = onScreen()
        var route = RemoteAskRoute.of(Build.VERSION.SDK_INT, onScreen, canNotify)
        if (route == RemoteAskRoute.SHOWN && !app.tryStartActivity(PermissionsActivity.askIntent(app, permission, from))) {
            route = RemotePromptRules.below(route, canNotify)
        }
        synchronized(lock) {
            pending = if (route == RemoteAskRoute.SHOWN) null else PendingAsk(permission, from, route, now())
        }
        if (route == RemoteAskRoute.NOTIFIED) {
            notify(
                app, res, NOTIF_ASK,
                title = res.getString(R.string.remote_prompt_ask_title, who),
                text = res.getString(R.string.remote_prompt_ask_text, title),
                staysMs = RemotePromptRules.ASK_WAITS_MS,
                // The person's own press may start what the app may not.
                tap = PendingIntent.getActivity(
                    app, NOTIF_ASK, PermissionsActivity.askIntent(app, permission, from),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            say(
                app, RemotePromptRules.said(route, onScreen, isTv(app)), RemoteDeviceNotice.Kind.ASK,
                from = who,
                text = res.getString(R.string.remote_notice_ask, title),
                spoken = res.getString(R.string.remote_prompt_ask_toast, who, title),
                staysMs = RemotePromptRules.IDENTIFY_BAR_MS,
            )
        } else {
            // The notification of a request this one has replaced would name the wrong thing.
            cancel(app, NOTIF_ASK)
        }
        Log.i(TAG, "A paired device asks for ${permission.id}: ${route.word}")
        return route
    }

    /** The request made by [ask] that still waits for a person, for the report; null when none does. */
    fun asked(context: Context): RemoteDeviceAsked? {
        val app = context.applicationContext
        watch(app)
        val ask = synchronized(lock) { pending } ?: return null
        ask.report(now(), granted(app, ask.permission))?.let { return it }
        // Given some other way, or its ten minutes are over: it is not shown later either.
        synchronized(lock) { if (pending === ask) pending = null }
        cancel(app, NOTIF_ASK)
        return null
    }

    /**
     * The Permissions screen has taken the request for [permission] — from the notification,
     * or from [ask] itself: it waits for nobody any more.
     */
    fun opened(context: Context, permission: AppPermission) {
        synchronized(lock) {
            if (pending?.permission != permission) return
            pending = null
        }
        cancel(context.applicationContext, NOTIF_ASK)
    }

    /**
     * A guest, [from], asks for full rights for [seconds] (0: until its pairing ends): the question
     * that gives them (RemoteElevateActivity) is put in front of whoever is at the device the way
     * a permission's page is ([ask]) — at once where Android lets it, otherwise through a
     * notification and a line in the bar, and when TailSocks is next opened within a few
     * minutes. Nothing is given here: the question takes the device's lock.
     */
    fun elevateAsk(context: Context, nodeId: String, from: String, seconds: Long): RemoteAskRoute {
        val app = context.applicationContext
        watch(app)
        val res = inAppLanguage(app)
        val who = who(res, from)
        val length = RemoteElevateText.length(res, seconds)
        val canNotify = canNotify(app)
        val onScreen = onScreen()
        val question = RemoteElevateActivity.askIntent(app, nodeId, from, seconds)
        var route = RemoteAskRoute.of(Build.VERSION.SDK_INT, onScreen, canNotify)
        if (route == RemoteAskRoute.SHOWN && !app.tryStartActivity(question)) route = RemotePromptRules.below(route, canNotify)
        synchronized(lock) {
            pendingElevate = if (route == RemoteAskRoute.SHOWN) null else PendingElevate(nodeId, from, seconds, now())
        }
        if (route == RemoteAskRoute.NOTIFIED) {
            notify(
                app, res, NOTIF_ELEVATE,
                title = res.getString(R.string.remote_elevate_title_asked, who),
                text = res.getString(R.string.remote_elevate_ask_text, length),
                staysMs = RemotePromptRules.ASK_WAITS_MS,
                // The person's own press may start what the app may not.
                tap = PendingIntent.getActivity(app, NOTIF_ELEVATE, question, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            say(
                app, RemotePromptRules.said(route, onScreen, isTv(app)), RemoteDeviceNotice.Kind.ASK,
                from = who,
                text = res.getString(R.string.remote_notice_elevate_ask, length),
                spoken = res.getString(R.string.remote_elevate_ask_toast, who, length),
                staysMs = RemotePromptRules.IDENTIFY_BAR_MS,
            )
        } else {
            cancel(app, NOTIF_ELEVATE)
        }
        Log.i(TAG, "A guest asks for full rights for ${seconds}s: ${route.word}")
        return route
    }

    /** The question for [nodeId]'s request is in front of a person: it waits for nobody any more. */
    fun elevateOpened(context: Context, nodeId: String) {
        synchronized(lock) {
            if (pendingElevate?.nodeId != nodeId) return
            pendingElevate = null
        }
        cancel(context.applicationContext, NOTIF_ELEVATE)
    }

    /** Says "managed from [from]" for a few seconds. */
    fun identify(context: Context, from: String): RemoteAskRoute {
        val app = context.applicationContext
        watch(app)
        val onScreen = onScreen()
        val route = RemotePromptRules.identifyRoute(onScreen, canNotify(app))
        if (route == RemoteAskRoute.WAITING) return route
        val res = inAppLanguage(app)
        val who = who(res, from)
        val line = res.getString(R.string.remote_prompt_identify, who)
        val said = RemotePromptRules.said(route, onScreen, isTv(app))
        if (said.notification) {
            notify(
                app, res, NOTIF_IDENTIFY,
                title = line,
                text = res.getString(R.string.remote_title),
                staysMs = RemotePromptRules.IDENTIFY_STAYS_MS,
                tap = null,
            )
        }
        say(
            app, said, RemoteDeviceNotice.Kind.IDENTIFY,
            from = who,
            text = res.getString(R.string.remote_notice_identify),
            spoken = line,
            staysMs = RemotePromptRules.IDENTIFY_BAR_MS,
        )
        return route
    }

    /**
     * Says, while one of the app's screens is up, that [from] changed something: [op] and
     * [detail] are the line of the "recent remote changes" log (RemoteActionsJSON), and it is
     * said as the managed page's log says it (ui/RemoteLogLine.kt): one wording for one thing.
     */
    fun notice(context: Context, from: String, op: String, detail: String) {
        val app = context.applicationContext
        watch(app)
        if (!onScreen()) return
        val now = now()
        synchronized(lock) {
            if (RemotePromptRules.noticeDropped(noticeAt, now)) return
            noticeAt = now
        }
        val res = inAppLanguage(app)
        // The bridge says only what was done; a line that could not be put together is not
        // replaced by the bridge's own keys.
        val line = runCatching { remoteEventLine(op, detail, ok = true) { res.getString(it) } }.getOrNull() ?: return
        say(
            app, RemotePromptRules.Said(bar = true), RemoteDeviceNotice.Kind.CHANGE,
            from = who(res, from),
            text = RemotePromptRules.noticeLine(line),
            spoken = RemotePromptRules.noticeText(from, line),
            staysMs = RemotePromptRules.NOTICE_BAR_MS,
        )
    }

    /**
     * Says a line where [said] has it: [from] and [text] are the two lines of the app's bar,
     * which takes down whatever it was showing; [spoken] is the same in one sentence, for a
     * screen reader and for a television's toast. The notification is the caller's.
     */
    private fun say(
        app: Context,
        said: RemotePromptRules.Said,
        kind: RemoteDeviceNotice.Kind,
        from: String,
        text: String,
        spoken: String,
        staysMs: Long,
    ) {
        if (said.bar) RemoteDeviceNotices.board.post(kind, from, text, spoken, readingTime(app, staysMs))
        if (said.toast) toast(app, spoken)
    }

    /** [staysMs], or as much longer as the person has asked of the system for things that go away by themselves. */
    private fun readingTime(app: Context, staysMs: Long): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return staysMs
        return runCatching {
            val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val content = AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT
            manager.getRecommendedTimeoutMillis(staysMs.toInt(), content).toLong()
        }.getOrDefault(staysMs).coerceAtLeast(staysMs)
    }

    /** A television, by the Interface setting too: where a notification alone may never reach a person. */
    private fun isTv(app: Context): Boolean = runCatching { DeviceProfile.deviceClass(app) == DeviceClass.TV }.getOrDefault(false)

    /** The asking device by name; the bridge always has one, and a line with a hole in it would say nothing. */
    private fun who(res: Context, from: String): String = from.ifBlank { res.getString(R.string.remote_prompt_someone) }

    // ─── Which screen is in front ────────────────────────────────────────────────────────────

    /** Starts counting the app's own screens, once: the bridge's first call is where it begins. */
    private fun watch(app: Context) {
        val application = app as? Application ?: return
        if (!watching.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = resumed(activity)
            override fun onActivityPaused(activity: Activity) = screen.paused()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun onScreen(): Boolean = screen.onScreen(::foregroundProcess)

    /** With an activity in front the process is the foreground one; the service alone keeps it a step below. */
    private fun foregroundProcess(): Boolean = runCatching {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * One of the app's screens came to the front: the request that waited for it is shown
     * now, once — unless it has outlived its time or was answered some other way.
     */
    private fun resumed(activity: Activity) {
        screen.resumed()
        resumedElevate(activity)
        val ask = synchronized(lock) { pending.also { pending = null } } ?: return
        val app = activity.applicationContext
        cancel(app, NOTIF_ASK)
        if (!ask.alive(now()) || granted(app, ask.permission)) return
        // Once the resume is through: this is called from the middle of it.
        main.post { app.tryStartActivity(PermissionsActivity.askIntent(app, ask.permission, ask.from)) }
    }

    /** The same for a guest's request for full rights: its question comes up once, while it is young. */
    private fun resumedElevate(activity: Activity) {
        val ask = synchronized(lock) { pendingElevate.also { pendingElevate = null } } ?: return
        val app = activity.applicationContext
        cancel(app, NOTIF_ELEVATE)
        if (!ask.alive(now())) return
        main.post { app.tryStartActivity(RemoteElevateActivity.askIntent(app, ask.nodeId, ask.from, ask.seconds)) }
    }

    private fun granted(app: Context, permission: AppPermission): Boolean =
        runCatching { AppPermissions.state(app, permission) == PermState.GRANTED }.getOrDefault(false)

    private fun now(): Long = SystemClock.elapsedRealtime()

    // ─── Notifications and lines ─────────────────────────────────────────────────────────────

    /** Notifications are on for the app, and this channel has not been switched off by itself. */
    private fun canNotify(app: Context): Boolean = runCatching {
        val manager = NotificationManagerCompat.from(app)
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannelCompat(CHANNEL_ID)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }.getOrDefault(false)

    /** [res] is [app] in the app's language: the channel's name is read by a person too. */
    private fun notify(app: Context, res: Context, id: Int, title: String, text: String, staysMs: Long, tap: PendingIntent?) {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, res.getString(R.string.remote_prompt_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // Before Android 8 there are no channels: the priority and a sound are what bring it up.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(staysMs)
            .build()
        // Without POST_NOTIFICATIONS on 13+ the system drops it; canNotify was asked first.
        try { manager.notify(id, notification) } catch (e: Exception) { Log.w(TAG, "notify: ${e.message}") }
    }

    private fun cancel(app: Context, id: Int) {
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(id) }
    }

    /**
     * One line over whatever is on a television's screen while the app is out of sight; it
     * takes no focus from a remote and goes away by itself. Made on the main thread: the
     * callers are the bridge's.
     */
    private fun toast(app: Context, line: String) {
        main.post { runCatching { Toast.makeText(app, line, Toast.LENGTH_LONG).show() } }
    }

    /** [context] in the language picked in Settings; the application's own is in the system's (as in TaildropEvents). */
    internal fun inAppLanguage(context: Context): Context {
        val lang = GlobalSettings.getString(context, "app_locale", "sys")
        if (lang == "sys") return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(lang))
        return context.createConfigurationContext(config)
    }
}
