package io.github.bropines.tailscaled.core

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import io.github.bropines.tailscaled.R

/**
 * The screen beneath a page, for the back gesture.
 *
 * Screens opened over the main one use Theme.Tailscaled.Page, whose window is translucent from
 * Android 13. Where the platform animates back between screens itself ([systemAnimatesBack]),
 * that is undone as each one is created — an opaque window, exactly as before. Where it does not
 * (HyperOS ships it off), the window stays translucent and PredictiveBackContainer shrinks the
 * page over the real screen beneath instead of over a blank, then lets it go at once.
 */
object BackUnderlay {
    fun watch(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val opaque = systemAnimatesBack(app)
        if (!opaque) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (isPage(activity)) activity.setTranslucent(false)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Whether [activity] is drawn over a translucent window: the real screen beneath shows through it. */
    fun showsThrough(activity: Activity?): Boolean =
        activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            isPage(activity) && !systemAnimatesBack(activity)

    private fun isPage(activity: Activity): Boolean = runCatching {
        activity.packageManager.getActivityInfo(activity.componentName, 0).themeResource == R.style.Theme_Tailscaled_Page
    }.getOrDefault(false)
}
