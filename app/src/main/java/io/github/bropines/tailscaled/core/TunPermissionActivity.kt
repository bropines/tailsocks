package io.github.bropines.tailscaled.core

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline Activity that requests Android VPN permission.
 *
 * If permission is already granted, it starts TunVpnService immediately and finishes.
 * Otherwise it calls startActivityForResult(VpnService.prepare()), waits for the
 * user's decision, then starts or aborts accordingly.
 *
 * Usage (from TailscaledService):
 *   startActivity(Intent(this, TunPermissionActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK))
 */
class TunPermissionActivity : Activity() {

    companion object {
        private const val TAG = "TunPermissionActivity"
        private const val REQ_VPN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            // Permission already granted.
            launchTunService()
            finish()
        } else {
            startActivityForResult(intent, REQ_VPN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "VPN permission granted")
                launchTunService()
            } else {
                Log.w(TAG, "VPN permission denied by user")
                // Disable TUN mode in settings to avoid re-prompting on restart.
                GlobalSettings.setTunModeEnabled(this, false)
            }
        }
        finish()
    }

    private fun launchTunService() {
        startService(Intent(this, TunVpnService::class.java).apply {
            action = TunVpnService.ACTION_START
        })
    }
}
