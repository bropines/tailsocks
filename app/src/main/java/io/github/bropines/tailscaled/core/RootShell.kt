package io.github.bropines.tailscaled.core

import android.util.Log
import io.github.bropines.tailscaled.core.RootUtils.SuResult
import io.github.bropines.tailscaled.core.RootUtils.TAG
import java.util.concurrent.TimeUnit

/**
 * The root shell every Root Mode operation goes through: [runSu] feeds a
 * script to `su`, drains both pipes, enforces the timeout and logs the
 * outcome, and [rootLog] writes a line to logcat and to the in-app ROOT tab.
 * Nothing here knows about routing or the daemon.
 */
internal object RootShell {
    internal fun rootLog(level: String, message: String) {
        // Both, deliberately. The in-app ROOT tab is what a user reads; logcat is
        // the only copy reachable while debugging on a device, and the routing
        // decisions are exactly what one needs to see there.
        Log.i(TAG, "[$level] $message")
        try {
            appctr.Appctr.logAndroid(level, "ROOT", message)
        } catch (e: Exception) {
            // The Go bridge may not be loaded yet; the logcat line above stands.
        }
    }

    /**
     * Runs a script in a root shell and returns its exit code and combined output.
     *
     * Both pipes are drained on separate threads: a script that prints more than
     * the pipe buffer (an `iptables -S` dump, for instance) would otherwise block
     * forever, and the shell is killed if `su` never returns — an ungranted root
     * prompt must not hang the caller.
     *
     * [quiet] keeps a successful run's output out of the ROOT log: a routine read
     * whose answer the caller interprets would otherwise fill the tab with dumps.
     * Failures are logged either way.
     */
    internal fun runSu(tag: String, script: String, timeoutMs: Long = 20_000L, quiet: Boolean = false): SuResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val output = StringBuilder()

            val drain = { stream: java.io.InputStream ->
                Thread {
                    try {
                        stream.bufferedReader().forEachLine { line ->
                            synchronized(output) { output.append(line).append('\n') }
                        }
                    } catch (e: Exception) {
                        // Stream closed together with the process.
                    }
                }.apply { isDaemon = true; start() }
            }
            val outThread = drain(process.inputStream)
            val errThread = drain(process.errorStream)

            try {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(script)
                    writer.write("\nexit\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$tag] failed to feed script to su: ${e.message}")
            }

            val waiter = Thread {
                try { process.waitFor() } catch (e: InterruptedException) { /* timed out */ }
            }.apply { isDaemon = true; start() }
            waiter.join(timeoutMs)

            val exitCode = if (waiter.isAlive) {
                Log.e(TAG, "[$tag] root shell timed out after ${timeoutMs}ms, killing it")
                rootLog("ERROR", "$tag: root shell timed out after ${timeoutMs}ms")
                process.destroy()
                -1
            } else {
                try { process.exitValue() } catch (e: IllegalThreadStateException) { -1 }
            }

            outThread.join(TimeUnit.SECONDS.toMillis(2))
            errThread.join(TimeUnit.SECONDS.toMillis(2))

            val text = synchronized(output) { output.toString().trim() }
            if (exitCode != 0 && tag.endsWith("-check")) {
                // Probes ([ -f script ] && echo exists) report "not installed" as a
                // non-zero exit; that is an answer, not a failure, and logging it as
                // ERROR made the ROOT tab look broken on every settings visit.
                Log.d(TAG, "[$tag] exit=$exitCode (not present)")
            } else if (exitCode != 0) {
                Log.e(TAG, "[$tag] exit=$exitCode output=$text")
                rootLog("ERROR", "$tag failed (exit $exitCode)${if (text.isEmpty()) "" else ": $text"}")
            } else {
                Log.d(TAG, "[$tag] exit=0 output=$text")
                if (text.isNotEmpty() && !quiet) rootLog("INFO", "$tag: $text")
            }
            SuResult(exitCode, text)
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] root shell failed: ${e.message}", e)
            rootLog("ERROR", "$tag: root shell unavailable (${e.message})")
            SuResult(-1, e.message ?: "")
        }
    }
}
