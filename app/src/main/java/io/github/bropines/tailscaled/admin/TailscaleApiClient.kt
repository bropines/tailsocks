package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.Authenticator
import java.net.PasswordAuthentication

class TailscaleApiClient(
    private val token: String,
    tailnetName: String = "",
    private val proxyMode: String = "DIRECT",
    private val proxyHost: String = "",
    private val proxyPort: Int = 0,
    private val proxyUser: String = "",
    private val proxyPass: String = "",
    private val localSocksAddr: String = "",
    private val localSocksUser: String = "",
    private val localSocksPass: String = "",
    private val clientId: String = "",
    private val clientSecret: String = ""
) {
    private val baseUrl = "https://api.tailscale.com/api/v2"
    private val tailnet = if (tailnetName.isBlank()) "-" else tailnetName

    private var cachedAccessToken: String? = null
    private var tokenExpiryTime: Long = 0

    private fun openConnection(url: URL): HttpURLConnection {
        val connectionProxy = when (proxyMode.uppercase()) {
            "LOCAL_SOCKS5" -> {
                val addr = localSocksAddr.takeIf { it.isNotEmpty() } ?: "127.0.0.1:48115"
                parseSocksProxy(addr)
            }
            "CUSTOM_SOCKS5" -> {
                if (proxyHost.isNotEmpty() && proxyPort > 0) {
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
                } else {
                    Proxy.NO_PROXY
                }
            }
            else -> Proxy.NO_PROXY
        }

        val conn = if (connectionProxy != Proxy.NO_PROXY) {
            url.openConnection(connectionProxy) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }

        // Set up Authenticator for proxy auth
        if (proxyMode.uppercase() == "CUSTOM_SOCKS5" && proxyUser.isNotEmpty() && proxyPass.isNotEmpty()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestingHost == proxyHost && requestingPort == proxyPort) {
                        return PasswordAuthentication(proxyUser, proxyPass.toCharArray())
                    }
                    return null
                }
            })
        } else if (proxyMode.uppercase() == "LOCAL_SOCKS5" && localSocksUser.isNotEmpty() && localSocksPass.isNotEmpty()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    val localParts = localSocksAddr.split(":")
                    val localHost = localParts.getOrNull(0) ?: "127.0.0.1"
                    val localPort = localParts.getOrNull(1)?.toIntOrNull() ?: 48115
                    if (requestingHost == localHost && requestingPort == localPort) {
                        return PasswordAuthentication(localSocksUser, localSocksPass.toCharArray())
                    }
                    return null
                }
            })
        }

        return conn
    }

    private fun parseSocksProxy(addr: String): Proxy {
        return try {
            val parts = addr.split(":")
            val host = parts[0]
            val port = parts[1].toInt()
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        } catch (e: Exception) {
            Proxy.NO_PROXY
        }
    }

    @Synchronized
    private fun getValidToken(): String {
        if (clientSecret.isBlank()) {
            return token
        }

        val now = System.currentTimeMillis()
        if (cachedAccessToken != null && now < tokenExpiryTime) {
            return cachedAccessToken!!
        }

        val fetched = fetchOauthToken()
        cachedAccessToken = fetched.accessToken
        tokenExpiryTime = now + (fetched.expiresIn * 1000L) - 60000L
        return cachedAccessToken!!
    }

    private fun fetchOauthToken(): OauthTokenResponse {
        val url = URL("https://api.tailscale.com/api/v2/oauth/token")
        val conn = openConnection(url)
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")

        val body = "grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret"
        conn.outputStream.use { os ->
            os.write(body.toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            return Gson().fromJson(json, OauthTokenResponse::class.java)
        } else {
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("OAuth HTTP $code: $errText")
        }
    }

    private fun request(method: String, path: String, body: Any? = null): String {
        val activeToken = getValidToken()
        val url = if (path.startsWith("http")) URL(path) else URL("$baseUrl$path")
        val conn = openConnection(url)
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $activeToken")
        conn.setRequestProperty("Accept", "application/json")
        
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val writer = OutputStreamWriter(conn.outputStream)
            val json = if (body is String) body else Gson().toJson(body)
            writer.write(json)
            writer.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("HTTP $code: $errText")
        }
    }

    // Devices
    fun listDevices(): List<ApiDevice> {
        val json = request("GET", "/tailnet/$tailnet/devices")
        val response = Gson().fromJson(json, ListDevicesResponse::class.java)
        return response.devices ?: emptyList()
    }

    fun expireDevice(deviceId: String) {
        request("POST", "/device/$deviceId/expire")
    }

    fun deleteDevice(deviceId: String) {
        request("DELETE", "/device/$deviceId")
    }

    fun setDeviceAuthorized(deviceId: String, authorized: Boolean) {
        request("POST", "/device/$deviceId/authorized", mapOf("authorized" to authorized))
    }

    fun renameDevice(deviceId: String, name: String) {
        request("POST", "/device/$deviceId/name", mapOf("name" to name))
    }

    fun setDeviceTags(deviceId: String, tags: List<String>) {
        request("POST", "/device/$deviceId/tags", mapOf("tags" to tags))
    }

    // DNS
    fun getDnsPreferences(): DnsPreferences {
        val json = request("GET", "/tailnet/$tailnet/dns/preferences")
        return Gson().fromJson(json, DnsPreferences::class.java)
    }

    fun updateDnsPreferences(magicDns: Boolean) {
        request("POST", "/tailnet/$tailnet/dns/preferences", mapOf("magicDNS" to magicDns))
    }

    fun getDnsNameservers(): List<String> {
        val json = request("GET", "/tailnet/$tailnet/dns/nameservers")
        val response = Gson().fromJson(json, DnsNameserversResponse::class.java)
        return response.dns ?: emptyList()
    }

    fun setDnsNameservers(nameservers: List<String>) {
        request("POST", "/tailnet/$tailnet/dns/nameservers", mapOf("dns" to nameservers))
    }

    // Split DNS
    fun getSplitDns(): Map<String, List<String>> {
        val json = request("GET", "/tailnet/$tailnet/dns/split-dns")
        val type = object : com.google.gson.reflect.TypeToken<Map<String, List<String>>>() {}.type
        return Gson().fromJson(json, type) ?: emptyMap()
    }

    fun updateSplitDns(domain: String, nameservers: List<String>?) {
        val body = mapOf(domain to nameservers)
        val jsonBody = com.google.gson.GsonBuilder().serializeNulls().create().toJson(body)
        request("PATCH", "/tailnet/$tailnet/dns/split-dns", jsonBody)
    }

    // DNS Search Paths
    fun listDnsSearchPaths(): List<String> {
        val json = request("GET", "/tailnet/$tailnet/dns/searchpaths")
        val response = Gson().fromJson(json, DnsSearchPaths::class.java)
        return response.searchPaths
    }

    fun setDnsSearchPaths(searchPaths: List<String>) {
        val body = DnsSearchPaths(searchPaths)
        request("POST", "/tailnet/$tailnet/dns/searchpaths", body)
    }

    // Tailnet Settings
    fun getTailnetSettings(): TailnetSettings {
        val json = request("GET", "/tailnet/$tailnet/settings")
        return Gson().fromJson(json, TailnetSettings::class.java)
    }

    fun updateTailnetSettings(settings: TailnetSettings): TailnetSettings {
        val json = request("PATCH", "/tailnet/$tailnet/settings", settings)
        return Gson().fromJson(json, TailnetSettings::class.java)
    }

    // Users
    fun listUsers(): List<ApiUser> {
        val json = request("GET", "/tailnet/$tailnet/users")
        val response = Gson().fromJson(json, ListUsersResponse::class.java)
        return response.users ?: emptyList()
    }

    // Keys
    fun listKeys(): List<ApiKeyInfo> {
        val json = request("GET", "/tailnet/$tailnet/keys")
        val response = Gson().fromJson(json, ListKeysResponse::class.java)
        return response.keys ?: emptyList()
    }

    fun createKey(
        description: String,
        expirySeconds: Long,
        ephemeral: Boolean,
        preauthorized: Boolean,
        tags: List<String>?
    ): ApiKeyInfo {
        val capabilities = mapOf(
            "devices" to mapOf(
                "create" to mapOf(
                    "reusable" to !ephemeral,
                    "ephemeral" to ephemeral,
                    "preauthorized" to preauthorized,
                    "tags" to tags
                )
            )
        )
        val body = mutableMapOf<String, Any>(
            "capabilities" to capabilities,
            "expirySeconds" to expirySeconds,
            "keyType" to "auth"
        )
        if (description.isNotBlank()) {
            body["description"] = description
        }
        val json = request("POST", "/tailnet/$tailnet/keys", body)
        return Gson().fromJson(json, ApiKeyInfo::class.java)
    }

    fun revokeKey(keyId: String) {
        request("DELETE", "/tailnet/$tailnet/keys/$keyId")
    }

    // User Management
    fun changeUserRole(userId: String, role: String) {
        request("POST", "/users/$userId/role", mapOf("role" to role))
    }

    fun approveUser(userId: String) {
        request("POST", "/users/$userId/approve")
    }

    fun suspendUser(userId: String) {
        request("POST", "/users/$userId/suspend")
    }

    fun restoreUser(userId: String) {
        request("POST", "/users/$userId/restore")
    }

    fun deleteUser(userId: String) {
        request("POST", "/users/$userId/delete")
    }

    // Key Expiry
    fun setDeviceKeyExpiryDisabled(deviceId: String, disabled: Boolean) {
        request("POST", "/device/$deviceId/key", mapOf("keyExpiryDisabled" to disabled))
    }

    // Device Routes
    fun getDeviceRoutes(deviceId: String): DeviceRoutes {
        val json = request("GET", "/device/$deviceId/routes")
        return Gson().fromJson(json, DeviceRoutes::class.java)
    }

    fun setDeviceRoutes(deviceId: String, routes: List<String>): DeviceRoutes {
        val json = request("POST", "/device/$deviceId/routes", mapOf("routes" to routes))
        return Gson().fromJson(json, DeviceRoutes::class.java)
    }

    // ACL Tags
    fun getTailnetTags(): List<String> {
        return try {
            val json = request("GET", "/tailnet/$tailnet/acl")
            val tagRegex = Regex("""\"(tag:[a-zA-Z0-9-_\/]+)\"""")
            tagRegex.findAll(json).map { it.groupValues[1] }.distinct().sorted().toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Webhooks
    fun listWebhooks(): List<WebhookEndpoint> {
        val json = request("GET", "/tailnet/$tailnet/webhooks")
        val response = Gson().fromJson(json, ListWebhooksResponse::class.java)
        return response.webhooks ?: emptyList()
    }

    fun createWebhook(endpointUrl: String, subscribedEvents: List<String>): WebhookEndpoint {
        val body = mapOf(
            "endpointUrl" to endpointUrl,
            "subscribedEvents" to subscribedEvents
        )
        val json = request("POST", "/tailnet/$tailnet/webhooks", body)
        return Gson().fromJson(json, WebhookEndpoint::class.java)
    }

    fun deleteWebhook(endpointId: String) {
        request("DELETE", "/webhooks/$endpointId")
    }

    fun testWebhook(endpointId: String) {
        request("POST", "/webhooks/$endpointId/test")
    }

    // Virtual Services
    fun listTailnetServices(): List<VIPServiceInfo> {
        val json = request("GET", "/tailnet/$tailnet/services")
        val response = Gson().fromJson(json, ListServicesResponse::class.java)
        return response.vipServices ?: emptyList()
    }

    fun listServiceHosts(serviceName: String): List<ServiceHostInfo> {
        val json = request("GET", "/tailnet/$tailnet/services/$serviceName/devices")
        val response = Gson().fromJson(json, ListServiceHostsResponse::class.java)
        return response.hosts ?: emptyList()
    }

    fun setServiceDeviceApproved(serviceName: String, deviceId: String, approved: Boolean) {
        request("POST", "/tailnet/$tailnet/services/$serviceName/device/$deviceId/approved", mapOf("approved" to approved))
    }

    fun triggerDeviceUpdate(deviceId: String, machineKey: String, nodeKey: String): String {
        val body = mapOf(
            "state" to "update-client",
            "machinekey" to machineKey,
            "nodekey" to nodeKey
        )
        return request("POST", "https://login.tailscale.com/admin/api/machines", body)
    }

    fun getDeviceUpdateStatus(deviceId: String): String {
        return request("GET", "https://login.tailscale.com/admin/api/public/device/$deviceId/update-status")
    }

    fun getAuditLogs(start: String, end: String): List<ApiAuditLogEntry> {
        val path = "/tailnet/$tailnet/logging/configuration?start=$start&end=$end"
        val json = request("GET", path)
        val response = Gson().fromJson(json, AuditLogsResponse::class.java)
        return response.logs ?: emptyList()
    }
}
