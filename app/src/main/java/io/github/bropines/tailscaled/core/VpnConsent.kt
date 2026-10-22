package io.github.bropines.tailscaled.core

import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process

/**
 * Questions about the VPN slot that change nothing.
 *
 * [android.net.VpnService.prepare] is not a question. For an app the user has
 * already consented to, it makes that app the prepared one, and Android revokes
 * whatever VPN another app runs ("Vpn: Switched from <them> to <us>" in the
 * system log). Called from the main service's tick to check the permission, it
 * took the slot back a second after AdGuard or Exclave had taken it, and
 * established nothing — the phone was left with no VPN at all. Use prepare()
 * only where the user asks for the tunnel now; ask these instead.
 */
object VpnConsent {

    /** The app op Android's own Vpn.prepare() checks for the user's consent. */
    private const val OP_ACTIVATE_VPN = "android:activate_vpn"

    /** Whether the user has consented to this app's VPN; null when the system would not say. */
    fun given(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(OP_ACTIVATE_VPN, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(OP_ACTIVATE_VPN, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /**
     * Whether a VPN network is up that this app can see. Call it while this app
     * has no device of its own: then any VPN up is another app's. A VPN that
     * leaves this app out is invisible to it.
     */
    fun vpnUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)
}
