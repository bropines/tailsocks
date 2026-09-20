package io.github.bropines.tailscaled.core

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import appctr.Appctr
import io.github.bropines.tailscaled.BuildConfig

/**
 * Everything worth knowing about this install when something goes wrong, in one block of
 * plain text: the versions, the device, the permissions that decide whether the service
 * survives, what the tunnel is made of, and how much the app has been used.
 *
 * It answers the questions every issue starts with — which build, which Android, root or
 * not, which engine, was the battery optimisation ever turned off — so that neither side
 * has to ask them. Nothing here identifies a tailnet: no node names, no addresses, no
 * keys, no account names. The auth key and the login server are reported as present or
 * absent and never by value.
 */
object Diagnostics {

    fun report(context: Context): String = buildString {
        appendLine("--- TAILSOCKS DIAGNOSTICS ---")
        appendLine(versions(context))
        appendLine(device())
        appendLine(permissions(context))
        appendLine(network(context))
        appendLine(tunnel(context))
        appendLine(profiles(context))
        appendLine(usage(context))
        append("-----------------------------")
    }

    private fun versions(context: Context): String {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }.getOrNull() ?: "sideloaded"
        val core = runCatching { Appctr.getCoreVersion() }.getOrDefault("unknown")
        return """
            App: ${info?.versionName ?: "unknown"} (code ${info?.longVersionCode ?: 0}, ${BuildConfig.BUILD_TYPE})
            Core: $core
            Installed by: $installer
        """.trimIndent()
    }

    private fun device(): String = """
        Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
        Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.VERSION.SECURITY_PATCH}
        ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}
    """.trimIndent()

    /**
     * The three the service's life depends on. A killed service with battery optimisation
     * still on is not a bug report, it is the setting — and this is where that shows.
     */
    private fun permissions(context: Context): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        val exactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)?.canScheduleExactAlarms() == true
        } else true
        val notifications = runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        }.getOrDefault(true)
        return """
            Battery unrestricted: $unrestricted
            Exact alarms: $exactAlarms
            Notifications: $notifications
        """.trimIndent()
    }

    private fun network(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        // Another app holding Android's single VPN slot is behind a whole class of reports.
        val foreignVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return """
            Transport: $transport (metered: $metered)
            VPN transport on the active network: $foreignVpn
        """.trimIndent()
    }

    private fun tunnel(context: Context): String {
        val root = GlobalSettings.isRootModeEnabled(context)
        val tun = GlobalSettings.isTunModeEnabled(context)
        val engine = GlobalSettings.getTunEngine(context)
        return """
            Service running: ${runCatching { Appctr.isRunning() }.getOrDefault(false)}
            Mode: ${if (root) "root" else if (tun) "tun" else "proxy"}
            TUN: ${if (tun) "on (engine: $engine, ipv6: ${GlobalSettings.isTunIpv6Enabled(context)})" else "off"}
            Root: ${if (root) "on (kernel tun: ${GlobalSettings.isRootTunEnabled(context)}, dns redirect: ${GlobalSettings.isRootDnsRedirectEnabled(context)}, yielded: ${GlobalSettings.isRootRoutingYielded(context)}, shared: ${GlobalSettings.isRootRoutingShared(context)})" else "off"}
            SOCKS5: ${GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")}, HTTP: ${GlobalSettings.getString(context, "httpproxy", "")}
            DNS proxy: ${GlobalSettings.getString(context, "dns_proxy", "")}
            LAN access: ${GlobalSettings.isLanAccessEnabled(context)}
            Control proxy: ${GlobalSettings.isCPProxyEnabled(context)} (ByeDPI: ${GlobalSettings.isCPByeDpiEnabled(context)})
            Accept routes: ${GlobalSettings.getBoolean(context, "accept_routes", false)}, accept DNS: ${GlobalSettings.getBoolean(context, "accept_dns", true)}
            Auto-reconnect: ${GlobalSettings.isAutoReconnectEnabled(context)}, watchdog: ${GlobalSettings.isServiceWatchdogEnabled(context)}, autostart: ${GlobalSettings.isAutoStartEnabled(context)}
        """.trimIndent()
    }

    private fun profiles(context: Context): String {
        val accounts = AccountManager.getAccounts(context)
        val active = AccountManager.getActiveAccount(context)
        val facts = AccountManager.facts(context, active.id)
        val prefs = context.getSharedPreferences("appctr_${active.id}", Context.MODE_PRIVATE)
        return """
            Accounts: ${accounts.size}
            Active profile: registered ${facts.registered}, auth key ${if (facts.hasAuthKey) "present" else "absent"}, honest OS ${facts.honestOs}, custom login server ${facts.loginServer != "tailscale.com"}
            Exit node set: ${prefs.getString("exit_node_id", "")?.isNotEmpty() == true}
        """.trimIndent()
    }

    /** How much the app has been used, as counted for the status card's own amusement. */
    private fun usage(context: Context): String = """
        Holds: ${StatusAsides.count(context, StatusAsides.HOLDS)}, toggles: ${StatusAsides.count(context, StatusAsides.TOGGLES)}, ping-alls: ${StatusAsides.count(context, StatusAsides.PINGS)}, account switches: ${StatusAsides.count(context, StatusAsides.SWITCHES)}
    """.trimIndent()
}
