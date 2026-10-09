package io.github.bropines.tailscaled.admin.notify

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import java.util.concurrent.TimeUnit

/**
 * One periodic WorkManager job per admin profile whose background check is on, run only with
 * a network. WorkManager keeps them across reboots; [reconcile] puts them back after a
 * restored backup and drops the ones whose profile was deleted.
 */
object AttentionScheduler {
    private const val WORK_TAG = "admin-attention"

    fun workName(profileId: String) = "admin-attention-$profileId"

    /** Schedules or cancels [profileId]'s check as [checks] says; a new interval takes effect at once. */
    fun apply(context: Context, profileId: String, checks: AttentionChecks) {
        if (checks.enabled) schedule(context, profileId, checks.intervalMinutes, ExistingPeriodicWorkPolicy.UPDATE)
        else cancel(context, profileId)
    }

    fun cancel(context: Context, profileId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(profileId))
    }

    /** Blocking (preferences): off the main thread. */
    fun reconcile(context: Context) {
        val prefs = AttentionPrefs.of(context)
        val profiles = AdminProfiles.store(context).profiles().mapTo(mutableSetOf()) { it.id }
        for (id in prefs.profileIds()) {
            if (id !in profiles) {
                cancel(context, id)
                prefs.forget(id)
                continue
            }
            val checks = prefs.checks(id)
            if (checks.enabled) schedule(context, id, checks.intervalMinutes, ExistingPeriodicWorkPolicy.KEEP)
        }
    }

    private fun schedule(context: Context, profileId: String, minutes: Int, policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<AttentionWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(AttentionWorker.KEY_PROFILE to profileId))
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(workName(profileId), policy, request)
    }
}

/** How a notification opens the console on its own profile. */
object AttentionLaunch {
    const val EXTRA_PROFILE_ID = "io.github.bropines.tailscaled.admin.PROFILE_ID"

    /**
     * Makes the profile a notification was about the active one, before the console's
     * ViewModel reads which profile is active. Only for a fresh start of the console; an
     * unknown id changes nothing.
     */
    fun selectProfile(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() } ?: return
        val store = AdminProfiles.store(context)
        if (store.get(id) != null && store.activeId() != id) store.setActive(id)
    }
}
