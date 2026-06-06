package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.net.Uri

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

    fun getControlProxyUrl(context: Context): String {
        if (!isCPProxyEnabled(context)) return ""
        val type = getPrefs(context).getString("cp_type", "SOCKS5") ?: "SOCKS5"
        val host = getPrefs(context).getString("cp_host", "") ?: ""
        val port = getPrefs(context).getString("cp_port", "") ?: ""
        val user = getPrefs(context).getString("cp_user", "") ?: ""
        val pass = getPrefs(context).getString("cp_pass", "") ?: ""
        if (host.isEmpty()) return ""
        val auth = if (user.isNotEmpty()) "$user:$pass@" else ""
        val scheme = type.lowercase()
        val p = port.ifEmpty { if (scheme == "http") "8080" else "1080" }
        return "$scheme://$auth$host:$p"
    }

    // Generic accessors for global settings
    fun getString(context: Context, key: String, default: String): String = getPrefs(context).getString(key, default) ?: default
    fun setString(context: Context, key: String, value: String) = getPrefs(context).edit().putString(key, value).apply()
    
    fun getBoolean(context: Context, key: String, default: Boolean): Boolean = getPrefs(context).getBoolean(key, default)
    fun setBoolean(context: Context, key: String, value: Boolean) = getPrefs(context).edit().putBoolean(key, value).apply()

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

    /** true = route 0.0.0.0/0 (requires exit node), false = route only 100.64.0.0/10 */
    fun isTunFullTunnel(context: Context): Boolean = getBoolean(context, "tun_full_tunnel", false)
    fun setTunFullTunnel(context: Context, full: Boolean) = setBoolean(context, "tun_full_tunnel", full)

    /** Package names excluded from VPN tunnel (comma-separated). */
    fun getTunExcludedApps(context: Context): Set<String> {
        val raw = getString(context, "tun_excluded_apps", "")
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
}

