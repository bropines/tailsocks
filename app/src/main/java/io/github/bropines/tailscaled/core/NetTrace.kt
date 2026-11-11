package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root Mode's network trace, dev builds only: what the kernel, netd and the
 * platform's network validation looked like around every default-network
 * change, written to logs/nettrace.log and appended to Save in the Logs screen.
 *
 * Built for the case a user's log cannot explain — mobile data toggled off and
 * on, and the device stays offline until Root Mode's DNS redirect is turned
 * off. Each snapshot holds the policy rules, the default routes, the redirect
 * chain with its packet counters, the platform's view of every network, its
 * validation probes, the system log since the previous snapshot, and the app's
 * own view of the MagicDNS gate. A change is followed up a few times, so the
 * trace shows how the state settled, not only the first second of it.
 *
 * Costs nothing in release builds: [enabled] is a compile-time false there.
 */
object NetTrace {
    private const val TAG = "NetTrace"
    private const val MAX_BYTES = 3L * 1024 * 1024

    /** Seconds after an event at which it is looked at again. */
    private val FOLLOW_UPS = longArrayOf(5, 20, 60)

    /** The quiet-state snapshot, while Root Mode runs and nothing happens. */
    private const val HEARTBEAT_MS = 5 * 60_000L

    val enabled: Boolean get() = BuildConfig.IS_DEV

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nettrace").apply { isDaemon = true }
    }

    /** The service's view of the gate and the network, one line per snapshot. */
    @Volatile var stateProvider: (() -> String)? = null

    @Volatile private var lastSnapshotAt = 0L
    @Volatile private var lastLogcatAt: Long? = null

    fun file(context: Context): File {
        val dataDir = context.filesDir.parentFile ?: context.filesDir
        return File(dataDir, "logs/nettrace.log")
    }

    private fun previous(context: Context) = File(file(context).path + ".1")

    /** Both halves of the trace, oldest first, for the exported log. */
    fun text(context: Context): String =
        listOf(previous(context), file(context)).filter { it.exists() }
            .joinToString("") { runCatching { it.readText() }.getOrDefault("") }

    /** A one-line note, without a snapshot: cheap, safe from any thread. */
    fun note(context: Context, line: String) {
        if (!enabled) return
        val app = context.applicationContext
        executor.execute { append(app, "${stamp()} ${line}\n") }
    }

    /** A network event: a snapshot now and the follow-ups. Root Mode only. */
    fun event(context: Context, reason: String) {
        if (!enabled || !GlobalSettings.isRootModeEnabled(context)) return
        val app = context.applicationContext
        executor.execute { snapshot(app, reason) }
        for (s in FOLLOW_UPS) executor.schedule({ snapshot(app, "$reason, +${s}s") }, s, TimeUnit.SECONDS)
    }

    /** From the refresh tick: a snapshot when none was taken for a while. */
    fun heartbeat(context: Context) {
        if (!enabled || !GlobalSettings.isRootModeEnabled(context)) return
        if (System.currentTimeMillis() - lastSnapshotAt < HEARTBEAT_MS) return
        lastSnapshotAt = System.currentTimeMillis()
        val app = context.applicationContext
        executor.execute { snapshot(app, "heartbeat") }
    }

    private fun snapshot(context: Context, reason: String) {
        try {
            val now = System.currentTimeMillis()
            lastSnapshotAt = now
            val since = lastLogcatAt
            lastLogcatAt = now
            val state = runCatching { stateProvider?.invoke() }.getOrNull() ?: "(service state unavailable)"
            val shell = RootUtils.netTraceShell(script(since))
            append(context, buildString {
                append("\n======== ").append(stamp(now)).append(" — ").append(reason).append(" ========\n")
                append("# app: ").append(state).append('\n')
                append(shell.trimEnd()).append('\n')
            })
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot failed: ${e.message}")
        }
    }

    /**
     * The root shell's half. Every command is allowed to fail on its own — a
     * missing table or a vendor without network_stack must not lose the rest —
     * and the script ends in `true` so the ROOT tab never logs it as an error.
     */
    private fun script(logcatSince: Long?): String {
        val logcatWindow = if (logcatSince == null) "-t 1500"
        else "-T '${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(logcatSince))}'"
        return """
            echo '# ip rule'; ip rule 2>&1
            echo '# ip route get 1.1.1.1 (no mark / tailscale bypass mark)'; ip route get 1.1.1.1 2>&1; ip route get 1.1.1.1 mark 0x80000 2>&1
            echo '# default routes'; ip route show table all 2>&1 | grep -E '^(default|unreachable default)'
            echo '# table 52'; ip route show table 52 2>&1 | head -20
            echo '# table 53'; ip route show table 53 2>&1 | head -20
            echo '# links up'; ip -br addr 2>&1 | grep -v ' DOWN '
            echo '# nat OUTPUT'; iptables -t nat -S OUTPUT 2>&1
            echo '# TAILSOCKS_DNS (packet counters)'; iptables -t nat -L TAILSOCKS_DNS -nvx 2>&1
            echo '# system DNS from root (ping resolves through netd, 3 s cap)'; timeout 3 ping -c1 -W1 connectivitycheck.gstatic.com 2>&1 | head -2
            echo '# connectivity'; dumpsys connectivity 2>/dev/null | grep -E 'Active default network|^  NetworkAgentInfo' | sed -E 's/TcpBufferSizes: [^ ]+ //'
            echo '# validation (network_stack)'; dumpsys network_stack 2>/dev/null | grep -E 'PROBE_|alidat|PRIVATE_DNS|NetworkMonitor' | head -60 | cut -c1-240
            echo '# daemon'; pgrep -af tailscaled 2>&1 | cut -c1-300
            echo '# system log'; logcat -d -b main,system,crash -v time $logcatWindow 2>/dev/null | grep -E 'NetworkMonitor|ConnectivityService|DnsResolver|resolv|netd|NetworkStack|TailSocks|Tailscaled|TailscaledService|RootUtils' | tail -250
            true
        """.trimIndent()
    }

    private fun append(context: Context, text: String) {
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) {
                val old = previous(context)
                old.delete()
                f.renameTo(old)
            }
            f.appendText(text)
        } catch (e: Exception) {
            Log.w(TAG, "Trace not written: ${e.message}")
        }
    }

    private fun stamp(at: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(at))
}
