package io.github.bropines.tailscaled

import android.content.Context
import android.net.Uri

object GlobalSettings {
    private const val PREFS_NAME = "tailsocks_global"
    private const val KEY_TAILDROP_ROOT_URI = "taildrop_root_uri"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_CP_ENABLED = "cp_enabled"

    fun getTaildropRootUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_TAILDROP_ROOT_URI, null) ?: return null
        return Uri.parse(uriStr)
    }

    fun setTaildropRootUri(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAILDROP_ROOT_URI, uri?.toString())
            .apply()
        
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

    fun isAutoStartEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, false)
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .apply()
    }

    // Control Plane Proxy
    fun isCPProxyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CP_ENABLED, false)
    }

    fun setCPProxyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CP_ENABLED, enabled)
            .apply()
    }

    fun getControlProxyUrl(context: Context): String {
        if (!isCPProxyEnabled(context)) return ""
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val type = prefs.getString("cp_type", "SOCKS5") ?: "SOCKS5"
        val host = prefs.getString("cp_host", "") ?: ""
        val port = prefs.getString("cp_port", "") ?: ""
        val user = prefs.getString("cp_user", "") ?: ""
        val pass = prefs.getString("cp_pass", "") ?: ""

        if (host.isEmpty()) return ""
        
        val auth = if (user.isNotEmpty()) "$user:$pass@" else ""
        val scheme = type.lowercase()
        val p = port.ifEmpty { if (scheme == "http") "8080" else "1080" }
        
        return "$scheme://$auth$host:$p"
    }

    fun getCPField(context: Context, key: String, default: String = ""): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("cp_$key", default) ?: default
    }

    fun setCPField(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("cp_$key", value).apply()
    }
}
