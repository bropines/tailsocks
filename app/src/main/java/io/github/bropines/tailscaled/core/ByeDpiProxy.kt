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

    fun start(customFlags: String): Pair<String, Int>? {
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

        Thread {
            isRunning = true
            Log.d(TAG, "Starting ByeDPI on $ip:$port with args: $baseArgs")
            val code = jniStartProxy(baseArgs.toTypedArray())
            Log.d(TAG, "ByeDPI stopped with code $code")
            isRunning = false
        }.start()

        return Pair(ip, port)
    }

    fun stop() {
        if (!isRunning) return
        Thread {
            jniStopProxy()
            jniForceClose()
        }.start()
    }
}
