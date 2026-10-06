package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * `tailsocks://` links: they open a screen, and nothing more. Any app on the
 * device can open one, so none of them changes a setting or starts anything;
 * that stays with the automation receiver and its token (docs/AUTOMATION.md).
 * `tailcat/add` only fills in a connection's editor — saving is the user's tap.
 *
 *     tailsocks://serve                     Serve & Funnel
 *     tailsocks://exitnode                  the exit-node picker on the main screen
 *     tailsocks://tailcat                   TailCat
 *     tailsocks://tailcat/add?cmd=…         a new TailCat connection from an
 *                                           address or a connect command
 *     tailsocks://logs?category=TAILCAT     Logs, on one category
 *     tailsocks://settings/<section>        Settings, on a section
 *     tailsocks://peers | dns | netcheck | console | files | taildrive | permissions | licenses
 *
 * MainActivity receives them (VIEW, scheme tailsocks) and opens the screen on
 * top of itself, so Back lands on the main screen.
 */
object DeepLinks {
    const val SCHEME = "tailsocks"

    /** The main screen's own: MainActivity opens its exit-node picker rather than a screen. */
    const val EXIT_NODE = "exitnode"

    /** Settings sections a link may name; the ids SettingsActivity's list uses. */
    private val SETTINGS_SECTIONS = setOf(
        "appearance", "account", "tunnel", "proxies", "dns", "bypass", "sharing",
        "background", "backup", "automation", "diagnostics"
    )

    /** The screen [uri] names, or null for the main screen or a link it does not know. */
    fun intentFor(context: Context, uri: Uri): Intent? {
        if (uri.scheme != SCHEME) return null
        val path = uri.pathSegments
        return when (uri.host) {
            "serve" -> Intent(context, ServeActivity::class.java)
            "tailcat" -> when (path.firstOrNull()) {
                "add" -> ServeActivity.tailcatIntent(context, importText = uri.getQueryParameter("cmd") ?: uri.getQueryParameter("address"))
                else -> ServeActivity.tailcatIntent(context)
            }
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
}
