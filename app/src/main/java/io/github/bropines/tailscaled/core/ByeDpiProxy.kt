package io.github.bropines.tailscaled.core

import android.util.Log
import java.net.ServerSocket

object ByeDpiProxy {
    private const val TAG = "ByeDpiProxy"
    @Volatile
    private var isRunning = false
    private var proxyThread: Thread? = null

    /** Names the latest run to the native side, which tells a stop meant for
     *  this run from one meant for another. Only grows; guarded by the monitor. */
    private var runId = 0L

    init {
        try {
            System.loadLibrary("byedpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load libbyedpi.so", e)
        }
    }

    @JvmStatic
    private external fun jniStartProxy(args: Array<String>, runId: Long): Int
    @JvmStatic
    private external fun jniStopProxy(runId: Long): Int
    @JvmStatic
    private external fun jniSetLogPath(path: String?): Unit

    private var logReaderThread: Thread? = null
    @Volatile
    private var stopLogReader = false

    /** Written under the monitor in start()/stop() and from the proxy thread when
     *  it exits, but read without it from Compose composition on main
     *  (GlobalSettings.getControlProxyUrl, SettingsActivity). */
    @Volatile
    var activeAddress: Pair<String, Int>? = null
        private set

    @Synchronized
    fun start(customFlags: String, context: android.content.Context): Pair<String, Int>? {
        if (isRunning) return null
        isRunning = true
        val id = ++runId
        // A previous run may still be leaving its loop, and the native core
        // keeps its state in globals: the new run waits for that thread, on its
        // own thread, instead of overlapping it. The address is known already.
        val previous = proxyThread

        // Generate random localhost IP in 127.0.0.0/8 subnet (excluding 127.0.0.1 for security)
        val ip = "127.${(2..254).random()}.${(2..254).random()}.${(2..254).random()}"
        
        // Generate random available port
        val port = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            (30000..65000).random()
        }

        val baseArgs = mutableListOf(
            "byedpi",
            "-i", "socks5://$ip",
            "-p", port.toString()
        )

        if (GlobalSettings.isCPByeDpiIpv6Disabled(context)) {
            baseArgs.add("-X")
        }
        
        // Sensible defaults: 1-byte splitting + disorder
        val defaultArgs = listOf("-s", "1", "-d", "split", "-r")
        if (customFlags.isNotEmpty()) {
            // Only desync/tuning options may come from the user string; anything
            // that binds, forks, or touches files is dropped (see ByeDpiFlags).
            val checked = ByeDpiFlags.sanitize(customFlags)
            if (checked.rejected.isNotEmpty()) {
                Log.w(TAG, "Ignoring ByeDPI flags outside the DPI allow-list: ${checked.rejected}")
                appctr.Appctr.logAndroid(
                    "WARN", "CORE",
                    "DPI Bypass: ignored flags ${checked.rejected.joinToString(" ")} — only desync/tuning options are accepted"
                )
            }
            if (checked.accepted.isNotEmpty()) baseArgs.addAll(checked.accepted) else baseArgs.addAll(defaultArgs)
        } else {
            baseArgs.addAll(defaultArgs)
        }

        val logFile = java.io.File(context.cacheDir, "byedpi_temp.log")
        val address = Pair(ip, port)
        activeAddress = address

        val t = Thread {
            if (previous != null && previous.isAlive) {
                previous.join(2000)
                if (previous.isAlive) {
                    appctr.Appctr.logAndroid("WARN", "CORE", "DPI Bypass (ByeDPI): the previous instance is still stopping, waiting for it")
                    previous.join()
                }
            }
            // The log file belongs to one run at a time: the previous one wrote
            // to it until it returned.
            try {
                jniSetLogPath(logFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set log path", e)
            }
            Log.d(TAG, "Starting ByeDPI on $ip:$port with args: $baseArgs")
            appctr.Appctr.logAndroid("INFO", "CORE", "DPI Bypass (ByeDPI) starting on $ip:$port...")
            
            startLogReader(logFile)
            
            // Returns at once if stop() got in first.
            val code = jniStartProxy(baseArgs.toTypedArray(), id)
            Log.d(TAG, "ByeDPI stopped with code $code")
            appctr.Appctr.logAndroid("INFO", "CORE", "DPI Bypass (ByeDPI) stopped with code $code")
            
            stopLogReader()
            synchronized(this@ByeDpiProxy) {
                // A later run has its own state by now; it is not ours to clear.
                if (runId == id) {
                    activeAddress = null
                    isRunning = false
                }
            }
        }
        proxyThread = t
        t.start()

        return address
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        // Clear the flag synchronously so a start() right after stop() is not
        // rejected while the native side is still winding down.
        isRunning = false
        activeAddress = null
        stopLogReader()
        // A request and nothing more: the loop notices it, closes its own
        // sockets and returns on its own thread. The call does not block, and
        // the request holds for a run that has not reached the native side yet.
        jniStopProxy(runId)
    }

    private fun startLogReader(file: java.io.File) {
        stopLogReader = false
        logReaderThread = Thread {
            try {
                var pos = 0L
                while (!stopLogReader) {
                    if (file.exists()) {
                        val len = file.length()
                        if (len > pos) {
                            java.io.RandomAccessFile(file, "r").use { raf ->
                                raf.seek(pos)
                                var line = raf.readLine()
                                while (line != null) {
                                    val bytes = line.toByteArray(Charsets.ISO_8859_1)
                                    val trimmed = String(bytes, Charsets.UTF_8).trim()
                                    if (trimmed.isNotEmpty()) {
                                        val category = if (trimmed.contains("error", ignoreCase = true) || trimmed.contains("fail", ignoreCase = true)) {
                                            "ERROR"
                                        } else {
                                            "OTHER"
                                        }
                                        appctr.Appctr.logAndroid("INFO", category, "[ByeDPI] $trimmed")
                                    }
                                    line = raf.readLine()
                                }
                                pos = raf.filePointer
                            }
                        } else if (len < pos) {
                            pos = 0L
                        }
                    }
                    Thread.sleep(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Log reader error", e)
            }
        }.apply { start() }
    }

    private fun stopLogReader() {
        stopLogReader = true
        logReaderThread?.interrupt()
        logReaderThread = null
    }
}
