package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.*

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Turns TailSocks on or off and shows nothing: the launcher's "On / off"
 * shortcut (AppShortcuts). The same start as the Quick Settings tile's. Before
 * onboarding there is nothing to start, so it opens the app instead.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val onboarded = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("first_start_done", false)
        if (!onboarded) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val intent = Intent(this, TailscaledService::class.java).apply {
            action = if (ProxyState.isActualRunning(this@ToggleActivity)) "STOP_ACTION" else "START_ACTION"
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w("ToggleActivity", "Foreground service start refused: ${e.message}")
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
