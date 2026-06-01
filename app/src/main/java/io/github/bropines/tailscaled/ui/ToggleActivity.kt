package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

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