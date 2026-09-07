package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

object GlobalSettings {
    private const val PREFS_NAME = "tailsocks_global"
    private const val KEY_TAILDROP_ROOT_URI = "taildrop_root_uri"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_CP_ENABLED = "cp_enabled"
    private const val KEY_APP_THEME = "app_theme"
    private const val KEY_THEME_PRESET = "theme_preset"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTaildropRootUri(context: Context): Uri? {
        val uriStr = getPrefs(context).getString(KEY_TAILDROP_ROOT_URI, null) ?: return null
        return Uri.parse(uriStr)
    }

    fun setTaildropRootUri(context: Context, uri: Uri?) {
        getPrefs(context).edit().putString(KEY_TAILDROP_ROOT_URI, uri?.toString()).apply()
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {}
        }
    }

    fun isAutoStartEnabled(context: Context) = getPrefs(context).getBoolean(KEY_AUTO_START, false)
    fun setAutoStartEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()

    // Control Plane Proxy
    fun isCPProxyEnabled(context: Context) = getPrefs(context).getBoolean(KEY_CP_ENABLED, false)
    fun setCPProxyEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_CP_ENABLED, enabled).apply()

    fun isCPByeDpiEnabled(context: Context) = getPrefs(context).getBoolean("cp_byedpi_enabled", false)
    fun setCPByeDpiEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("cp_byedpi_enabled", enabled).apply()

    fun getCPByeDpiFlags(context: Context): String = getPrefs(context).getString("cp_byedpi_flags", "-o1 -a1 -r-5+se") ?: "-o1 -a1 -r-5+se"
    fun setCPByeDpiFlags(context: Context, flags: String) = getPrefs(context).edit().putString("cp_byedpi_flags", flags).apply()

    fun isCPByeDpiIpv6Disabled(context: Context): Boolean = getPrefs(context).getBoolean("cp_byedpi_ipv6_disabled", false)
    fun setCPByeDpiIpv6Disabled(context: Context, disabled: Boolean) = getPrefs(context).edit().putBoolean("cp_byedpi_ipv6_disabled", disabled).apply()

    fun getControlProxyUrl(context: Context): String {
        if (isCPByeDpiEnabled(context)) {
            // Pure getter: only report a ByeDPI listener that is already running.
            // Starting it here bound a ServerSocket, and this is called from Compose
            // composition (DnsActivity, AdminApiDashboardScreen). TailscaledService
            // owns starting/stopping ByeDPI; if it is not up yet there is no URL.
            val addr = ByeDpiProxy.activeAddress ?: return ""
            return "socks5://${addr.first}:${addr.second}"
        }
        if (!isCPProxyEnabled(context)) return ""
        val type = getPrefs(context).getString("cp_type", "SOCKS5") ?: "SOCKS5"
        val host = getPrefs(context).getString("cp_host", "") ?: ""
        val port = getPrefs(context).getString("cp_port", "") ?: ""
        val user = getPrefs(context).getString("cp_user", "") ?: ""
        val pass = getPrefs(context).getString("cp_pass", "") ?: ""
        if (host.isEmpty()) return ""
        val auth = if (user.isNotEmpty()) "${pctEncodeUserInfo(user)}:${pctEncodeUserInfo(pass)}@" else ""
        val scheme = type.lowercase()
        val p = port.ifEmpty { if (scheme == "http" || scheme == "https") "8080" else "1080" }
        return "$scheme://$auth$host:$p"
    }

    /**
     * Percent-encodes a URL userinfo component (RFC 3986 unreserved characters
     * are kept, everything else becomes %XX of its UTF-8 bytes).
     *
     * The proxy URL is handed to tailscaled as ALL_PROXY/HTTPS_PROXY, and Go's
     * proxy.FromEnvironment falls back to a DIRECT connection whenever the URL
     * does not parse. A `/`, `?`, `#`, `@` or stray `%` in a password used to
     * turn the proxy off silently. java.net.URI is no help here: it leaves
     * those characters alone in userinfo because they are legal URI characters.
     */
    fun pctEncodeUserInfo(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(Character.forDigit(c shr 4, 16).uppercaseChar()).append(Character.forDigit(c and 0xF, 16).uppercaseChar())
            }
        }
        return sb.toString()
    }

    /** Inverse of [pctEncodeUserInfo]; tolerant of plain (unencoded) input. */
    fun pctDecodeUserInfo(value: String): String {
        if (!value.contains('%')) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length + 0 && i + 2 <= value.length - 1) {
                val hex = value.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) { out.write(byte); i += 3; continue }
            }
            out.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // -------------------------------------------------------------------------
    // LAN exposure of the local proxies
    // -------------------------------------------------------------------------

    /**
     * When enabled, the SOCKS5 proxy, the outbound HTTP proxy and the local DNS
     * proxy bind to `0.0.0.0` instead of loopback, so other devices on the same
     * network can use this phone as a Tailscale gateway.
     */
    fun isLanAccessEnabled(context: Context): Boolean = getBoolean(context, "lan_access_enabled", false)

    fun setLanAccessEnabled(context: Context, enabled: Boolean) = setBoolean(context, "lan_access_enabled", enabled)

    const val DEFAULT_SOCKS5 = "127.0.0.1:48115"
    const val DEFAULT_DNS_PROXY = "127.0.0.1:1053"

    /**
     * The address a listener should actually bind to.
     *
     * The stored value stays whatever the user typed; the LAN choice is applied
     * here, at the moment the address is used. Rewriting the stored values on
     * toggle instead meant a listener the user had never touched — and so had no
     * stored value at all — was skipped and silently stayed on loopback, and any
     * later hand edit quietly opted that listener out again.
     *
     * An empty value means the listener is switched off and is left empty; a host
     * the user chose explicitly is never overridden.
     */
    private fun bindAddr(context: Context, key: String, default: String): String {
        val configured = getString(context, key, default)
        if (configured.isBlank()) return ""
        return NetAddr.rebind(configured, isLanAccessEnabled(context))
    }

    fun getSocks5BindAddr(context: Context): String = bindAddr(context, "socks5", DEFAULT_SOCKS5)
    fun getHttpProxyBindAddr(context: Context): String = bindAddr(context, "httpproxy", "")
    fun getDnsProxyBindAddr(context: Context): String = bindAddr(context, "dns_proxy", DEFAULT_DNS_PROXY)

    fun parseProxyUri(uriStr: String): Map<String, String>? {
        try {
            val trimmed = uriStr.trim().split("#").first().trim()
            if (trimmed.isEmpty()) return null
            val regex = Regex("^(socks5|http|https)://(?:([^:@]+)(?::([^@]+))?@)?([^:]+):(\\d+)(?:/.*)?$", RegexOption.IGNORE_CASE)
            val matchResult = regex.matchEntire(trimmed)
            if (matchResult != null) {
                val scheme = matchResult.groups[1]?.value?.uppercase() ?: "SOCKS5"
                val user = pctDecodeUserInfo(matchResult.groups[2]?.value ?: "")
                val pass = pctDecodeUserInfo(matchResult.groups[3]?.value ?: "")
                val host = matchResult.groups[4]?.value ?: ""
                val port = matchResult.groups[5]?.value ?: ""
                return mapOf(
                    "type" to scheme,
                    "user" to user,
                    "pass" to pass,
                    "host" to host,
                    "port" to port
                )
            }
            val regexNoScheme = Regex("^(?:([^:@]+)(?::([^@]+))?@)?([^:]+):(\\d+)$")
            val matchNoScheme = regexNoScheme.matchEntire(trimmed)
            if (matchNoScheme != null) {
                val user = matchNoScheme.groups[1]?.value ?: ""
                val pass = matchNoScheme.groups[2]?.value ?: ""
                val host = matchNoScheme.groups[3]?.value ?: ""
                val port = matchNoScheme.groups[4]?.value ?: ""
                return mapOf(
                    "type" to "SOCKS5",
                    "user" to user,
                    "pass" to pass,
                    "host" to host,
                    "port" to port
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun buildProxyUri(type: String, host: String, port: String, user: String, pass: String): String {
        val scheme = type.lowercase()
        val auth = if (user.isNotEmpty()) "${pctEncodeUserInfo(user)}:${pctEncodeUserInfo(pass)}@" else ""
        return "$scheme://$auth$host:$port"
    }

    // Generic accessors for global settings
    fun getString(context: Context, key: String, default: String): String = getPrefs(context).getString(key, default) ?: default
    fun setString(context: Context, key: String, value: String) = getPrefs(context).edit().putString(key, value).apply()
    
    fun getBoolean(context: Context, key: String, default: Boolean): Boolean = getPrefs(context).getBoolean(key, default)
    fun setBoolean(context: Context, key: String, value: Boolean) = getPrefs(context).edit().putBoolean(key, value).apply()

    fun getLong(context: Context, key: String, default: Long): Long = getPrefs(context).getLong(key, default)
    fun setLong(context: Context, key: String, value: Long) = getPrefs(context).edit().putLong(key, value).apply()

    fun getCPField(context: Context, key: String, default: String = ""): String = getPrefs(context).getString("cp_$key", default) ?: default
    fun setCPField(context: Context, key: String, value: String) = getPrefs(context).edit().putString("cp_$key", value).apply()

    fun getAppTheme(context: Context): String {
        val theme = getString(context, KEY_APP_THEME, "system")
        if (theme == "amoled") {
            setAppTheme(context, "dark")
            setBoolean(context, "amoled_mode", true)
            return "dark"
        }
        return theme
    }
    fun setAppTheme(context: Context, theme: String) = setString(context, KEY_APP_THEME, theme)

    fun getThemePreset(context: Context): String = getString(context, KEY_THEME_PRESET, "default")
    fun setThemePreset(context: Context, preset: String) = setString(context, KEY_THEME_PRESET, preset)

    fun isDynamicColorEnabled(context: Context): Boolean = getBoolean(context, KEY_DYNAMIC_COLOR, true)
    fun setDynamicColorEnabled(context: Context, enabled: Boolean) = setBoolean(context, KEY_DYNAMIC_COLOR, enabled)

    // -------------------------------------------------------------------------
    // TUN Mode (hev-socks5-tunnel VPN)
    // -------------------------------------------------------------------------

    fun isTunModeEnabled(context: Context): Boolean = getBoolean(context, "tun_mode_enabled", false)
    fun setTunModeEnabled(context: Context, enabled: Boolean) = setBoolean(context, "tun_mode_enabled", enabled)

    private val DEFAULT_EXCLUDED_APPS = setOf(
        "ru.oneme.app",
        "com.vkontakte.android",
        "ru.vk.store.tv",
        "ru.nspk.mirpay",
        "ru.rostel",
        "com.avito.android"
    )

    /** Package names excluded from VPN tunnel (comma-separated). */
    fun getTunExcludedApps(context: Context): Set<String> {
        val prefs = getPrefs(context)
        if (!prefs.contains("tun_excluded_apps")) {
            val defaultStr = DEFAULT_EXCLUDED_APPS.joinToString(",")
            prefs.edit().putString("tun_excluded_apps", defaultStr).apply()
            return DEFAULT_EXCLUDED_APPS
        }
        val raw = prefs.getString("tun_excluded_apps", "") ?: ""
        return if (raw.isEmpty()) emptySet() else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    fun setTunExcludedApps(context: Context, apps: Set<String>) =
        setString(context, "tun_excluded_apps", apps.joinToString(","))

    /** CIDR ranges excluded from VPN routing (comma-separated, e.g. "192.168.0.0/16,10.0.0.0/8"). */
    fun getTunExcludedCIDRs(context: Context): String = getString(context, "tun_excluded_cidrs", "192.168.0.0/16,10.0.0.0/8,172.16.0.0/12")
    fun setTunExcludedCIDRs(context: Context, cidrs: String) = setString(context, "tun_excluded_cidrs", cidrs)

    /** TUN interface IP address (default "10.0.0.1/8"). */
    fun getTunAddress(context: Context): String = getString(context, "tun_address", "10.0.0.1/8")
    fun setTunAddress(context: Context, address: String) = setString(context, "tun_address", address)

    /** Whether to enable IPv6 routing in TUN mode. */
    /** Whether ALL IPv6 is routed through the tunnel. Off by default: see TunVpnService. */
    fun isTunIpv6Enabled(context: Context): Boolean = getBoolean(context, "tun_ipv6_enabled", false)
    fun setTunIpv6Enabled(context: Context, enabled: Boolean) = setBoolean(context, "tun_ipv6_enabled", enabled)

    @Serializable
    data class ProxyPreset(
        val name: String = "",
        val type: String = "",
        val host: String = "",
        val port: String = "",
        val user: String = "",
        val pass: String = ""
    )

    fun getCPPresets(context: Context): List<ProxyPreset> {
        val json = getPrefs(context).getString("cp_presets", null)
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { AppJson.decodeFromString<List<ProxyPreset>>(json) }.getOrDefault(emptyList())
    }

    fun saveCPPresets(context: Context, presets: List<ProxyPreset>) {
        val json = AppJson.encodeToString(presets)
        getPrefs(context).edit().putString("cp_presets", json).apply()
    }

    // Tasker & Automation Settings
    fun isAutomationEnabled(context: Context): Boolean = getPrefs(context).getBoolean("automation_enabled", true)
    fun setAutomationEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("automation_enabled", enabled).apply()

    fun getAutomationSecret(context: Context): String = getPrefs(context).getString("automation_secret", "") ?: ""
    fun setAutomationSecret(context: Context, secret: String) = getPrefs(context).edit().putString("automation_secret", secret.trim()).apply()

    // Root Mode Settings
    fun isRootModeEnabled(context: Context): Boolean = getPrefs(context).getBoolean("root_mode_enabled", false)
    fun setRootModeEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("root_mode_enabled", enabled).apply()

    fun isRootTunEnabled(context: Context): Boolean = getPrefs(context).getBoolean("root_tun_enabled", true)
    fun setRootTunEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("root_tun_enabled", enabled).apply()

    fun shouldKillRootDaemonOnStop(context: Context): Boolean = getPrefs(context).getBoolean("root_kill_daemon_on_stop", true)
    fun setKillRootDaemonOnStop(context: Context, kill: Boolean) = getPrefs(context).edit().putBoolean("root_kill_daemon_on_stop", kill).apply()

    /**
     * Whether Root Mode redirects the whole device's port 53 to MagicDNS.
     *
     * Turning it off is the escape hatch for coexisting with another VPN or a
     * DNS filtering app: those own the system resolver, and a global redirect
     * takes their queries away from them.
     */
    /**
     * Whether the root daemon marks its own sockets as protected from other
     * VPNs. On by default: without it another VPN client on the phone swallows
     * the daemon's control and DERP connections and both tunnels stall.
     */
    fun isRootVpnBypassEnabled(context: Context): Boolean = getPrefs(context).getBoolean("root_vpn_bypass", true)
    fun setRootVpnBypassEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("root_vpn_bypass", enabled).apply()

    fun isRootDnsRedirectEnabled(context: Context): Boolean = getPrefs(context).getBoolean("root_dns_redirect", true)
    fun setRootDnsRedirectEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("root_dns_redirect", enabled).apply()

    /**
     * Whether Root Mode takes the default route and the device's port 53 even
     * while another VPN client holds the phone.
     *
     * Off by default, and deliberately so: Android's own per-app VPN rules sit
     * below ours, so the exit-node catch-all and the DNS redirect capture the
     * other tunnel's apps as well and it never finds out. With this off, that
     * situation installs tailnet reachability only — tailnet addresses,
     * MagicDNS names and the loopback proxies keep working, exit nodes and
     * system-wide MagicDNS do not. Turning it on is the user accepting that
     * cost on behalf of the other app.
     */
    fun isRootTakeDeviceAnyway(context: Context): Boolean = getPrefs(context).getBoolean("root_take_device_anyway", false)
    fun setRootTakeDeviceAnyway(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("root_take_device_anyway", enabled).apply()

    /**
     * Records that the last routing apply yielded the default route and the
     * device-wide DNS redirect to another VPN.
     *
     * Written by the service, which is the only place that knows what actually
     * went onto the system, and read by the dashboard so the card and the exit
     * node row do not claim a tunnel that is not there. Like
     * `root_routing_installed` it describes *this* device right now, so it is
     * cleared whenever the rules are removed.
     */
    fun isRootRoutingYielded(context: Context): Boolean = getPrefs(context).getBoolean("root_routing_yielded", false)
    fun setRootRoutingYielded(context: Context, yielded: Boolean) =
        getPrefs(context).edit().putBoolean("root_routing_yielded", yielded).apply()

    /**
     * Records that the last routing apply was the scoped one: another VPN holds
     * the device, and the default route and the DNS redirect were taken only for
     * the apps that client leaves outside its own tunnel.
     *
     * This refines [isRootRoutingYielded] instead of replacing it. The yield flag
     * answers "did another VPN change what we installed", which is true here too;
     * this one answers "is the exit node carrying anything at all", and only the
     * two together tell the three cases apart. Like the other two markers it
     * describes *this* device right now, so it is cleared with the rules.
     */
    fun isRootRoutingShared(context: Context): Boolean = getPrefs(context).getBoolean("root_routing_shared", false)
    fun setRootRoutingShared(context: Context, shared: Boolean) =
        getPrefs(context).edit().putBoolean("root_routing_shared", shared).apply()

    /**
     * Records that firewall and policy-routing rules are currently installed on
     * the system.
     *
     * Cleanup must not depend on Root Mode still being switched on: the user can
     * turn it off, or the app can be killed, while the rules are live. This
     * marker survives both, so the rules can always be found and removed.
     */
    fun isRootRoutingInstalled(context: Context): Boolean = getPrefs(context).getBoolean("root_routing_installed", false)
    fun setRootRoutingInstalled(context: Context, installed: Boolean) =
        getPrefs(context).edit().putBoolean("root_routing_installed", installed).apply()

    // -------------------------------------------------------------------------
    // Connection recovery
    // -------------------------------------------------------------------------

    /**
     * Restart the daemon automatically when it fails to reach a connected state,
     * or when it dies while the user still wants it running.
     */
    fun isAutoReconnectEnabled(context: Context): Boolean = getBoolean(context, "auto_reconnect", false)
    fun setAutoReconnectEnabled(context: Context, enabled: Boolean) = setBoolean(context, "auto_reconnect", enabled)

    /** How many automatic restarts to attempt before giving up (0 = unlimited). */
    fun getAutoReconnectAttempts(context: Context): Int =
        getString(context, "auto_reconnect_attempts", "3").toIntOrNull()?.coerceIn(0, 99) ?: 3

    /**
     * Periodically check that the service is still alive and revive it.
     * Complements START_STICKY, which does not cover OEM task killers.
     */
    fun isServiceWatchdogEnabled(context: Context): Boolean = getBoolean(context, "service_watchdog", true)
    fun setServiceWatchdogEnabled(context: Context, enabled: Boolean) = setBoolean(context, "service_watchdog", enabled)

    // -------------------------------------------------------------------------
    // In-app changelog ("What's new")
    // -------------------------------------------------------------------------

    /**
     * The BuildConfig.VERSION_NAME whose changelog the user has already seen.
     * Empty means the app has never been launched before (fresh install), so
     * the current version is recorded silently and nothing is shown.
     */
    fun getLastSeenChangelogVersion(context: Context): String = getString(context, "last_seen_changelog_version", "")
    fun setLastSeenChangelogVersion(context: Context, version: String) = setString(context, "last_seen_changelog_version", version)

    /** Show the changelog dialog once after the app has been updated. */
    fun isShowChangelogAfterUpdate(context: Context): Boolean = getBoolean(context, "show_changelog_after_update", true)
    fun setShowChangelogAfterUpdate(context: Context, enabled: Boolean) = setBoolean(context, "show_changelog_after_update", enabled)

    // -------------------------------------------------------------------------
    // Settings export
    // -------------------------------------------------------------------------

    /**
     * The app-wide settings a plain-text profile export may carry.
     *
     * An allow-list rather than a deny-list: a key added later is left out of
     * backups until someone decides it belongs there, which is the safe default
     * for a file the user hands around. Deliberately absent:
     *
     *  - `automation_secret` — a shared secret, and the reason the whole
     *    `tailsocks_global` file is excluded from cloud backup and device
     *    transfer (res/xml/data_extraction_rules.xml). The Admin API token
     *    (`admin_api_keys`) and the node keys (`files/states`) live outside this
     *    preference file and are likewise never exported.
     *  - `root_routing_installed`, `root_routing_yielded`, `root_routing_shared`
     *    — markers for the rules installed on *this* device right now; restoring
     *    them elsewhere would strand the cleanup and make the dashboard describe
     *    another phone's tunnels.
     *  - `root_take_device_anyway` — per-device consent to break whatever other
     *    VPN happens to run on *this* phone. A second device may have no other
     *    VPN, or one the user does not want disconnected, so the consent is
     *    given again there or not at all. `root_vpn_bypass` next to it is
     *    exported: it changes only whether our own daemon marks its sockets.
     *  - `taildrop_root_uri` — a SAF grant bound to this device and install.
     *  - `last_seen_changelog_version` — install-local bookkeeping.
     *  - `app_locale` — applied at attachBaseContext, so a restored value only
     *    takes effect after a restart and reads as a glitch until then.
     */
    val EXPORTED_KEYS: Set<String> = setOf(
        // Local listeners
        "socks5", "socks5_user", "socks5_pass", "httpproxy",
        "dns_proxy", "dns_fallbacks", "doh_url", "lan_access_enabled",
        // Daemon behaviour
        "accept_routes", "accept_dns", "extra_args_raw", "detailed_logs", "auto_refresh",
        // Control-plane proxy and DPI bypass
        "cp_enabled", "cp_type", "cp_host", "cp_port", "cp_user", "cp_pass", "cp_presets",
        "cp_byedpi_enabled", "cp_byedpi_flags", "cp_byedpi_ipv6_disabled",
        // Lifecycle and recovery
        "force_bg", "auto_start", "auto_reconnect", "auto_reconnect_attempts",
        "service_watchdog", "automation_enabled",
        // TUN mode
        "tun_mode_enabled", "tun_full_tunnel", "tun_excluded_apps", "tun_excluded_cidrs",
        "tun_address", "tun_ipv6_enabled",
        // Root mode. "Ignore other VPNs" belongs here as much as the rest: it
        // decides how our own daemon marks its sockets, and dropping it silently
        // restored a device that yields where the exported one did not. The
        // take-the-device override is the one coexistence switch that stays
        // behind — see above.
        "root_mode_enabled", "root_tun_enabled", "root_kill_daemon_on_stop", "root_dns_redirect",
        "root_vpn_bypass",
        // Appearance
        "app_theme", "theme_preset", "dynamic_color", "amoled_mode", "show_changelog_after_update"
    )

    /** The exportable subset of the global preferences, as stored. */
    fun exportable(context: Context): Map<String, Any?> =
        getPrefs(context).all.filterKeys { it in EXPORTED_KEYS }

    /**
     * Applies global settings read back from an export.
     *
     * Only allow-listed keys are written, so a hand-edited file cannot inject an
     * automation secret or fake the routing marker, and a key the file does not
     * mention keeps its current value. This preference file only ever holds
     * Strings and Booleans; anything else in the file is ignored.
     */
    fun importValues(context: Context, values: Map<String, Any>) {
        val editor = getPrefs(context).edit()
        for ((key, value) in values) {
            if (key !in EXPORTED_KEYS) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
    }
}


