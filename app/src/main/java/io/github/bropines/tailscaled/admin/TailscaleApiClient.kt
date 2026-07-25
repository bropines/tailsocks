package io.github.bropines.tailscaled.admin

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

class TailscaleApiClient(
    private val token: String,
    tailnetName: String = "",
    private val proxyMode: String = "CONTROL_PLANE",
    private val proxyHost: String = "",
    private val proxyPort: Int = 0,
    private val proxyUser: String = "",
    private val proxyPass: String = "",
    private val localSocksAddr: String = "",
    private val localSocksUser: String = "",
    private val localSocksPass: String = "",
    private val clientId: String = "",
    private val clientSecret: String = "",
    private val controlProxyUrl: String = ""
) {
    private val baseUrl = "https://api.tailscale.com/api/v2"
    private val tailnet = if (tailnetName.isBlank()) "-" else tailnetName

    private var cachedAccessToken: String? = null
    private var tokenExpiryTime: Long = 0

    private val httpClient: OkHttpClient by lazy {
        buildOkHttpClient()
    }

    private data class ParsedProxyInfo(
        val host: String,
        val port: Int,
        val user: String,
        val pass: String,
        val proxy: Proxy
    )

    private fun parseProxyFromUrl(urlStr: String): ParsedProxyInfo? {
        try {
            val trimmed = urlStr.trim()
            if (trimmed.isEmpty()) return null
            val uri = URI(if (!trimmed.contains("://")) "socks5://$trimmed" else trimmed)
            val scheme = uri.scheme?.lowercase() ?: "socks5"
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else (if (scheme.startsWith("http")) 8080 else 1080)

            var user = ""
            var pass = ""
            if (uri.userInfo != null && uri.userInfo.contains(":")) {
                val parts = uri.userInfo.split(":", limit = 2)
                user = parts[0]
                pass = parts[1]
            }

            val proxyType = if (scheme.startsWith("http")) Proxy.Type.HTTP else Proxy.Type.SOCKS
            return ParsedProxyInfo(host, port, user, pass, Proxy(proxyType, InetSocketAddress(host, port)))
        } catch (e: Exception) {
            return null
        }
    }

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        var authUser = ""
        var authPass = ""

        val selectedProxy = when (proxyMode.uppercase()) {
            "CONTROL_PLANE", "AUTO" -> {
                if (controlProxyUrl.isNotEmpty()) {
                    val parsed = parseProxyFromUrl(controlProxyUrl)
                    if (parsed != null) {
                        authUser = parsed.user
                        authPass = parsed.pass
                        parsed.proxy
                    } else Proxy.NO_PROXY
                } else Proxy.NO_PROXY
            }
            "LOCAL_SOCKS5" -> {
                val addr = localSocksAddr.takeIf { it.isNotEmpty() } ?: "127.0.0.1:48115"
                val parts = addr.split(":")
                val host = parts.getOrNull(0) ?: "127.0.0.1"
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 48115
                authUser = localSocksUser
                authPass = localSocksPass
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            }
            "CUSTOM_SOCKS5", "CUSTOM_PROXY" -> {
                if (proxyHost.isNotEmpty() && proxyPort > 0) {
                    authUser = proxyUser
                    authPass = proxyPass
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
                } else Proxy.NO_PROXY
            }
            else -> Proxy.NO_PROXY
        }

        if (selectedProxy != Proxy.NO_PROXY) {
            builder.proxy(selectedProxy)
        }

        if (authUser.isNotEmpty() && authPass.isNotEmpty()) {
            val credentials = Credentials.basic(authUser, authPass)
            
            // 1. Authenticator for HTTP 407 responses
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null // Prevent retry loop if credentials failed
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credentials)
                        .build()
                }
            }

            // 2. Pre-emptive network interceptor for HTTP proxy CONNECT requests
            builder.addNetworkInterceptor { chain ->
                var request = chain.request()
                if (selectedProxy.type() == Proxy.Type.HTTP && request.header("Proxy-Authorization") == null) {
                    request = request.newBuilder()
                        .header("Proxy-Authorization", credentials)
                        .build()
                }
                chain.proceed(request)
            }

            // 3. JVM-wide Authenticator for SOCKS5 proxy authentication
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                    return java.net.PasswordAuthentication(authUser, authPass.toCharArray())
                }
            })
        }

        return builder.build()
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

    private fun <T> executeCall(req: Request, parse: (String) -> T): T {
        try {
            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    return parse(bodyStr)
                } else {
                    throw Exception("HTTP ${resp.code}: $bodyStr")
                }
            }
        } catch (e: Exception) {
            Log.e("TailscaleApiClient", "Request to ${req.url} failed (proxyMode=$proxyMode): ${e.message}", e)

            // If proxy fails with IOException (unexpected end of stream / connection reset), try fallback to DIRECT connection
            if (proxyMode.uppercase() in listOf("CONTROL_PLANE", "AUTO") && e is java.io.IOException) {
                Log.w("TailscaleApiClient", "Proxy connection failed, trying Direct fallback...")
                try {
                    val directClient = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .proxy(Proxy.NO_PROXY)
                        .build()
                    directClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            return parse(bodyStr)
                        }
                    }
                } catch (fallbackErr: Exception) {
                    Log.e("TailscaleApiClient", "Direct fallback also failed: ${fallbackErr.message}")
                }
            }

            throw Exception("API Error: ${e.message ?: "Connection error"}")
        }
    }

    private fun fetchOauthToken(): OauthTokenResponse {
        val formBody = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val req = Request.Builder()
            .url("https://api.tailscale.com/api/v2/oauth/token")
            .post(formBody)
            .header("Accept", "application/json")
            .build()

        return executeCall(req) { json -> Gson().fromJson(json, OauthTokenResponse::class.java) }
    }

    private fun request(method: String, path: String, body: Any? = null): String {
        val activeToken = getValidToken()
        val url = if (path.startsWith("http")) path else "$baseUrl$path"

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $activeToken")
            .header("Accept", "application/json")

        val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

        val requestBody = when {
            body == null && (method == "POST" || method == "PUT" || method == "PATCH") -> {
                "".toRequestBody(mediaTypeJson)
            }
            body != null -> {
                val json = if (body is String) body else Gson().toJson(body)
                json.toRequestBody(mediaTypeJson)
            }
            else -> null
        }

        reqBuilder.method(method, requestBody)
        return executeCall(reqBuilder.build()) { it }
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
