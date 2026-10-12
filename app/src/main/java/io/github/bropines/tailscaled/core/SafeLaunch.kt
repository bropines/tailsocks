package io.github.bropines.tailscaled.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat

/*
 * Launching what the device may not have. A phone always has a file picker, a
 * browser and every settings page; a television often has none of them. Some
 * have no handler, and startActivity throws; most have a placeholder that
 * resolves and does nothing. These answer false in both cases, so the caller
 * can say what is missing — a crash or a system toast says nothing.
 */

/**
 * Whether the activity an intent resolved to is one of the system's placeholders.
 *
 * Android TV answers the intents it has no app for — the document picker, web
 * links, some settings pages — with activities that resolve like the real
 * thing, show a "You don't have an app that can do this" toast or an empty
 * page, and finish. So on a television "something resolves" does not mean
 * "something works", and a launch does not throw. Measured on the Android TV
 * image of API 36: `com.android.tv.frameworkpackagestubs` (`Stubs$DocumentsStub`,
 * `Stubs$BrowserStub`) and the settings app's `EmptyStubActivity`. The stubs
 * package is named per vendor; its last segment is not.
 *
 * Pure, so the rule has a test; [canLaunch] is what applies it.
 */
fun isPlaceholderActivity(packageName: String?, className: String?): Boolean =
    packageName?.endsWith(".frameworkpackagestubs") == true ||
        className?.substringAfterLast('.') == "EmptyStubActivity"

/**
 * Whether an activity that does something would answer [intent]: at least one
 * of those that take it is not a placeholder. For deciding what to show; a
 * launch still goes through [tryStartActivity] or [tryLaunch], because a
 * device with no handler at all throws, and the answer can be stale a minute
 * later.
 */
fun Context.canLaunch(intent: Intent): Boolean =
    runCatching { packageManager.activitiesFor(intent) }
        .getOrDefault(emptyList())
        .any { !isPlaceholderActivity(it.activityInfo?.packageName, it.activityInfo?.name) }

private fun PackageManager.activitiesFor(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

/**
 * [Context.startActivity] that answers false where it would have thrown for
 * want of a target: no activity takes [intent], or the one that does is not
 * ours to start (a vendor's settings page that is not exported).
 */
fun Context.tryStartActivity(intent: Intent, options: Bundle? = null): Boolean = try {
    startActivity(intent, options)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** [ActivityResultLauncher.launch] that answers false the same way — a picker the system does not have. */
fun <I> ActivityResultLauncher<I>.tryLaunch(input: I, options: ActivityOptionsCompat? = null): Boolean = try {
    launch(input, options)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/**
 * Opens the page where the user gives this app all-files access (Android 11
 * and later; false before that, where it is a runtime permission). The app's
 * own page first — the only one a television has — then the list of apps, then
 * the app's details, where the permission can be found by hand. False when the
 * device has none of them.
 */
fun Context.openAllFilesAccessSettings(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val app = Uri.fromParts("package", packageName, null)
    val pages = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
    )
    return pages.any { canLaunch(it) && tryStartActivity(it) }
}
