package io.github.bropines.tailscaled

import android.content.Context
import android.net.Uri

object GlobalSettings {
    private const val PREFS_NAME = "tailsocks_global"
    private const val KEY_TAILDROP_ROOT_URI = "taildrop_root_uri"
    private const val KEY_AUTO_START = "auto_start"

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
}
