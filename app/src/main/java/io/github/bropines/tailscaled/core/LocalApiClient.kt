package io.github.bropines.tailscaled.core

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Pure Kotlin LocalAPI Client (Direct Unix Domain Socket)
 *
 * Connects directly to tailscaled.sock via Android's native LocalSocket and streams
 * HTTP/1.1 requests without invoking the Go/JNI bridge layer.
 */
class LocalApiClient(private val socketPathProvider: () -> String) {

    /**
     * Executes a raw HTTP request over Android's native LocalSocket Unix domain socket.
     */
    suspend fun executeRaw(method: String, path: String, body: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val socketPath = socketPathProvider()
            require(socketPath.isNotBlank()) { "LocalAPI socket path is empty" }

            val socket = LocalSocket()
            try {
                val address = LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                socket.connect(address)

                val outputStream = socket.outputStream
                val inputStream = socket.inputStream

                val reqBuilder = StringBuilder()
                reqBuilder.append("$method $path HTTP/1.1\r\n")
                reqBuilder.append("Host: local-tailscaled.sock\r\n")
                reqBuilder.append("Connection: close\r\n")

                val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
                if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                    reqBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
                    reqBuilder.append("Content-Type: application/json\r\n")
                }
                reqBuilder.append("\r\n")

                outputStream.write(reqBuilder.toString().toByteArray(StandardCharsets.UTF_8))
                if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                    outputStream.write(bodyBytes)
                }
                outputStream.flush()

                val buffer = ByteArray(4096)
                val responseStream = ByteArrayOutputStream()
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    responseStream.write(buffer, 0, read)
                }

                val fullResponse = responseStream.toString(StandardCharsets.UTF_8.name())
                val bodyOffset = fullResponse.indexOf("\r\n\r\n")
                if (bodyOffset != -1) {
                    fullResponse.substring(bodyOffset + 4)
                } else {
                    fullResponse
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    // --- 1. Node Status & Profiles ---

    suspend fun getStatus(includePeers: Boolean = false): Result<String> {
        val path = if (includePeers) "/localapi/v0/status?peers=true" else "/localapi/v0/status"
        return executeRaw("GET", path)
    }

    suspend fun getProfiles(): Result<String> = executeRaw("GET", "/localapi/v0/profiles/")

    suspend fun switchProfile(profileId: String): Result<String> =
        executeRaw("POST", "/localapi/v0/profiles/$profileId")

    // --- 2. Preferences & Lifecycle ---

    suspend fun getPrefs(): Result<String> = executeRaw("GET", "/localapi/v0/prefs")

    suspend fun patchPrefs(jsonPayload: String): Result<String> =
        executeRaw("PATCH", "/localapi/v0/prefs", jsonPayload)

    suspend fun startDaemon(jsonPayload: String): Result<String> =
        executeRaw("POST", "/localapi/v0/start", jsonPayload)

    suspend fun loginInteractive(): Result<String> =
        executeRaw("POST", "/localapi/v0/login-interactive")

    suspend fun logoutDaemon(): Result<String> =
        executeRaw("POST", "/localapi/v0/logout")

    // --- 3. Diagnostics & Topologies ---

    suspend fun getNetcheck(requestDerp: Boolean = false): Result<String> {
        val path = if (requestDerp) "/localapi/v0/netcheck?full=true" else "/localapi/v0/netcheck"
        return executeRaw("GET", path)
    }

    suspend fun ping(targetIp: String, pingType: String = "disco"): Result<String> {
        val json = """{"IP":"$targetIp","Type":"$pingType"}"""
        return executeRaw("POST", "/localapi/v0/ping", json)
    }

    suspend fun whoIs(addr: String): Result<String> =
        executeRaw("GET", "/localapi/v0/whois?addr=$addr")

    suspend fun getDerpMap(): Result<String> =
        executeRaw("GET", "/localapi/v0/derp/map")

    // --- 4. Taildrive & Files ---

    suspend fun getDriveShares(): Result<String> = executeRaw("GET", "/localapi/v0/drive/shares")

    suspend fun putDriveShare(name: String, path: String): Result<String> {
        val json = """{"name":"$name","path":"$path"}"""
        return executeRaw("PUT", "/localapi/v0/drive/shares", json)
    }

    suspend fun deleteDriveShare(name: String): Result<String> =
        executeRaw("DELETE", "/localapi/v0/drive/shares/$name")

    suspend fun setFileServerAddress(addr: String): Result<String> {
        val json = """{"address":"$addr"}"""
        return executeRaw("PUT", "/localapi/v0/drive/fileserver-address", json)
    }

    suspend fun getFileTargets(): Result<String> = executeRaw("GET", "/localapi/v0/file-targets")

    suspend fun getWaitingFiles(): Result<String> = executeRaw("GET", "/localapi/v0/files/")

    // --- 5. Serve & Funnel ---

    suspend fun getServeConfig(): Result<String> = executeRaw("GET", "/localapi/v0/serve-config")

    suspend fun setServeConfig(configJson: String): Result<String> {
        // Reset-then-Apply pattern
        executeRaw("POST", "/localapi/v0/serve-config", "{}")
        return if (configJson.isNotBlank() && configJson != "{}") {
            executeRaw("POST", "/localapi/v0/serve-config", configJson)
        } else {
            Result.success("{}")
        }
    }

    suspend fun resetServeConfig(): Result<String> = setServeConfig("{}")

    // --- 6. DNS ---

    suspend fun setDns(dnsJson: String): Result<String> =
        executeRaw("POST", "/localapi/v0/set-dns", dnsJson)
}
