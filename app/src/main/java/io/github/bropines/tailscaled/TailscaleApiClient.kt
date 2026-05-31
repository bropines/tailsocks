package io.github.bropines.tailscaled

import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class TailscaleApiClient(private val token: String, tailnetName: String = "") {
    private val baseUrl = "https://api.tailscale.com/api/v2"
    private val tailnet = if (tailnetName.isBlank()) "-" else tailnetName

    private fun request(method: String, path: String, body: Any? = null): String {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(Gson().toJson(body))
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
}
