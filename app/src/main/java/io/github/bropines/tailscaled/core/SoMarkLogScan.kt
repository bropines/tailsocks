package io.github.bropines.tailscaled.core

import java.io.File
import java.io.RandomAccessFile

/**
 * The daemon-log witness of the SO_MARK gate (docs/ROOT.md §4, T2): has the
 * root daemon's *current run* logged that it marks its own sockets?
 *
 * The rules are [RootUtils.soMarkVerdict]'s and unchanged: the file is read
 * backwards from its end; the last daemon line containing
 * [RootUtils.SO_MARK_LINE] is the candidate verdict and counts only once a run
 * start ([RootUtils.RUN_MARKER], [RootUtils.DAEMON_START_LINE], or the launch
 * offset the app recorded) lies below it; meeting a run start first means this
 * run has logged no probe line (yet); no run start at all, within the file or
 * within [limit] bytes, means the run cannot be bounded. Lines tagged
 * [RootUtils.APP_LINE_TAG] never count. Every negative here is "does not mark";
 * the caller decides what silence falls back to.
 *
 * What is new is that the answer is remembered. The probe line is logged once,
 * at the start of a run, so every apply used to read everything the daemon had
 * written since it started — on an adopted daemon that has been up for a week,
 * tens of MiB per apply. The verdict of a run cannot change while the file only
 * grows, except by what is appended: a new run start, or (for a run that has
 * not logged its probe yet) the probe line itself. So after one full scan only
 * the bytes appended since are read, starting at the line that was last when
 * the previous call looked (it may have been half written then). A file that
 * shrank, or whose bytes before the remembered end changed — truncated,
 * trimmed, rewritten — is scanned in full again, as is any other file or
 * launch offset.
 *
 * Thread-safe: the routing apply and the Check Routing dump both ask.
 */
internal class SoMarkLogScan(
    private val chunk: Int = RootUtils.SCAN_CHUNK,
    private val limit: Long = RootUtils.SCAN_LIMIT,
) {
    /** What one backwards pass over `[floor, end)` found. */
    private class Pass(val runStart: Boolean, val probe: RootUtils.SoMarkVerdict?, val limitHit: Boolean)

    private class Memo(
        val path: String,
        val recorded: Long,
        val scannedTo: Long,
        /** Offset of the line that was last at [scannedTo]: the next pass starts here. */
        val lastLineStart: Long,
        /** The bytes just before [scannedTo], to notice a file rewritten underneath. */
        val tail: ByteArray,
        /** A run start (or the recorded launch offset) lies at or below [lastLineStart]. */
        val bounded: Boolean,
        val verdict: RootUtils.SoMarkVerdict,
    )

    private var memo: Memo? = null

    /** Bytes the last [verdict] call read from the file; for the tests. */
    @Volatile var lastBytesRead = 0L
        private set

    /** Forgets the remembered answer; a new run was just launched. */
    @Synchronized fun reset() {
        memo = null
    }

    /**
     * The verdict for [logFile]. [recorded] is the byte offset the app recorded
     * when it launched the daemon writing this file, or -1 when it did not.
     */
    @Synchronized fun verdict(logFile: File, recorded: Long): RootUtils.SoMarkVerdict {
        lastBytesRead = 0L
        return try {
            RandomAccessFile(logFile, "r").use { raf -> scan(raf, logFile.path, recorded) }
        } catch (e: Exception) {
            memo = null
            RootUtils.SoMarkVerdict(false, "cannot read ${logFile.path} (${e.message})")
        }
    }

    private fun scan(raf: RandomAccessFile, path: String, recorded: Long): RootUtils.SoMarkVerdict {
        val len = raf.length()
        val noProbe = RootUtils.SoMarkVerdict(false, "no '${RootUtils.SO_MARK_LINE}' line since this run's start in $path")
        val limitVerdict = RootUtils.SoMarkVerdict(
            false, "no run start within the last ${limit shr 20} MiB of $path; run unverified"
        )
        val m = memo
        val verdict: RootUtils.SoMarkVerdict
        val bounded: Boolean
        if (m != null && m.path == path && m.recorded == recorded && len >= m.scannedTo && sameTail(raf, m)) {
            if (len == m.scannedTo) return m.verdict
            val p = pass(raf, m.lastLineStart, len)
            verdict = when {
                p.limitHit -> limitVerdict
                p.runStart -> p.probe ?: noProbe
                // A probe line appended to a run whose start is already known.
                p.probe != null && m.bounded -> p.probe
                // Nothing appended changes the answer: no run start, and either no
                // probe line or one that has no run start below it to count against.
                else -> m.verdict
            }
            bounded = !p.limitHit && (p.runStart || m.bounded)
        } else {
            val launched = recorded in 0..len
            val floor = if (launched) recorded else 0L
            val p = pass(raf, floor, len)
            verdict = when {
                p.limitHit -> limitVerdict
                p.runStart -> p.probe ?: noProbe
                // The recorded launch offset is a run start by construction.
                launched -> p.probe ?: RootUtils.SoMarkVerdict(
                    false, "no '${RootUtils.SO_MARK_LINE}' line yet for the run started at byte $recorded of $path"
                )
                else -> RootUtils.SoMarkVerdict(
                    false,
                    "no run start ('${RootUtils.RUN_MARKER}' or '${RootUtils.DAEMON_START_LINE}') in $path; run unverified"
                )
            }
            bounded = !p.limitHit && (p.runStart || launched)
        }
        memo = remember(raf, path, recorded, len, bounded, verdict)
        return verdict
    }

    /**
     * Reads `[floor, end)` backwards, line by line, until a run start, the
     * floor, or [limit] bytes from the end of the file. [floor] must be a line
     * start (the start of the file, the recorded launch offset, or the start of
     * a line remembered from an earlier call).
     */
    private fun pass(raf: RandomAccessFile, floor: Long, end: Long): Pass {
        val buf = ByteArray(chunk)
        // Leading partial line of the chunk read before this one (later in the file).
        var carry = ""
        var pos = end
        // Verdict of the last probe line met, pending a run start below it.
        var probe: RootUtils.SoMarkVerdict? = null
        while (pos > floor) {
            if (end - pos >= limit) return Pass(runStart = false, probe = probe, limitHit = true)
            val start = maxOf(floor, pos - chunk)
            val n = (pos - start).toInt()
            raf.seek(start)
            raf.readFully(buf, 0, n)
            lastBytesRead += n
            // ISO-8859-1 decodes bytes 1:1, so no multi-byte sequence can straddle a chunk boundary.
            val lines = (String(buf, 0, n, Charsets.ISO_8859_1) + carry).split('\n')
            // Unless this chunk begins at the floor (a line start by
            // construction), its first segment continues a line from the
            // chunk below and is judged together with that one.
            val first = if (start > floor) 1 else 0
            carry = if (start > floor) lines[0] else ""
            for (i in lines.indices.reversed()) {
                if (i < first) break
                val line = lines[i]
                if (line.contains(RootUtils.RUN_MARKER) || line.contains(RootUtils.DAEMON_START_LINE)) {
                    return Pass(runStart = true, probe = probe, limitHit = false)
                }
                if (probe != null || line.contains(RootUtils.APP_LINE_TAG) || !line.contains(RootUtils.SO_MARK_LINE)) continue
                probe = if (line.contains(RootUtils.SO_MARK_OK)) {
                    RootUtils.SoMarkVerdict(true, "daemon logged '${RootUtils.SO_MARK_OK}'")
                } else {
                    RootUtils.SoMarkVerdict(false, "daemon logged: ${line.trim()}")
                }
            }
            pos = start
        }
        return Pass(runStart = false, probe = probe, limitHit = false)
    }

    /** The memo for a scan that ended at [len], or null when the last line cannot be found cheaply. */
    private fun remember(
        raf: RandomAccessFile, path: String, recorded: Long, len: Long, bounded: Boolean, verdict: RootUtils.SoMarkVerdict
    ): Memo? {
        // Usually the last line is short: look back a little first, further only if needed.
        var look = 0
        var bytes = ByteArray(0)
        var nl = -1
        for (window in TAIL_LOOKS) {
            look = minOf(len, window.toLong()).toInt()
            bytes = ByteArray(look)
            raf.seek(len - look)
            raf.readFully(bytes)
            lastBytesRead += look
            nl = bytes.lastIndexOf('\n'.code.toByte())
            if (nl >= 0 || look.toLong() == len) break
        }
        val lineStart = when {
            nl >= 0 -> len - look + nl + 1
            look.toLong() == len -> 0L
            // A last line longer than the look-back: not worth chasing, scan in full next time.
            else -> return null
        }
        // Never before the recorded launch offset: what lies below it is an
        // earlier run's, and a probe line there must not be read as this run's.
        val lastLineStart = if (recorded in 0..len) maxOf(lineStart, recorded) else lineStart
        val tail = bytes.copyOfRange(maxOf(0, look - PRINT), look)
        return Memo(path, recorded, len, lastLineStart, tail, bounded, verdict)
    }

    private fun sameTail(raf: RandomAccessFile, m: Memo): Boolean {
        val n = m.tail.size
        if (n == 0) return true
        val now = ByteArray(n)
        raf.seek(m.scannedTo - n)
        raf.readFully(now)
        lastBytesRead += n
        return now.contentEquals(m.tail)
    }

    private companion object {
        /** How far back from the end the last line start is looked for, in widening steps. */
        val TAIL_LOOKS = intArrayOf(512, 64 * 1024)
        /** Bytes compared to tell an appended file from a rewritten one. */
        const val PRINT = 256
    }
}
