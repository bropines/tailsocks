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

    fun isCPByeDpiEnabled(context: Context) = getPrefs(context).getBoolean("cp_byedpi_enabled", false)
    fun setCPByeDpiEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("cp_byedpi_enabled", enabled).apply()

    fun getCPByeDpiFlags(context: Context): String = getPrefs(context).getString("cp_byedpi_flags", "-o1 -a1 -r-5+se") ?: "-o1 -a1 -r-5+se"
    fun setCPByeDpiFlags(context: Context, flags: String) = getPrefs(context).edit().putString("cp_byedpi_flags", flags).apply()

    fun isCPByeDpiIpv6Disabled(context: Context): Boolean = getPrefs(context).getBoolean("cp_byedpi_ipv6_disabled", false)
    fun setCPByeDpiIpv6Disabled(context: Context, disabled: Boolean) = getPrefs(context).edit().putBoolean("cp_byedpi_ipv6_disabled", disabled).apply()

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
        val p = port.ifEmpty { if (scheme == "http" || scheme == "https") "8080" else "1080" }
        return "$scheme://$auth$host:$p"
    }

    fun parseProxyUri(uriStr: String): Map<String, String>? {
        try {
            val trimmed = uriStr.trim().split("#").first().trim()
            if (trimmed.isEmpty()) return null
            val regex = Regex("^(socks5|http|https)://(?:([^:@]+)(?::([^@]+))?@)?([^:]+):(\\d+)(?:/.*)?$", RegexOption.IGNORE_CASE)
            val matchResult = regex.matchEntire(trimmed)
            if (matchResult != null) {
                val scheme = matchResult.groups[1]?.value?.uppercase() ?: "SOCKS5"
                val user = matchResult.groups[2]?.value ?: ""
                val pass = matchResult.groups[3]?.value ?: ""
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
        val auth = if (user.isNotEmpty()) "$user:$pass@" else ""
        return "$scheme://$auth$host:$port"
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
    fun isTunIpv6Enabled(context: Context): Boolean = getBoolean(context, "tun_ipv6_enabled", true)
    fun setTunIpv6Enabled(context: Context, enabled: Boolean) = setBoolean(context, "tun_ipv6_enabled", enabled)

    data class ProxyPreset(
        val name: String,
        val type: String,
        val host: String,
        val port: String,
        val user: String = "",
        val pass: String = ""
    )

    fun getCPPresets(context: Context): List<ProxyPreset> {
        val json = getPrefs(context).getString("cp_presets", null) ?: return emptyList()
        return try {
            val typeToken = object : com.google.gson.reflect.TypeToken<List<ProxyPreset>>() {}.type
            com.google.gson.Gson().fromJson(json, typeToken) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCPPresets(context: Context, presets: List<ProxyPreset>) {
        val json = com.google.gson.Gson().toJson(presets)
        getPrefs(context).edit().putString("cp_presets", json).apply()
    }

    // Tasker & Automation Settings
    fun isAutomationEnabled(context: Context): Boolean = getPrefs(context).getBoolean("automation_enabled", true)
    fun setAutomationEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("automation_enabled", enabled).apply()

    fun getAutomationSecret(context: Context): String = getPrefs(context).getString("automation_secret", "") ?: ""
    fun setAutomationSecret(context: Context, secret: String) = getPrefs(context).edit().putString("automation_secret", secret.trim()).apply()
}

