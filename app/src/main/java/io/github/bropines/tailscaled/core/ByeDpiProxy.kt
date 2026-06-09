package io.github.bropines.tailscaled.core

import android.util.Log
import java.net.ServerSocket

object ByeDpiProxy {
    private const val TAG = "ByeDpiProxy"
    private var isRunning = false

    init {
        try {
            System.loadLibrary("byedpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load libbyedpi.so", e)
        }
    }

    @JvmStatic
    private external fun jniStartProxy(args: Array<String>): Int
    @JvmStatic
    private external fun jniStopProxy(): Int
    @JvmStatic
    private external fun jniForceClose(): Int
    @JvmStatic
    private external fun jniSetLogPath(path: String?): Unit

    private var logReaderThread: Thread? = null
    @Volatile
    private var stopLogReader = false

    var activeAddress: Pair<String, Int>? = null
        private set

    fun start(customFlags: String, context: android.content.Context): Pair<String, Int>? {
        if (isRunning) return null
        
        // Генерация случайного localhost IP в подсети 127.0.0.0/8 (кроме 127.0.0.1 для безопасности)
        val ip = "127.${(2..254).random()}.${(2..254).random()}.${(2..254).random()}"
        
        // Генерация случайного свободного порта
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
        
        if (customFlags.isNotEmpty()) {
            baseArgs.addAll(customFlags.split("\\s+".toRegex()).filter { it.isNotEmpty() })
        } else {
            // Сенсорные дефолты: сплиттинг на 1 байте + disorder
            baseArgs.addAll(listOf("-s", "1", "-d", "split", "-r"))
        }

        val logFile = java.io.File(context.cacheDir, "byedpi_temp.log")
        try {
            jniSetLogPath(logFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set log path", e)
        }

        val address = Pair(ip, port)
        activeAddress = address

        Thread {
            isRunning = true
            Log.d(TAG, "Starting ByeDPI on $ip:$port with args: $baseArgs")
            appctr.Appctr.logAndroid("INFO", "CORE", "DPI Bypass (ByeDPI) starting on $ip:$port...")
            
            startLogReader(logFile)
            
            val code = jniStartProxy(baseArgs.toTypedArray())
            Log.d(TAG, "ByeDPI stopped with code $code")
            appctr.Appctr.logAndroid("INFO", "CORE", "DPI Bypass (ByeDPI) stopped with code $code")
            
            stopLogReader()
            activeAddress = null
            isRunning = false
        }.start()

        return address
    }

    fun stop() {
        if (!isRunning) return
        activeAddress = null
        stopLogReader()
        Thread {
            jniStopProxy()
            jniForceClose()
        }.start()
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
