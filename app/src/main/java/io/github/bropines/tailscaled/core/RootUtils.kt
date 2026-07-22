package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import java.io.File

object RootUtils {
    private const val TAG = "RootUtils"
    const val SERVICE_D_DIR = "/data/adb/service.d"
    const val SERVICE_SCRIPT_PATH = "$SERVICE_D_DIR/tailscaled.sh"

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
        taildropDir: String = ""
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
                add("--socks5-server=$socksAddr")
                if (httpAddr.isNotEmpty()) {
                    add("--outbound-http-proxy-listen=$httpAddr")
                }
            }.joinToString(" ")

            sb.append("nohup $cmd >> \"$logFile\" 2>&1 &\n")
            sb.append("sleep 1\n")
            sb.append("chmod 777 \"$socketPath\"\n")
            sb.append("chcon u:object_r:app_data_file:s0 \"$socketPath\" 2>/dev/null || true\n")

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
            while (attempts < 10) {
                if (socketFile.exists()) {
                    Log.i(TAG, "Root daemon socket successfully created at $socketPath")
                    return true
                }
                Thread.sleep(300)
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
                pkill -9 -f tailscaled || killall -9 tailscaled 2>/dev/null || true
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
                val tailscaledBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
                val stateDir = File(context.filesDir, "states/root").apply { mkdirs() }.absolutePath
                val socketPath = File(context.filesDir, "tailscaled.sock").absolutePath
                val logsDir = File(context.filesDir.parentFile ?: context.filesDir, "logs").apply { mkdirs() }.absolutePath

                val scriptContent = """
                    #!/system/bin/sh
                    # TailSocks Root Autostart Service
                    export TS_LOGS_DIR="$logsDir"
                    export TS_NO_LOGS_NO_SUPPORT=true
                    export TS_AUTH_ONCE=true
                    
                    nohup $tailscaledBin --statedir="$stateDir" --socket="$socketPath" --socks5-server=127.0.0.1:1053 >> "$logsDir/tailscaled.log" 2>&1 &
                    sleep 2
                    chmod 777 "$socketPath"
                    chcon u:object_r:app_data_file:s0 "$socketPath" 2>/dev/null || true
                """.trimIndent()

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
}
