package io.github.bropines.tailscaled.core

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File

object RootUtils {
    private const val TAG = "RootUtils"
    const val SERVICE_D_DIR = "/data/adb/service.d"
    const val SERVICE_SCRIPT_PATH = "$SERVICE_D_DIR/tailscaled.sh"

    /**
     * Checks if the root daemon is actually alive by attempting a real
     * LocalSocket connect() to the Unix domain socket.
     * Returns false if the file doesn't exist or the connect() is refused.
     */
    fun isDaemonAlive(socketPath: String): Boolean {
        if (!File(socketPath).exists()) return false
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.isConnected
            }
        } catch (e: Exception) {
            Log.d(TAG, "isDaemonAlive: connect failed: ${e.message}")
            false
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "Root check exitCode=$exitCode output=$output")
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    fun startRootDaemon(
        context: Context,
        stateDir: String,
        socketPath: String,
        logFilePath: String,
        socksAddr: String = "127.0.0.1:1053",
        httpAddr: String = "",
        controlProxy: String = "",
        taildropDir: String = "",
        tunMode: Boolean = true
    ): Boolean {
        return try {
            val tailscaledBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
            val dataDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            val logsDir = File(dataDir, "logs").apply { mkdirs() }.absolutePath
            val logFile = if (logFilePath.isNotEmpty()) logFilePath else "$logsDir/tailscaled.log"

            val socketFile = File(socketPath)
            socketFile.parentFile?.mkdirs()

            val sb = StringBuilder()
            sb.append("export TS_LOGS_DIR=\"$logsDir\"\n")
            sb.append("export TS_NO_LOGS_NO_SUPPORT=true\n")
            sb.append("export TS_AUTH_ONCE=true\n")
            sb.append("export TS_DNS_FALLBACK=\"1.1.1.1,8.8.8.8\"\n")

            if (taildropDir.isNotEmpty()) {
                sb.append("export TS_TAILDROP_DIR=\"$taildropDir\"\n")
            }

            if (controlProxy.isNotEmpty()) {
                val staticOverride = resolveProxyHostStatic(controlProxy)
                if (staticOverride.isNotEmpty()) {
                    sb.append("export TS_STATIC_HOSTS=\"$staticOverride\"\n")
                }
                if (controlProxy.startsWith("socks5://")) {
                    sb.append("export ALL_PROXY=\"$controlProxy\"\n")
                } else {
                    sb.append("export HTTP_PROXY=\"$controlProxy\"\n")
                    sb.append("export HTTPS_PROXY=\"$controlProxy\"\n")
                }
            }

            try {
                val envFile = File(dataDir, "files/control_proxy.env")
                envFile.parentFile?.mkdirs()
                envFile.writeText(sb.toString())
                Log.d(TAG, "Wrote control_proxy.env for service.d autostart")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write control_proxy.env: ${e.message}")
            }

            val cmd = mutableListOf<String>().apply {
                add(tailscaledBin)
                add("--statedir=$stateDir")
                add("--socket=$socketPath")
                if (socksAddr.isNotEmpty() && socksAddr != "none") {
                    add("--socks5-server=$socksAddr")
                }
                if (tunMode) {
                    add("--tun=tailscale0")
                } else {
                    add("--tun=userspace-networking")
                }
                if (httpAddr.isNotEmpty()) {
                    add("--outbound-http-proxy-listen=$httpAddr")
                }
            }.joinToString(" ")

            sb.append("nohup $cmd >> \"$logFile\" 2>&1 &\n")
            sb.append("chmod 666 \"$logFile\" 2>/dev/null || true\n")
            sb.append("magiskpolicy --live \"allow untrusted_app magisk unix_stream_socket connectto\" 2>/dev/null || supolicy --live \"allow untrusted_app magisk unix_stream_socket connectto\" 2>/dev/null || true\n")
            sb.append("for i in \$(seq 1 30); do\n")
            sb.append("    if [ -S \"$socketPath\" ] || [ -e \"$socketPath\" ]; then\n")
            sb.append("        chmod 777 \"$socketPath\"\n")
            sb.append("        chcon u:object_r:app_data_file:s0 \"$socketPath\" 2>/dev/null || true\n")
            sb.append("        chmod 777 \"$stateDir\" 2>/dev/null || true\n")
            sb.append("        break\n")
            sb.append("    fi\n")
            sb.append("    sleep 0.2\n")
            sb.append("done\n")

            val script = sb.toString()
            Log.d(TAG, "Executing root launch script:\n$script")

            val process = Runtime.getRuntime().exec("su")
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.write("\nexit\n")
                writer.flush()
            }
            val exitCode = process.waitFor()
            Log.d(TAG, "Root daemon launch result exitCode=$exitCode")

            // Verify socket creation
            var attempts = 0
            while (attempts < 15) {
                if (socketFile.exists()) {
                    Log.i(TAG, "Root daemon socket successfully created at $socketPath")
                    return true
                }
                Thread.sleep(200)
                attempts++
            }
            socketFile.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root daemon: ${e.message}", e)
            false
        }
    }

    fun stopRootDaemon(socketPath: String = ""): Boolean {
        return try {
            val script = """
                pkill -15 -f libtailscale.so || killall -15 tailscaled 2>/dev/null || true
                sleep 0.2
                pkill -9 -f libtailscale.so || killall -9 tailscaled 2>/dev/null || true
                if [ -n "$socketPath" ]; then
                    rm -f "$socketPath"
                fi
            """.trimIndent()

            val process = Runtime.getRuntime().exec("su")
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.write("\nexit\n")
                writer.flush()
            }
            val exitCode = process.waitFor()
            Log.d(TAG, "Root daemon stopped exitCode=$exitCode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop root daemon: ${e.message}", e)
            false
        }
    }

    fun setServiceScriptInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                val scriptContent = context.assets.open("scripts/tailscaled.sh").bufferedReader().use { it.readText() }

                val tempFile = File(context.cacheDir, "tailscaled.sh").apply {
                    writeText(scriptContent)
                }

                val cmd = """
                    mkdir -p "$SERVICE_D_DIR"
                    cp "${tempFile.absolutePath}" "$SERVICE_SCRIPT_PATH"
                    chmod 755 "$SERVICE_SCRIPT_PATH"
                    rm -f "${tempFile.absolutePath}"
                """.trimIndent()

                val process = Runtime.getRuntime().exec("su")
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(cmd)
                    writer.write("\nexit\n")
                    writer.flush()
                }
                val exitCode = process.waitFor()
                Log.d(TAG, "Installed service.d script exitCode=$exitCode")
                exitCode == 0
            } else {
                val cmd = "rm -f \"$SERVICE_SCRIPT_PATH\""
                val process = Runtime.getRuntime().exec("su")
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(cmd)
                    writer.write("\nexit\n")
                    writer.flush()
                }
                val exitCode = process.waitFor()
                Log.d(TAG, "Removed service.d script exitCode=$exitCode")
                exitCode == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage service.d script: ${e.message}", e)
            false
        }
    }

    fun isServiceScriptInstalled(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "[ -f \"$SERVICE_SCRIPT_PATH\" ] && echo 'exists'"))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            exitCode == 0 && output.contains("exists")
        } catch (e: Exception) {
            false
        }
    }

    const val CLI_SCRIPT_PATH = "/system/bin/tailscale"
    const val ALT_CLI_SCRIPT_PATH = "/data/adb/service.d/tailscale"
    const val MAGISK_MODULE_CLI_PATH = "/data/adb/modules/tailscaled/system/bin/tailscale"

    fun setTailscaleCliInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                val cliBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale_cli.so").absolutePath
                val socketPath = File(context.filesDir, "tailscaled.sock").absolutePath
                val pkgName = context.packageName

                val scriptContent = context.assets.open("scripts/tailscale_cli.sh").bufferedReader().use { it.readText() }
                    .replace("%PKG_NAME%", pkgName)
                    .replace("%CLI_BIN%", cliBin)

                val tempFile = File(context.cacheDir, "tailscale_cli_wrapper.sh").apply {
                    writeText(scriptContent)
                }

                val cmd = """
                    mkdir -p "$SERVICE_D_DIR"
                    cp "${tempFile.absolutePath}" "$ALT_CLI_SCRIPT_PATH"
                    chmod 755 "$ALT_CLI_SCRIPT_PATH"
                    
                    mkdir -p "/data/adb/modules/tailscaled/system/bin"
                    rm -f "/data/adb/modules/tailscaled/disable" "/data/adb/modules/tailscaled/remove"
                    printf 'id=tailscaled\nname=TailSocks CLI Integration\nversion=v1.0\nversionCode=100\nauthor=TailSocks\ndescription=Tailscale CLI binary overlay\n' > /data/adb/modules/tailscaled/module.prop
                    cp "${tempFile.absolutePath}" "$MAGISK_MODULE_CLI_PATH"
                    chmod 755 "$MAGISK_MODULE_CLI_PATH"
                    chcon u:object_r:system_file:s0 "$MAGISK_MODULE_CLI_PATH" 2>/dev/null || true
                    
                    mount -o remount,rw /product/bin 2>/dev/null && cp "${tempFile.absolutePath}" /product/bin/tailscale && chmod 755 /product/bin/tailscale && chcon u:object_r:system_file:s0 /product/bin/tailscale && mount -o remount,ro /product/bin 2>/dev/null || true
                    mount -o remount,rw /system 2>/dev/null || true
                    cp "${tempFile.absolutePath}" "$CLI_SCRIPT_PATH" 2>/dev/null && chmod 755 "$CLI_SCRIPT_PATH" || true
                    rm -f "${tempFile.absolutePath}"
                """.trimIndent()

                val process = Runtime.getRuntime().exec("su")
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(cmd)
                    writer.write("\nexit\n")
                    writer.flush()
                }
                val exitCode = process.waitFor()
                Log.d(TAG, "Installed CLI wrapper script exitCode=$exitCode")
                exitCode == 0
            } else {
                val cmd = """
                    rm -f "$CLI_SCRIPT_PATH" "$ALT_CLI_SCRIPT_PATH" "$MAGISK_MODULE_CLI_PATH"
                    mount -o remount,rw /product/bin 2>/dev/null && rm -f /product/bin/tailscale && mount -o remount,ro /product/bin 2>/dev/null || true
                """.trimIndent()
                val process = Runtime.getRuntime().exec("su")
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(cmd)
                    writer.write("\nexit\n")
                    writer.flush()
                }
                val exitCode = process.waitFor()
                Log.d(TAG, "Removed CLI wrapper script exitCode=$exitCode")
                exitCode == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage CLI wrapper script: ${e.message}", e)
            false
        }
    }

    fun isTailscaleCliInstalled(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "([ -f \"$CLI_SCRIPT_PATH\" ] || [ -f \"$ALT_CLI_SCRIPT_PATH\" ] || [ -f \"$MAGISK_MODULE_CLI_PATH\" ] || [ -f \"/product/bin/tailscale\" ]) && echo 'exists'"))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            exitCode == 0 && output.contains("exists")
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveProxyHostStatic(proxyUrl: String): String {
        if (proxyUrl.isBlank()) return ""
        return try {
            val uri = java.net.URI(proxyUrl)
            val host = uri.host ?: return ""
            if (host.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$")) || host.contains(":")) {
                return ""
            }
            var ip: String? = null
            try {
                val addrs = java.net.InetAddress.getAllByName(host)
                if (addrs.isNotEmpty()) {
                    ip = addrs[0].hostAddress
                }
            } catch (e: Exception) {
                Log.w(TAG, "System DNS failed to resolve '$host', trying direct UDP DNS to 1.1.1.1...")
            }
            if (ip.isNullOrBlank()) {
                ip = resolveHostViaUdpDns(host)
            }
            if (!ip.isNullOrBlank()) {
                val formattedIp = if (ip.contains(":")) "[$ip]" else ip
                val override = "$host=$formattedIp"
                Log.i(TAG, "Static DNS override resolved: $override")
                override
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve proxy host in Kotlin: ${e.message}")
            ""
        }
    }

    private fun resolveHostViaUdpDns(host: String): String? {
        return try {
            val socket = java.net.DatagramSocket()
            socket.soTimeout = 2000
            val dnsServer = java.net.InetAddress.getByName("1.1.1.1")

            val baos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(baos)
            dos.writeShort(0x1234)
            dos.writeShort(0x0100)
            dos.writeShort(0x0001)
            dos.writeShort(0x0000)
            dos.writeShort(0x0000)
            dos.writeShort(0x0000)

            for (part in host.split(".")) {
                val bytes = part.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)
            dos.writeShort(0x0001)
            dos.writeShort(0x0001)

            val query = baos.toByteArray()
            val packet = java.net.DatagramPacket(query, query.size, dnsServer, 53)
            socket.send(packet)

            val buf = ByteArray(512)
            val recvPacket = java.net.DatagramPacket(buf, buf.size)
            socket.receive(recvPacket)
            socket.close()

            val data = recvPacket.data
            val length = recvPacket.length
            if (length > 12) {
                val ancnt = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
                if (ancnt > 0) {
                    for (i in (length - 4) downTo 12) {
                        val b0 = data[i].toInt() and 0xff
                        val b1 = data[i + 1].toInt() and 0xff
                        val b2 = data[i + 2].toInt() and 0xff
                        val b3 = data[i + 3].toInt() and 0xff
                        if (b0 in 1..254 && b3 in 1..254 && b0 != 127) {
                            return "$b0.$b1.$b2.$b3"
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Direct UDP DNS resolution failed for '$host': ${e.message}")
            null
        }
    }
}
