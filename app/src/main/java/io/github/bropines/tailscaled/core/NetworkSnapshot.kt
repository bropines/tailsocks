package io.github.bropines.tailscaled.core

import android.net.ConnectivityManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the bridge's network monitors are told about this device's network.
 *
 * Android denies an app NETLINK_ROUTE, so netmon cannot list the interfaces
 * itself: the bridge's interface getter (appctr/netmon_android.go) reads the
 * list the app hands it, and the default route comes from the app too. Both
 * the daemon's service and tailcat's report through here.
 */
object NetworkSnapshot {
    /** The up, non-loopback interfaces that have an address, as the getter reads them. */
    fun interfacesJson(): String {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        return buildJsonArray {
            if (interfaces != null) {
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = iface.inetAddresses?.toList()?.filter { !it.isLoopbackAddress }?.map { it.hostAddress ?: "" } ?: emptyList()
                    if (addrs.isEmpty()) continue
                    addJsonObject {
                        put("name", iface.name)
                        putJsonArray("addresses") { addrs.forEach { add(it) } }
                        put("up", iface.isUp)
                        put("mtu", iface.mtu)
                    }
                }
            }
        }.toString()
    }

    /** The interface of the active network, as the platform names it, or "". */
    fun defaultRouteInterface(cm: ConnectivityManager): String =
        runCatching { cm.getLinkProperties(cm.activeNetwork)?.interfaceName }.getOrNull().orEmpty()
}
