package io.github.bropines.tailscaled.core

import android.content.Context
import android.os.Build
import io.github.bropines.tailscaled.BuildConfig

/**
 * Where this copy of the app gets its updates.
 *
 * One APK serves every channel: F-Droid builds it reproducibly and ships the
 * very APK of the GitHub release, so the build cannot differ between them.
 * The GitHub updater — the launch check, the About screen's check, download
 * and install — is therefore switched off at run time instead, when a store
 * that updates the app itself installed it. -PselfUpdate=false still removes
 * it at build time, for a builder that is not reproducing the release.
 */
object UpdateChannel {
    /** Clients that deliver updates themselves: F-Droid and its relatives, and Play. */
    private val stores = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.fdroid.privileged",
        "org.fdroid.basic",
        "com.looker.droidify",
        "com.machiav3lli.fdroid",
        "com.aurora.adroid",
        "com.android.vending",
    )

    /** The package that installed this app, or null for adb and file managers. */
    fun installer(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
    }.getOrNull()

    /** Whether this copy may offer updates from GitHub. */
    fun selfUpdate(context: Context): Boolean = BuildConfig.SELF_UPDATE && installer(context) !in stores
}
