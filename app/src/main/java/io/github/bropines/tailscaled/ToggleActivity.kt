package io.github.bropines.tailscaled

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val intent = Intent(this, TailscaledService::class.java)
        if (ProxyState.isActualRunning()) {
            intent.action = "STOP_ACTION"
        } else {
            intent.action = "START_ACTION"
        }
        
        startForegroundService(intent)
        finish()
    }
}