package io.github.bropines.tailscaled.core

import android.content.Context
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.SoMarkVerdict
import io.github.bropines.tailscaled.core.RootUtils.shQuote
import java.io.File

/**
 * The SO_MARK verdict: whether the running root daemon marks its own sockets
 * (patch 16), which decides whether the exit-node catch-all may be installed.
 * Read from this run's daemon log ([soMarkVerdict], through [SoMarkLogScan]),
 * and from the daemon's environment when the log is silent
 * ([daemonMarksByEnviron]). The run bounds [startRootDaemon] records live here
 * too.
 */
internal object SoMarkGate {
    /**
     * Log of the daemon launched by [startRootDaemon] and the file size just
     * before the launch: a run start [soMarkVerdict] can use even before the
     * daemon has written anything. Both stay unset when the app merely attached
     * to a daemon that was already running (boot script), in which case the
     * last [RUN_MARKER] line the script wrote bounds the current run instead.
     */
    @Volatile internal var lastDaemonLogFile: File? = null
    @Volatile internal var daemonLogStartOffset: Long = -1L

    /**
     * Whether the daemon currently running has shown that it marks its own
     * sockets with [BYPASS_MARK]: its log carries [SO_MARK_OK] for THIS run.
     *
     * The daemon appends to one file across runs, so only the current run's
     * part counts. The file is scanned backwards from its end, line by line:
     *  - the last daemon line containing [SO_MARK_LINE] (the probe logs exactly
     *    once per run, `set` or `unavailable`) is the candidate verdict — but it
     *    counts only once a run start is found BELOW it;
     *  - a run start is a [RUN_MARKER] or [DAEMON_START_LINE] line, or the byte
     *    offset [startRootDaemon] recorded when it is this log and lies inside
     *    the file. Meeting one before any probe line means this run has logged
     *    no probe line (yet) → false;
     *  - the start of the file, or [SCAN_LIMIT] bytes, without a run start →
     *    the run cannot be bounded (log rewritten underneath, unknown core) →
     *    false. Never "the last line anywhere in the file": an earlier run's
     *    line must not vouch for this one.
     * Lines tagged [APP_LINE_TAG] are the app's and the script's own and are
     * never evidence. The scan reads [SCAN_CHUNK] at a time, so a daemon that
     * has been running for days (adopted, "Terminate Root Daemon on Stop" off)
     * is judged correctly however large its log has grown.
     *
     * Anything unreadable or absent counts as "does not mark": without the mark
     * the pref-200 catch-all would route the daemon's own tunnel packets into
     * tailscale0, so the safe failure mode is "no exit node", never a loop.
     *
     * Read by [SoMarkLogScan]: after the first full scan of a run only the
     * bytes appended since are read.
     */
    internal fun soMarkVerdict(logFile: File): SoMarkVerdict =
        soMarkScan.verdict(logFile, if (lastDaemonLogFile == logFile) daemonLogStartOffset else -1L)

    /**
     * Second opinion for the socket-marking gate, taken from the running daemon
     * instead of from its log.
     *
     * The log is not a reliable witness: it is truncated, it outlives the app
     * across an update, and a daemon that has been up for a while has long since
     * scrolled its startup lines away — on a device checked tonight it held no
     * probe line at all, so the gate could never open and the exit node was
     * withheld for a reason that had nothing to do with routing.
     *
     * The binary is ours and marks its sockets unconditionally; the only way to
     * turn that off is TS_VPN_BYPASS=0, which we put in the daemon's environment
     * ourselves. So reading that environment answers the question directly.
     * Returns null when nothing can be read, which leaves the log's verdict
     * standing — the gate's failure mode is "no exit node", never a loop.
     */
    internal fun daemonMarksByEnviron(context: Context): SoMarkVerdict? {
        val libDir = context.applicationInfo?.nativeLibraryDir ?: return null
        val script = buildString {
            append("for p in \$(pidof libtailscale.so 2>/dev/null); do\n")
            // The binary is the CLI as well (ts_include_cli): a `tailscale`
            // command running through the root wrapper is the same file under the
            // same name. Only a process with a state directory is a daemon.
            append("    tr '\\0' '\\n' < /proc/\$p/cmdline 2>/dev/null | grep -q '^--statedir=' || continue\n")
            append("    exe=\$(readlink /proc/\$p/exe 2>/dev/null)\n")
            append("    case \"\$exe\" in ${shQuote(libDir)}*) echo SOMARK_ENV_OURS; break;; esac\n")
            append("done\n")
        }
        val res = runSu("somark-environ", script, timeoutMs = 8_000L)
        if (!res.ok) return null
        // Note what is NOT checked: TS_VPN_BYPASS. Patch 16's socketMark() returns
        // the bypass bit unconditionally and only adds Android's protect bit when
        // that setting is on, so a daemon started with TS_VPN_BYPASS=0 still skips
        // our table — which is the only thing the catch-all's mask asks about.
        // Reading that variable as "does not mark" withheld the exit node for a
        // reason that does not exist.
        return if (res.output.contains("SOMARK_ENV_OURS")) {
            SoMarkVerdict(true, "the running daemon is the binary we ship, which marks its own sockets")
        } else {
            null
        }
    }

    /** Remembers how far the daemon log was read, so an apply reads only what was appended since. */
    internal val soMarkScan = SoMarkLogScan()
}
