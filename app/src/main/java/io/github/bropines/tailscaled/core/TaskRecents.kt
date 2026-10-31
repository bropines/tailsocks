package io.github.bropines.tailscaled.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * Settings → Background & permissions → Hide from recents: the app's tasks leave the recent
 * apps list. Only the card goes; activities, the service and the daemon keep running.
 *
 * Applied to every task when the switch or a restored backup changes it, and on each activity
 * start, so a cold start and a task opened later (a share, a widget's screen) follow it too.
 */
internal object TaskRecents {
    fun watch(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = apply(activity)
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun apply(context: Context) {
        val hidden = GlobalSettings.isHideFromRecents(context)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        // A task can finish between the list and the call; that one throws and is skipped.
        runCatching { manager.appTasks }.getOrNull()?.forEach { task ->
            runCatching { task.setExcludeFromRecents(hidden) }
        }
    }
}
