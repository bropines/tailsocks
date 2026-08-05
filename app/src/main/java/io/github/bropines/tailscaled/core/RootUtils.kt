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
            if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 777 \"$socketPath\"")).waitFor()
                    LocalSocket().use { socket ->
                        socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                        return socket.isConnected
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "isDaemonAlive su chmod retry failed: ${ex.message}")
                }
            }
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

            if (taildropDir.isNotEmpty()) {
                sb.append("export TS_TAILDROP_DIR=\"$taildropDir\"\n")
            }

            if (controlProxy.isNotEmpty()) {
                if (controlProxy.startsWith("socks5://")) {
                    sb.append("export ALL_PROXY=\"$controlProxy\"\n")
                } else {
                    sb.append("export HTTP_PROXY=\"$controlProxy\"\n")
                    sb.append("export HTTPS_PROXY=\"$controlProxy\"\n")
                }
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

                val scriptContent = """
                    #!/system/bin/sh
                    # TailSocks Tailscale CLI Wrapper
                    PKG="$pkgName"
                    [ ! -d "/data/data/${'$'}PKG" ] && PKG="io.github.bropines.tailscaled"
                    [ ! -d "/data/data/${'$'}PKG" ] && PKG="io.github.bropines.tailscaled.dev"

                    CLI_BIN="$cliBin"
                    if [ ! -x "${'$'}CLI_BIN" ]; then
                        CLI_BIN="${'$'}(pm path ${'$'}PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/x86_64/libtailscale_cli.so|')"
                    fi
                    if [ ! -x "${'$'}CLI_BIN" ]; then
                        CLI_BIN="${'$'}(pm path ${'$'}PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/arm64/libtailscale_cli.so|')"
                    fi
                    if [ ! -x "${'$'}CLI_BIN" ]; then
                        CLI_BIN="${'$'}(pm path ${'$'}PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/arm/libtailscale_cli.so|')"
                    fi
                    if [ ! -x "${'$'}CLI_BIN" ]; then
                        CLI_BIN="${'$'}(pm path ${'$'}PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/x86/libtailscale_cli.so|')"
                    fi
                    if [ ! -x "${'$'}CLI_BIN" ]; then
                        CLI_BIN="${'$'}(find /data/app -name "libtailscale_cli.so" 2>/dev/null | head -n1)"
                    fi

                    SOCKET_PATH="/data/data/${'$'}PKG/files/tailscaled.sock"
                    
                    if [ ! -x "${'$'}CLI_BIN" ]; then
                        echo "TailSocks CLI binary not found"
                        exit 1
                    fi
                    
                    if echo "${'$'}@" | grep -q -- '--socket='; then
                        exec "${'$'}CLI_BIN" "${'$'}@"
                    else
                        exec "${'$'}CLI_BIN" --socket="${'$'}SOCKET_PATH" "${'$'}@"
                    fi
                """.trimIndent()

                val tempFile = File(context.cacheDir, "tailscale_cli_wrapper.sh").apply {
                    writeText(scriptContent)
                }

                val cmd = """
                    mkdir -p "$SERVICE_D_DIR"
                    cp "${tempFile.absolutePath}" "$ALT_CLI_SCRIPT_PATH"
                    chmod 755 "$ALT_CLI_SCRIPT_PATH"
                    
                    mkdir -p "/data/adb/modules/tailscaled/system/bin"
                    printf 'id=tailscaled\nname=TailSocks CLI Integration\nversion=v1.0\nversionCode=100\nauthor=TailSocks\ndescription=Tailscale CLI binary overlay\n' > /data/adb/modules/tailscaled/module.prop
                    cp "${tempFile.absolutePath}" "$MAGISK_MODULE_CLI_PATH"
                    chmod 755 "$MAGISK_MODULE_CLI_PATH"
                    
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
                val cmd = "rm -f \"$CLI_SCRIPT_PATH\" \"$ALT_CLI_SCRIPT_PATH\" \"$MAGISK_MODULE_CLI_PATH\""
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
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "([ -f \"$CLI_SCRIPT_PATH\" ] || [ -f \"$ALT_CLI_SCRIPT_PATH\" ] || [ -f \"$MAGISK_MODULE_CLI_PATH\" ]) && echo 'exists'"))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            exitCode == 0 && output.contains("exists")
        } catch (e: Exception) {
            false
        }
    }
}
