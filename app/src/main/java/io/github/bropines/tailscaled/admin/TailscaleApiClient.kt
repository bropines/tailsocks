package io.github.bropines.tailscaled.admin

import android.util.Log
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.NetAddr
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
                val host = NetAddr.dialableHost(addr)
                val port = NetAddr.port(addr) ?: 48115
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

            // 3. SOCKS5 has no per-connection auth in the JDK, so a default
            //    Authenticator is unavoidable — but it is scoped to the exact
            //    proxy endpoint and to PROXY requests only. An unscoped default
            //    handed the proxy password to any host that answered with a 401
            //    or 407, including remote avatar URLs and the update check that
            //    share this process.
            val socksAddr = (selectedProxy.address() as? InetSocketAddress)
            if (socksAddr != null) {
                val proxyHostName = socksAddr.hostString
                val proxyPortNum = socksAddr.port
                val user = authUser
                val pass = authPass
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                        if (requestorType != RequestorType.PROXY) return null
                        if (requestingHost != proxyHostName || requestingPort != proxyPortNum) return null
                        return java.net.PasswordAuthentication(user, pass.toCharArray())
                    }
                })
            }
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

        return executeCall(req) { json ->
            require(json.isNotBlank()) { "Empty OAuth token response" }
            AppJson.decodeFromString<OauthTokenResponse>(json)
        }
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
                // body is a JSON String or a kotlinx JsonObject (whose toString() is valid JSON).
                val json = if (body is String) body else body.toString()
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
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<ListDevicesResponse>(json) }.getOrNull()
        return response?.devices ?: emptyList()
    }

    fun expireDevice(deviceId: String) {
        request("POST", "/device/$deviceId/expire")
    }

    fun deleteDevice(deviceId: String) {
        request("DELETE", "/device/$deviceId")
    }

    fun setDeviceAuthorized(deviceId: String, authorized: Boolean) {
        request("POST", "/device/$deviceId/authorized", buildJsonObject { put("authorized", authorized) })
    }

    fun renameDevice(deviceId: String, name: String) {
        request("POST", "/device/$deviceId/name", buildJsonObject { put("name", name) })
    }

    fun setDeviceTags(deviceId: String, tags: List<String>) {
        request("POST", "/device/$deviceId/tags", buildJsonObject {
            putJsonArray("tags") { tags.forEach { add(it) } }
        })
    }

    // DNS
    fun getDnsPreferences(): DnsPreferences {
        val json = request("GET", "/tailnet/$tailnet/dns/preferences")
        if (json.isBlank()) return DnsPreferences()
        return runCatching { AppJson.decodeFromString<DnsPreferences>(json) }.getOrDefault(DnsPreferences())
    }

    fun updateDnsPreferences(magicDns: Boolean) {
        request("POST", "/tailnet/$tailnet/dns/preferences", buildJsonObject { put("magicDNS", magicDns) })
    }

    fun getDnsNameservers(): List<String> {
        val json = request("GET", "/tailnet/$tailnet/dns/nameservers")
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<DnsNameserversResponse>(json) }.getOrNull()
        return response?.dns ?: emptyList()
    }

    fun setDnsNameservers(nameservers: List<String>) {
        request("POST", "/tailnet/$tailnet/dns/nameservers", buildJsonObject {
            putJsonArray("dns") { nameservers.forEach { add(it) } }
        })
    }

    // Split DNS
    fun getSplitDns(): Map<String, List<String>> {
        val json = request("GET", "/tailnet/$tailnet/dns/split-dns")
        if (json.isBlank()) return emptyMap()
        return runCatching { AppJson.decodeFromString<Map<String, List<String>>>(json) }.getOrDefault(emptyMap())
    }

    fun updateSplitDns(domain: String, nameservers: List<String>?) {
        // Must explicitly include the domain -> nameservers mapping, with an
        // explicit null when clearing (the old GsonBuilder used serializeNulls()).
        val body = buildJsonObject {
            if (nameservers == null) {
                put(domain, JsonNull)
            } else {
                putJsonArray(domain) { nameservers.forEach { add(it) } }
            }
        }
        request("PATCH", "/tailnet/$tailnet/dns/split-dns", body)
    }

    // DNS Search Paths
    fun listDnsSearchPaths(): List<String> {
        val json = request("GET", "/tailnet/$tailnet/dns/searchpaths")
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<DnsSearchPaths>(json) }.getOrNull()
        return response?.searchPaths ?: emptyList()
    }

    fun setDnsSearchPaths(searchPaths: List<String>) {
        request("POST", "/tailnet/$tailnet/dns/searchpaths", AppJson.encodeToString(DnsSearchPaths(searchPaths)))
    }

    // Tailnet Settings
    fun getTailnetSettings(): TailnetSettings {
        val json = request("GET", "/tailnet/$tailnet/settings")
        if (json.isBlank()) return TailnetSettings()
        return runCatching { AppJson.decodeFromString<TailnetSettings>(json) }.getOrDefault(TailnetSettings())
    }

    fun updateTailnetSettings(settings: TailnetSettings): TailnetSettings {
        val json = request("PATCH", "/tailnet/$tailnet/settings", AppJson.encodeToString(settings))
        if (json.isBlank()) return TailnetSettings()
        return runCatching { AppJson.decodeFromString<TailnetSettings>(json) }.getOrDefault(TailnetSettings())
    }

    // Users
    fun listUsers(): List<ApiUser> {
        val json = request("GET", "/tailnet/$tailnet/users")
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<ListUsersResponse>(json) }.getOrNull()
        return response?.users ?: emptyList()
    }

    // Keys
    fun listKeys(): List<ApiKeyInfo> {
        val json = request("GET", "/tailnet/$tailnet/keys")
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<ListKeysResponse>(json) }.getOrNull()
        return response?.keys ?: emptyList()
    }

    fun createKey(
        description: String,
        expirySeconds: Long,
        ephemeral: Boolean,
        preauthorized: Boolean,
        tags: List<String>?
    ): ApiKeyInfo {
        val body = buildJsonObject {
            putJsonObject("capabilities") {
                putJsonObject("devices") {
                    putJsonObject("create") {
                        put("reusable", !ephemeral)
                        put("ephemeral", ephemeral)
                        put("preauthorized", preauthorized)
                        // Gson (no serializeNulls) omitted a null "tags"; match that.
                        if (tags != null) {
                            putJsonArray("tags") { tags.forEach { add(it) } }
                        }
                    }
                }
            }
            put("expirySeconds", expirySeconds)
            put("keyType", "auth")
            if (description.isNotBlank()) {
                put("description", description)
            }
        }
        val json = request("POST", "/tailnet/$tailnet/keys", body)
        if (json.isBlank()) return ApiKeyInfo()
        return runCatching { AppJson.decodeFromString<ApiKeyInfo>(json) }.getOrDefault(ApiKeyInfo())
    }

    fun revokeKey(keyId: String) {
        request("DELETE", "/tailnet/$tailnet/keys/$keyId")
    }

    // User Management
    fun changeUserRole(userId: String, role: String) {
        request("POST", "/users/$userId/role", buildJsonObject { put("role", role) })
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
        request("POST", "/device/$deviceId/key", buildJsonObject { put("keyExpiryDisabled", disabled) })
    }

    // Device Routes
    fun getDeviceRoutes(deviceId: String): DeviceRoutes {
        val json = request("GET", "/device/$deviceId/routes")
        if (json.isBlank()) return DeviceRoutes()
        return runCatching { AppJson.decodeFromString<DeviceRoutes>(json) }.getOrDefault(DeviceRoutes())
    }

    fun setDeviceRoutes(deviceId: String, routes: List<String>): DeviceRoutes {
        val json = request("POST", "/device/$deviceId/routes", buildJsonObject {
            putJsonArray("routes") { routes.forEach { add(it) } }
        })
        if (json.isBlank()) return DeviceRoutes()
        return runCatching { AppJson.decodeFromString<DeviceRoutes>(json) }.getOrDefault(DeviceRoutes())
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
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<ListWebhooksResponse>(json) }.getOrNull()
        return response?.webhooks ?: emptyList()
    }

    fun createWebhook(endpointUrl: String, subscribedEvents: List<String>): WebhookEndpoint {
        val body = buildJsonObject {
            put("endpointUrl", endpointUrl)
            putJsonArray("subscribedEvents") { subscribedEvents.forEach { add(it) } }
        }
        val json = request("POST", "/tailnet/$tailnet/webhooks", body)
        if (json.isBlank()) return WebhookEndpoint()
        return runCatching { AppJson.decodeFromString<WebhookEndpoint>(json) }.getOrDefault(WebhookEndpoint())
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
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<ListServicesResponse>(json) }.getOrNull()
        return response?.vipServices ?: emptyList()
    }

    fun listServiceHosts(serviceName: String): List<ServiceHostInfo> {
        val json = request("GET", "/tailnet/$tailnet/services/$serviceName/devices")
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<ListServiceHostsResponse>(json) }.getOrNull()
        return response?.hosts ?: emptyList()
    }

    fun setServiceDeviceApproved(serviceName: String, deviceId: String, approved: Boolean) {
        request("POST", "/tailnet/$tailnet/services/$serviceName/device/$deviceId/approved", buildJsonObject {
            put("approved", approved)
        })
    }

    fun triggerDeviceUpdate(deviceId: String, machineKey: String, nodeKey: String): String {
        val body = buildJsonObject {
            put("state", "update-client")
            put("machinekey", machineKey)
            put("nodekey", nodeKey)
        }
        return request("POST", "https://login.tailscale.com/admin/api/machines", body)
    }

    fun getDeviceUpdateStatus(deviceId: String): String {
        return request("GET", "https://login.tailscale.com/admin/api/public/device/$deviceId/update-status")
    }

    fun getAuditLogs(start: String, end: String): List<ApiAuditLogEntry> {
        val path = "/tailnet/$tailnet/logging/configuration?start=$start&end=$end"
        val json = request("GET", path)
        if (json.isBlank()) return emptyList()
        val response = runCatching { AppJson.decodeFromString<AuditLogsResponse>(json) }.getOrNull()
        return response?.logs ?: emptyList()
    }
}
