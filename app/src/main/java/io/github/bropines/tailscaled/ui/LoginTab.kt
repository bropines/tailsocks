package io.github.bropines.tailscaled.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import io.github.bropines.tailscaled.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The login page in a Custom Tab: over the app instead of out in the browser,
 * still with the browser's own session — its cookies, passkeys and password
 * manager — and gone again once the login has gone through. The main screen
 * calls [finished] when the backend reaches Running.
 *
 * Closing it is up to what the tab reports: bound to the browser's Custom Tabs
 * service, the session says when the tab is shown and when it is hidden, so a
 * tab the user has left for another app is never pulled back over that app. A
 * browser without the service, or one that does not answer in time, gets the
 * page all the same and is simply left open. With no Custom Tabs browser at
 * all, the link opens the ordinary way.
 */
object LoginTab {
    private const val TAG = "LoginTab"

    /** How long a browser gets to bind before the page opens without a session. */
    private const val BIND_WAIT_MS = 1500L

    /** A login finished later than this after the tab opened no longer closes it. */
    private const val CLOSE_WINDOW_MS = 15 * 60_000L

    @Volatile private var openedAt = 0L
    @Volatile private var shown = false
    private var connection: CustomTabsServiceConnection? = null

    fun open(activity: Activity, url: String, @ColorInt toolbarColor: Int, dark: Boolean) {
        val uri = Uri.parse(url)
        val browser = runCatching { CustomTabsClient.getPackageName(activity, null) }.getOrNull()
        if (browser == null) {
            openPlain(activity, uri)
            return
        }
        val app = activity.applicationContext
        unbind(app)

        val launched = AtomicBoolean(false)
        fun launch(session: CustomTabsSession?) {
            if (!launched.compareAndSet(false, true)) return
            activity.runOnUiThread {
                runCatching {
                    val tab = CustomTabsIntent.Builder(session)
                        .setShowTitle(true)
                        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                        .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
                        .setDefaultColorSchemeParams(
                            CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build()
                        )
                        .build()
                    tab.intent.setPackage(browser)
                    tab.launchUrl(activity, uri)
                    openedAt = System.currentTimeMillis()
                    // With a session the tab's own events keep this true; without
                    // one nothing would, so it never counts as shown.
                    shown = session != null
                }.onFailure {
                    Log.w(TAG, "Custom Tab did not open, using the browser", it)
                    openPlain(activity, uri)
                }
            }
        }

        val callback = object : CustomTabsCallback() {
            override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                when (navigationEvent) {
                    TAB_SHOWN -> shown = true
                    TAB_HIDDEN -> shown = false
                }
            }
        }
        val conn = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                launch(runCatching { client.newSession(callback) }.getOrNull())
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = runCatching { CustomTabsClient.bindCustomTabsService(app, browser, conn) }.getOrDefault(false)
        if (bound) {
            connection = conn
            Handler(Looper.getMainLooper()).postDelayed({ launch(null) }, BIND_WAIT_MS)
        } else {
            launch(null)
        }
    }

    /**
     * The login went through. Brings [activity] back over the tab — which
     * closes it, the tab being on top of it in this task — when the tab is
     * the one on screen. Cheap to call when no tab was opened.
     */
    fun finished(activity: Activity) {
        if (openedAt == 0L) return
        val bringBack = shown && System.currentTimeMillis() - openedAt < CLOSE_WINDOW_MS
        openedAt = 0L
        shown = false
        unbind(activity.applicationContext)
        if (bringBack) {
            activity.startActivity(
                Intent(activity, activity.javaClass)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun openPlain(activity: Activity, uri: Uri) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(activity, activity.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show() }
    }

    private fun unbind(context: Context) {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }
}
