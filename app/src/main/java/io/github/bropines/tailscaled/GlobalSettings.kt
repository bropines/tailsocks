package io.github.bropines.tailscaled

import android.content.Context
import android.net.Uri

object GlobalSettings {
    private const val PREFS_NAME = "tailsocks_global"
    private const val KEY_TAILDROP_ROOT_URI = "taildrop_root_uri"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_CP_ENABLED = "cp_enabled"

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
}
