package io.github.bropines.tailscaled.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** What the system can tell the app about one optional permission. */
enum class PermState {
    GRANTED,
    DENIED,

    /** The system keeps the answer to itself: a vendor's autostart list has no API. */
    UNKNOWN,

    /** This device, or this Android, has no such permission. */
    NOT_APPLICABLE;

    /** Something is left to do about it. */
    val needsAction: Boolean get() = this == DENIED || this == UNKNOWN
}

/**
 * The permissions the app works without and works better with: the rows of the Permissions
 * screen (ui/PermissionsActivity.kt), and the two it asks for where they are needed — storage
 * for a receive folder and for shared folders, the VPN consent for TUN mode. One list, so
 * that the screen and remote management (core/RemoteDevice.kt), which reports them to a
 * paired device, cannot come to mean different things by "granted".
 *
 * [id] is the name remote management knows it by (appctr/remote_policy.go, remotePermissions).
 */
enum class AppPermission(val id: String) {
    /** All files access from Android 11, the Storage permission before it. */
    STORAGE("storage"),
    NOTIFICATIONS("notifications"),

    /** Being left out of battery optimisation. */
    BATTERY("battery"),

    /** The consent Android asks before an app may open a VPN. */
    VPN("vpn"),

    /** Alarms and reminders: exact alarms, for the service watchdog. */
    ALARMS("alarms"),

    /** Installing an update the app downloaded. */
    INSTALL("install"),

    /** A vendor's own list of apps that may start by themselves. */
    AUTOSTART("autostart");

    companion object {
        fun of(id: String?): AppPermission? = entries.firstOrNull { it.id == id }
    }
}

object AppPermissions {

    /** What the system says of [permission] now. Asks system services; never on the preview renderer. */
    fun state(context: Context, permission: AppPermission): PermState = when (permission) {
        AppPermission.STORAGE -> storageState(FileBrowse.storageAccess(context))
        AppPermission.NOTIFICATIONS ->
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) PermState.GRANTED else PermState.DENIED
        AppPermission.BATTERY -> batteryState(context)
        AppPermission.VPN -> vpnState(
            wanted = GlobalSettings.isTunModeEnabled(context) && !GlobalSettings.isRootModeEnabled(context),
            prepared = { runCatching { VpnService.prepare(context) == null }.getOrNull() },
        )
        // Before Android 12 an exact alarm needs no permission at all.
        AppPermission.ALARMS -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> PermState.NOT_APPLICABLE
            ServiceWatchdog.canScheduleExact(context) -> PermState.GRANTED
            else -> PermState.DENIED
        }
        // Before Android 8 "unknown sources" is one device-wide switch, not an app permission.
        AppPermission.INSTALL -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> PermState.NOT_APPLICABLE
            context.packageManager.canRequestPackageInstalls() -> PermState.GRANTED
            else -> PermState.DENIED
        }
        // The vendors' screens expose no API at all, so claiming either answer would be a guess.
        AppPermission.AUTOSTART -> PermState.UNKNOWN
    }

    /** Storage access as a permission's state: both kinds of missing are "denied". Pure. */
    fun storageState(access: StorageAccess): PermState =
        if (access == StorageAccess.GRANTED) PermState.GRANTED else PermState.DENIED

    /**
     * The VPN consent. It matters only where the app is to open a VPN — TUN mode, outside Root
     * Mode, which routes below it — and [prepared] is asked only then: true when Android would
     * let the tunnel start without asking, null when it would not say. Pure.
     */
    fun vpnState(wanted: Boolean, prepared: () -> Boolean?): PermState = when {
        !wanted -> PermState.NOT_APPLICABLE
        else -> when (prepared()) {
            true -> PermState.GRANTED
            false -> PermState.DENIED
            null -> PermState.UNKNOWN
        }
    }

    private fun batteryState(context: Context): PermState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return PermState.UNKNOWN
        return try {
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) PermState.GRANTED else PermState.DENIED
        } catch (e: Exception) {
            PermState.UNKNOWN
        }
    }

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** The vendor's autostart list, where there is one this app knows how to open. */
    private fun autostartPage(): Intent = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    )

    /**
     * The system's pages where [permission] is given, most direct first. The last of them is
     * the app's own details page, where the permission can be found by hand — except for the
     * VPN consent, which is a dialog and has no page, and for the autostart list, which is a
     * vendor's page or nothing. Start each in turn ([open]): a television answers some of
     * them with a placeholder, and some skins have dropped them.
     */
    fun pages(context: Context, permission: AppPermission): List<Intent> {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (permission) {
            AppPermission.STORAGE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) FileBrowse.grantIntents(context) + appDetails(context)
                else listOf(appDetails(context))
            AppPermission.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                appDetails(context),
            )
            AppPermission.BATTERY -> listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(context))
            AppPermission.ALARMS -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg), appDetails(context))
            AppPermission.INSTALL -> listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                appDetails(context),
            )
            AppPermission.AUTOSTART -> listOf(autostartPage())
            AppPermission.VPN -> emptyList()
        }
    }

    /** Whether the activity an intent resolves to is one of a television's placeholders (core/SafeLaunch.kt). */
    private fun Context.landsOnPlaceholder(intent: Intent): Boolean {
        val activity = runCatching {
            packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()?.activityInfo
        return isPlaceholderActivity(activity?.packageName, activity?.name)
    }

    /** Whether the device has a page of [pages] that would really open. For deciding what to offer. */
    fun hasPage(context: Context, permission: AppPermission): Boolean =
        pages(context, permission).any { context.canLaunch(it) || (it.component != null && resolves(context, it)) }

    private fun resolves(context: Context, intent: Intent): Boolean =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    /**
     * Starts the first page of [permission] the device really has; false when it has none.
     * From a context that is not an activity the pages open in a task of their own.
     */
    fun open(context: Context, permission: AppPermission): Boolean =
        pages(context, permission).any { page ->
            if (context !is android.app.Activity) page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            !context.landsOnPlaceholder(page) && context.tryStartActivity(page)
        }

    /**
     * Whether [permission] is asked with one of Android's own run-time dialogs rather than on a
     * Settings page: the Storage permission below Android 11, notifications from Android 13.
     * Such a dialog needs an activity to ask from; see ui/PermissionsActivity.kt.
     */
    fun asksInDialog(permission: AppPermission, sdk: Int = Build.VERSION.SDK_INT): Boolean = when (permission) {
        AppPermission.STORAGE -> sdk < Build.VERSION_CODES.R
        AppPermission.NOTIFICATIONS -> sdk >= Build.VERSION_CODES.TIRAMISU
        else -> false
    }

    /**
     * Whether the app has any way to put [permission] in front of a person: a dialog of
     * Android's, or a page the device really has. A television may have neither for access to
     * all files — then only adb gives it (core/TaildriveShares.kt, storageGrantCommand).
     */
    fun canAsk(context: Context, permission: AppPermission): Boolean =
        permission != AppPermission.VPN && (asksInDialog(permission) || hasPage(context, permission))
}
