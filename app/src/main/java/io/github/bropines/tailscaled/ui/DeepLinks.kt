package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.bropines.tailscaled.core.DeviceProfile
import io.github.bropines.tailscaled.core.Feature

/**
 * `tailsocks://` links: they open a screen, and nothing more. Any app on the
 * device can open one, so none of them changes a setting or starts anything;
 * that stays with the automation receiver and its token (docs/AUTOMATION.md).
 * `tailcat/add` only fills in a connection's editor — saving is the user's tap.
 *
 *     tailsocks://serve                     Serve & Funnel
 *     tailsocks://exitnode                  the exit-node picker on the main screen
 *     tailsocks://scan                      the QR scanner, over the main screen
 *     tailsocks://tailcat                   TailCat
 *     tailsocks://tailcat/add?cmd=…         a new TailCat connection from an
 *                                           address or a connect command
 *     tailsocks://logs?category=TAILCAT     Logs, on one category
 *     tailsocks://settings/<section>        Settings, on a section
 *     tailsocks://peers | dns | netcheck | console | files | taildrive | permissions | licenses
 *
 * MainActivity receives them (VIEW, scheme tailsocks) and opens the screen on
 * top of itself, so Back lands on the main screen. A link that reaches the app
 * another way — a scanned QR code — goes through [open], to the same place.
 *
 * A link opens what the device has. A screen the device is not offered at all
 * (core/DeviceProfile.kt: TailCat on a television) is not opened — the link
 * lands on the main screen, where MainActivity already is — and a Settings
 * page the device shows somewhere else is found there (settingsLanding).
 */
object DeepLinks {
    const val SCHEME = "tailsocks"

    /** The main screen's own: MainActivity opens its exit-node picker rather than a screen. */
    const val EXIT_NODE = "exitnode"

    /** The main screen's own too: it opens the QR scanner and deals with what it reads. */
    const val SCAN = "scan"

    /**
     * Settings pages a link may name: the ids SettingsActivity knows its pages by. The last
     * three are the Advanced page and the two pages only it leads to — the theme and Root
     * mode, which a device without that page keeps inside Appearance and Tunnel mode.
     */
    internal val SETTINGS_SECTIONS = setOf(
        "appearance", "account", "tunnel", "proxies", "dns", "bypass", "sharing",
        "background", "backup", "automation", "diagnostics",
        "advanced", "theme", "root"
    )

    /**
     * The part of the app a link's screen is, where a device may be without it; null for a
     * screen every device has. Asked of the link's host.
     */
    internal fun featureOf(host: String?): Feature? = when (host) {
        "serve" -> Feature.SERVE_EDIT
        "tailcat" -> Feature.TAILCAT
        "logs" -> Feature.LOGS
        "dns" -> Feature.DNS_SCREEN
        "console" -> Feature.CONSOLE
        SCAN -> Feature.QR_SCANNER
        else -> null
    }

    /** The screen [uri] names, or null for the main screen or a link it does not know. */
    fun intentFor(context: Context, uri: Uri): Intent? {
        if (uri.scheme != SCHEME) return null
        // Hidden on this device: no screen to open. One kept for the advanced settings is still its screen.
        featureOf(uri.host)?.let { if (!DeviceProfile.availability(context, it).reachable) return null }
        val path = uri.pathSegments
        return when (uri.host) {
            "serve" -> Intent(context, ServeActivity::class.java)
            "tailcat" -> ServeActivity.tailcatIntent(context, importText = tailcatImportText(uri))
            "logs" -> LogsActivity.intent(context, uri.getQueryParameter("category")?.uppercase() ?: "ALL")
            "settings" -> Intent(context, SettingsActivity::class.java).apply {
                path.firstOrNull()?.takeIf { it in SETTINGS_SECTIONS }?.let { putExtra(SettingsActivity.EXTRA_OPEN_SECTION, it) }
            }
            "peers" -> Intent(context, PeersActivity::class.java)
            "dns" -> Intent(context, DnsActivity::class.java)
            "netcheck" -> Intent(context, NetcheckActivity::class.java)
            "console" -> Intent(context, ConsoleActivity::class.java)
            "files" -> Intent(context, FilesActivity::class.java)
            "taildrive" -> Intent(context, TaildriveActivity::class.java)
            "permissions" -> Intent(context, PermissionsActivity::class.java)
            "licenses" -> Intent(context, LicensesActivity::class.java)
            else -> null
        }
    }

    /**
     * The `tailcat/add` link carrying [command]: any phone's camera opens it in
     * the app, with a new connection's editor filled in from the command.
     */
    fun tailcatAddLink(command: String): String =
        Uri.Builder().scheme(SCHEME).authority("tailcat").appendPath("add")
            .appendQueryParameter("cmd", command).build().toString()

    /**
     * What a `tailcat/add` link hands the editor — its `cmd`, else its
     * `address`, else "" — and null for any other link.
     */
    fun tailcatImportText(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.host != "tailcat" || uri.pathSegments.firstOrNull() != "add") return null
        return uri.getQueryParameter("cmd") ?: uri.getQueryParameter("address") ?: ""
    }

    /** Whether [uri] is a link the app opens: a screen's, or one of the main screen's own. */
    fun knows(context: Context, uri: Uri): Boolean =
        uri.scheme == SCHEME && (uri.host == EXIT_NODE || uri.host == SCAN || intentFor(context, uri) != null)

    /**
     * Opens [uri] as if the system had handed it to the app: the main
     * screen's own links go through MainActivity, a screen opens on top of
     * [context]. False, with nothing opened, for a link the app does not know.
     */
    fun open(context: Context, uri: Uri): Boolean {
        if (!knows(context, uri)) return false
        val intent = if (uri.host == EXIT_NODE || uri.host == SCAN) Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
        else intentFor(context, uri) ?: return false
        context.startActivity(intent)
        return true
    }
}
