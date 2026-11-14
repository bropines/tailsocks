package io.github.bropines.tailscaled.core

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.core.RootDnsGate.localApiGet
import io.github.bropines.tailscaled.core.RootRoutingScript.routingCleanupScript
import io.github.bropines.tailscaled.core.RootSelinux.injectSocketConnectRule
import io.github.bropines.tailscaled.core.RootShell.rootLog
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.LOG_TRIM_BYTES
import io.github.bropines.tailscaled.core.RootUtils.RUN_MARKER
import io.github.bropines.tailscaled.core.RootUtils.RootDaemonSpec
import io.github.bropines.tailscaled.core.RootUtils.TAG
import io.github.bropines.tailscaled.core.RootUtils.logTrimScript
import io.github.bropines.tailscaled.core.RootUtils.noteRoutingRemoved
import io.github.bropines.tailscaled.core.RootUtils.paths
import io.github.bropines.tailscaled.core.RootUtils.shQuote
import io.github.bropines.tailscaled.core.SoMarkGate.daemonLogStartOffset
import io.github.bropines.tailscaled.core.SoMarkGate.lastDaemonLogFile
import io.github.bropines.tailscaled.core.SoMarkGate.soMarkScan
import java.io.File

/**
 * The root daemon's lifecycle: probing a daemon that may be adopted
 * ([probeDaemon], [awaitRootDaemon]), waking it on a network change
 * ([wakeRootDaemon]), the env file both launch paths share ([writeRootEnvFile],
 * with the control proxy's host resolved to an address), the guarded launch
 * ([startRootDaemon], [launchGuardScript]) and the stop with its hand-back of
 * the state directory to the app ([stopRootDaemon], [handStateBackToApp]).
 * [RootUtils.RootDaemonSpec] stays on the façade.
 */
internal object RootDaemon {
    /**
     * Checks if the root daemon is actually alive by attempting a real
     * LocalSocket connect() to the Unix domain socket.
     * Returns false if the file doesn't exist or the connect() is refused.
     */
    fun isDaemonAlive(socketPath: String): Boolean = probeSocket(socketPath) == null

    /**
     * Connects to the daemon socket once. Returns null on success, otherwise the
     * reason — [allowSocketConnect] needs to tell "the daemon is not there" from
     * "SELinux refused the connect", which look identical to [isDaemonAlive].
     */
    internal fun probeSocket(socketPath: String): Exception? {
        if (!File(socketPath).exists()) return java.io.FileNotFoundException("no socket at $socketPath")
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                if (socket.isConnected) null else java.io.IOException("connect returned an unconnected socket")
            }
        } catch (e: Exception) {
            Log.d(TAG, "probeSocket: connect failed: ${e.message}")
            e
        }
    }

    /**
     * One look at the daemon a start may adopt: a connect, then the status with a
     * read timeout. LocalAPI holds a request until the daemon's backend exists,
     * so without the timeout a daemon on its way up parked the start thread for
     * as long as it took — and one that never got there, for good.
     */
    fun probeDaemon(socketPath: String): RootAdoption.Probe {
        val error = probeSocket(socketPath)
        // Denied by policy means it is there; allowSocketConnect, run before the
        // first probe, is what repairs that, and the status read below fails the
        // same way until it has.
        if (error != null && !isPolicyDenial(error)) return RootAdoption.Probe.ABSENT
        // Only BackendState is read, so the peers are not asked for: the default answer carries the whole netmap.
        return RootAdoption.classify(localApiGet(socketPath, "/localapi/v0/status?peers=false", RootAdoption.PROBE_TIMEOUT_MS))
    }

    /**
     * Whether a daemon process for this socket exists, as root sees it. Matched by
     * its `--socket=` argument like [stopRootDaemon]; the app cannot see a root
     * process in /proc. False when the root shell itself fails — a start then
     * goes ahead as it always did.
     */
    fun isDaemonProcessAlive(socketPath: String): Boolean {
        val pat = shQuote("--socket=$socketPath")
        val res = runSu("daemon-alive-check", "pgrep -f -- $pat >/dev/null 2>&1 && echo alive\n", timeoutMs = 10_000L, quiet = true)
        return res.ok && res.output.contains("alive")
    }

    /**
     * Tells the root daemon that the network moved: SIGUSR1 makes it re-read
     * the default route from TS_NETROUTE_FILE and wake its network monitor
     * (patch 06), as the app's own child daemon is woken from the bridge. The
     * app cannot signal a root process itself. Matched by its `--socket=`
     * argument and, like [daemonMarksByEnviron], only a process with a state
     * directory: the CLI is the same binary.
     */
    fun wakeRootDaemon(socketPath: String): Boolean {
        val pat = shQuote("--socket=$socketPath")
        val script = buildString {
            append("for p in \$(pgrep -f -- $pat 2>/dev/null); do\n")
            append("    tr '\\0' '\\n' < /proc/\$p/cmdline 2>/dev/null | grep -q '^--statedir=' || continue\n")
            append("    kill -USR1 \$p 2>/dev/null && echo woken\n")
            append("done\n")
        }
        val res = runSu("daemon-wake", script, timeoutMs = 8_000L, quiet = true)
        return res.ok && res.output.contains("woken")
    }

    /**
     * Waits for a daemon that exists to answer, within [RootAdoption.BUDGET_MS],
     * and says what the start does with it ([RootAdoption.next]). A daemon that
     * answers at once costs one probe; no daemon process costs one root shell.
     * [cancelled] is asked between probes, so a Stop does not wait out the
     * budget; that is the one case that returns [RootAdoption.Step.WAIT].
     */
    fun awaitRootDaemon(socketPath: String, cancelled: () -> Boolean): RootAdoption.Step {
        var probe = probeDaemon(socketPath)
        if (probe == RootAdoption.Probe.READY) return RootAdoption.Step.ADOPT
        val started = android.os.SystemClock.elapsedRealtime()
        var alive = isDaemonProcessAlive(socketPath)
        var aliveCheckedAt = started
        if (alive) rootLog("INFO", "A root daemon for this app is running but not answering yet ($probe); waiting for it")
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            val step = RootAdoption.next(probe, alive, now - started)
            if (step != RootAdoption.Step.WAIT || cancelled()) {
                val waited = now - started
                when {
                    step == RootAdoption.Step.ADOPT && waited > 0 ->
                        rootLog("INFO", "The root daemon answered after ${waited} ms; adopting it")
                    step == RootAdoption.Step.START_FRESH && waited > RootAdoption.PROBE_INTERVAL_MS ->
                        rootLog("INFO", "The root daemon went away while it was being waited for (${waited} ms)")
                    step == RootAdoption.Step.GIVE_UP ->
                        rootLog("WARN", "The root daemon did not answer within ${waited} ms ($probe); replacing it")
                }
                return step
            }
            Thread.sleep(RootAdoption.PROBE_INTERVAL_MS)
            probe = probeDaemon(socketPath)
            val checked = android.os.SystemClock.elapsedRealtime()
            if (probe != RootAdoption.Probe.READY && checked - aliveCheckedAt >= RootAdoption.PROCESS_RECHECK_MS) {
                alive = isDaemonProcessAlive(socketPath)
                aliveCheckedAt = checked
            }
        }
    }

    /** A connect refused by policy, as opposed to a daemon that is simply not running. */
    internal fun isPolicyDenial(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("EACCES") || msg.contains("Permission denied", ignoreCase = true)
    }

    /**
     * The `export NAME='value'` lines both root launch paths share. Every value
     * is single-quoted through shQuote — the boot script accepts nothing else
     * (assets/scripts/tailscaled.sh parses the file instead of sourcing it),
     * and a user-supplied value must never be expanded by a root shell.
     */
    private fun rootDaemonEnv(context: Context, spec: RootDaemonSpec, logsDir: String): String {
        val env = StringBuilder()
        env.append("export TS_LOGS_DIR=${shQuote(logsDir)}\n")
        env.append("export TS_NO_LOGS_NO_SUPPORT=${shQuote("true")}\n")
        env.append("export TS_AUTH_ONCE=${shQuote("true")}\n")
        // Go finds no /etc/localtime on Android: without TZ the daemon's log file is
        // UTC, and the Logs screen, which reads its stamps as local time, misplaces it.
        env.append("export TZ=${shQuote(java.util.TimeZone.getDefault().id)}\n")
        // Must match the addresses excluded from the DNS redirect in
        // applyTailscale0Routing, otherwise the daemon's bootstrap queries are
        // redirected into MagicDNS before MagicDNS can answer anything.
        val fallbacks = spec.dnsFallbacks.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        env.append("export TS_DNS_FALLBACK=${shQuote(fallbacks.joinToString(","))}\n")
        // The interface that carries the default route, which the daemon cannot
        // learn on Android: the app keeps this file current and wakes the daemon
        // ([wakeRootDaemon]). Without it every network change looked minor, so a
        // daemon that lost mobile data never rebound and stayed on dead relays.
        env.append("export TS_NETROUTE_FILE=${shQuote(File(context.filesDir, "netroute").absolutePath)}\n")
        // The daemon has no VpnService to protect its sockets, so it sets
        // Android's protect bit itself unless the user wants the opposite:
        // its own traffic riding whatever VPN client holds the tunnel. Quoted:
        // up to 4.1.0 this line was written bare and the boot script's parser
        // dropped it, so "Ignore other VPNs = off" reverted after every reboot.
        if (!GlobalSettings.isRootVpnBypassEnabled(context)) {
            env.append("export TS_VPN_BYPASS=${shQuote("0")}\n")
        }
        if (spec.taildropDir.isNotEmpty()) {
            env.append("export TS_TAILDROP_DIR=${shQuote(spec.taildropDir)}\n")
        }
        // The SOCKS5 password. Only the userspace path used to pass it, so a
        // Root Mode proxy — LAN-exposed or not — accepted anyone. The file is
        // root-owned 0600 and already carries the control-proxy password.
        // Both or neither, as everywhere else (see appctr/daemon.go).
        if (spec.socksUser.isNotEmpty() && spec.socksPass.isNotEmpty()) {
            env.append("export TS_SOCKS5_USER=${shQuote(spec.socksUser)}\n")
            env.append("export TS_SOCKS5_PASS=${shQuote(spec.socksPass)}\n")
        }
        if (spec.honestHostinfo) {
            env.append("export TS_HONEST_HOSTINFO=${shQuote("1")}\n")
            env.append("export TS_HI_OS_VERSION=${shQuote(spec.osVersion)}\n")
            env.append("export TS_HI_DEVICE_MODEL=${shQuote(spec.deviceModel)}\n")
            env.append("export TS_HI_PACKAGE=${shQuote(spec.installSource)}\n")
        }
        // Off only: on is upstream's default, and an unset variable keeps it.
        if (!spec.netmapCache) {
            env.append("export ${NetmapCache.ENV}=${shQuote("false")}\n")
        }
        if (!spec.dnsRescue) {
            env.append("export TS_DNS_RESCUE=${shQuote("0")}\n")
        }
        if (spec.controlTlsOnly) {
            env.append("export ${ControlPort.ENV}=${shQuote("true")}\n")
        }

        // Single-quoted: the control proxy URL carries a user-supplied
        // password, and this file is read by the boot script as root, so
        // a value containing $(...) or backticks would otherwise run as root
        // on every boot.
        if (spec.controlProxy.isNotEmpty()) {
            val staticOverride = resolveProxyHostStatic(spec.controlProxy)
            if (staticOverride.isNotEmpty()) {
                env.append("export TS_STATIC_HOSTS=${shQuote(staticOverride)}\n")
            }
            if (spec.controlProxy.startsWith("socks5://")) {
                env.append("export ALL_PROXY=${shQuote(spec.controlProxy)}\n")
            } else {
                env.append("export HTTP_PROXY=${shQuote(spec.controlProxy)}\n")
                env.append("export HTTPS_PROXY=${shQuote(spec.controlProxy)}\n")
            }
        }

        // What the boot script needs to build the same command line the app
        // builds below. Without these a daemon started at boot had no SOCKS5 or
        // HTTP listener (Taildrive in Files and LAN clients dead until a manual
        // restart), always a kernel TUN, and whichever profile `find` saw first.
        env.append("export TAILSOCKS_STATE_DIR=${shQuote(spec.stateDir)}\n")
        if (spec.socksAddr.isNotEmpty() && spec.socksAddr != "none") {
            env.append("export TAILSOCKS_SOCKS_ADDR=${shQuote(spec.socksAddr)}\n")
        }
        if (spec.httpAddr.isNotEmpty()) {
            env.append("export TAILSOCKS_HTTP_ADDR=${shQuote(spec.httpAddr)}\n")
        }
        env.append("export TAILSOCKS_TUN=${shQuote(if (spec.tunMode) "tailscale0" else "userspace-networking")}\n")
        return env.toString()
    }

    /**
     * Writes the root-owned env file the boot script parses.
     *
     * The boot script reads this file as root on every boot, so it must not
     * live anywhere the app uid can write: a restored backup used to be able to
     * drop an attacker's files/control_proxy.env there. It is written through
     * su into a root-owned directory, 0600, via a quoted heredoc (no expansion),
     * and the old app-writable copy is removed. Called from every Root Mode
     * start — the app's own launch and the attach to a daemon the boot script
     * started — so the next boot always uses the current settings.
     */
    fun writeRootEnvFile(context: Context, spec: RootDaemonSpec): Boolean {
        val dataDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
        val logsDir = File(dataDir, "logs").apply { mkdirs() }.absolutePath
        return writeRootEnvFile(rootDaemonEnv(context, spec, logsDir), dataDir)
    }

    private fun writeRootEnvFile(env: String, dataDir: String): Boolean {
        val ok = runSu("control-proxy-env", rootEnvFileScript(env, dataDir)).ok
        if (!ok) {
            Log.w(TAG, "Failed to write the root-owned control_proxy.env; the boot script will start with stale or no settings")
        }
        return ok
    }

    /** The env file write as a script; [startRootDaemon] runs it in its launch shell. */
    private fun rootEnvFileScript(env: String, dataDir: String): String {
        val envScript = StringBuilder()
            .append("mkdir -p ").append(paths.envDir).append(" && chmod 700 ").append(paths.envDir).append('\n')
            .append("cat > ").append(paths.envFile).append(" <<'TAILSOCKS_ENV_EOF'\n")
            // Who wrote it, in the boot script's header style. The file has no
            // format version — the script accepts `export NAME='value'` lines
            // and skips everything else, this comment included. Each package
            // has its own file (RootPaths); the line says which build wrote it.
            .append(RootBootScript.envFileHeader(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME))
            .append(env)
            .append("TAILSOCKS_ENV_EOF\n")
            .append("chmod 600 ").append(paths.envFile).append('\n')
            .append("rm -f ").append(shQuote("$dataDir/files/control_proxy.env")).append('\n')
        return envScript.toString()
    }

    fun startRootDaemon(context: Context, spec: RootDaemonSpec): Boolean {
        return try {
            val tailscaledBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
            val dataDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            val logsDir = File(dataDir, "logs").apply { mkdirs() }.absolutePath
            val logFile = if (spec.logFilePath.isNotEmpty()) spec.logFilePath else "$logsDir/tailscaled.log"
            // Everything the daemon appends from here on belongs to this run —
            // unless the script below trims the log first, which moves every
            // offset; the run marker it writes bounds the run then.
            lastDaemonLogFile = File(logFile)
            val logLength = File(logFile).length()
            daemonLogStartOffset = if (logLength > LOG_TRIM_BYTES) -1L else logLength
            soMarkScan.reset()

            val socketFile = File(spec.socketPath)
            socketFile.parentFile?.mkdirs()

            val env = rootDaemonEnv(context, spec, logsDir)

            // Every argument is quoted: the listen addresses come from free-text
            // settings fields and are interpolated into a root shell.
            val cmd = mutableListOf<String>().apply {
                add(shQuote(tailscaledBin))
                add("--statedir=" + shQuote(spec.stateDir))
                add("--socket=" + shQuote(spec.socketPath))
                if (spec.socksAddr.isNotEmpty() && spec.socksAddr != "none") {
                    add("--socks5-server=" + shQuote(spec.socksAddr))
                }
                if (spec.tunMode) {
                    add("--tun=tailscale0")
                } else {
                    add("--tun=userspace-networking")
                }
                if (spec.httpAddr.isNotEmpty()) {
                    add("--outbound-http-proxy-listen=" + shQuote(spec.httpAddr))
                }
            }.joinToString(" ")

            // The guard first: no launch while a daemon for this socket exists or
            // another launch of one is in progress (see [launchGuardScript]).
            // Then the env file the boot script reads next boot, and the same
            // variables exported for this launch — one root shell for both; they
            // used to be two, in this order.
            val sb = StringBuilder(launchGuardScript(launchLockDir(dataDir), spec.socketPath))
            sb.append(rootEnvFileScript(env, dataDir))
            sb.append(env)
            // The boot script's trim, before the marker so the marker survives it.
            sb.append(logTrimScript(logFile))
            // Run marker first: everything the daemon prints in this run follows
            // it, which is what the SO_MARK gate keys on (the boot script writes
            // the same line). A missing marker means "unverified", never "marks".
            sb.append("echo ${shQuote(RUN_MARKER)} >> ${shQuote(logFile)}\n")
            // Received Taildrop files are created by the root daemon with 0666
            // (feature/taildrop/send.go, ext.go) minus this shell's umask. Some su
            // implementations run with 077, which leaves them unreadable to the
            // app until handStateBackToApp chowns the folder at daemon stop — the
            // "File received" notification would then open into EACCES. 022 gives
            // 0644: readable by the app uid, inside its own 0700 data directory.
            // Nothing else widens: state files are written 0600 explicitly, the
            // socket and the log get their modes below. Same line in the boot
            // script (assets/scripts/tailscaled.sh).
            sb.append("umask 022\n")
            sb.append("nohup $cmd >> ${shQuote(logFile)} 2>&1 &\n")
            // The daemon runs as root, so the file is root-owned; 0644 lets the
            // app read it for the Logs screen. It sits in the app's private data
            // directory (0700), so nothing else can. 0600 left the Logs screen
            // empty in Root Mode.
            sb.append("chmod 644 ${shQuote(logFile)} 2>/dev/null || true\n")
            // No SELinux rule is injected here any more: allowSocketConnect adds
            // one below, with the real domains, and only if a connect is denied.
            sb.append("for i in \$(seq 1 30); do\n")
            sb.append("    if [ -S ${shQuote(spec.socketPath)} ] || [ -e ${shQuote(spec.socketPath)} ]; then\n")
            sb.append("        chmod 666 ${shQuote(spec.socketPath)}\n")
            sb.append("        chcon u:object_r:app_data_file:s0 ${shQuote(spec.socketPath)} 2>/dev/null || true\n")
            sb.append("        chmod 700 ${shQuote(spec.stateDir)} 2>/dev/null || true\n")
            sb.append("        break\n")
            sb.append("    fi\n")
            sb.append("    sleep 0.2\n")
            sb.append("done\n")
            // Routing is intentionally not configured here: it is applied by
            // TailscaledService once tailscale0 exists, and the DNS redirect only
            // after the daemon reaches the Running state.

            val res = runSu("daemon-start", sb.toString(), timeoutMs = 30_000L)
            Log.d(TAG, "Root daemon launch result exitCode=${res.exitCode}")
            if (!res.ok) {
                Log.w(TAG, "The daemon-start shell failed; the root-owned control_proxy.env may not have been written")
            }
            // Nothing was launched: the daemon that exists, or the one another
            // launch is bringing up, is the one to attach to. It opens its socket
            // in its own time, so the wait is the adoption budget rather than the
            // five seconds a launch of our own gets; and its run started at the
            // marker that launch wrote, not at the offset sampled above.
            val skipped = res.output.contains(LAUNCH_SKIPPED_RUNNING) || res.output.contains(LAUNCH_SKIPPED_LOCKED)
            if (skipped) {
                daemonLogStartOffset = -1L
                rootLog("INFO", "No second root daemon launched for this socket; waiting for the one that is there to answer")
            }
            val waitMs = if (skipped) RootAdoption.BUDGET_MS else 5_000L

            // Verify the daemon really came up rather than trusting the exit code.
            var attempts = 0
            var policyPatched = false
            while (attempts < waitMs / 200) {
                val error = probeSocket(spec.socketPath)
                if (error == null) {
                    Log.i(TAG, "Root daemon socket is accepting connections at ${spec.socketPath}")
                    rootLog("INFO", "Root daemon started (socket ready)")
                    return true
                }
                // The socket exists and the kernel refused us: that is policy,
                // not a daemon that failed to start. Fix it once, then keep
                // probing as before.
                if (!policyPatched && isPolicyDenial(error)) {
                    policyPatched = true
                    if (injectSocketConnectRule(spec.socketPath) && isDaemonAlive(spec.socketPath)) {
                        rootLog("INFO", "Root daemon started (socket ready)")
                        return true
                    }
                }
                Thread.sleep(200)
                attempts++
            }
            rootLog("ERROR", "Root daemon did not open ${spec.socketPath} within ${waitMs / 1000}s")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root daemon: ${e.message}", e)
            rootLog("ERROR", "Failed to start root daemon: ${e.message}")
            false
        }
    }

    /** Printed by [launchGuardScript] when a daemon for the socket is already running. */
    internal const val LAUNCH_SKIPPED_RUNNING = "launch: skipped, a daemon for this socket is already running"
    /** Printed by [launchGuardScript] when another launch holds the lock. */
    internal const val LAUNCH_SKIPPED_LOCKED = "launch: skipped, another launch for this socket is in progress"

    /** The launch lock: a directory in the app's files dir, created by root with mkdir. */
    internal fun launchLockDir(dataDir: String): String = "$dataDir/files/root-daemon-launch.lock"

    /**
     * The head of the launch script: makes "is there a daemon for this socket?"
     * and "start one" a single step, whoever runs the script.
     *
     * The app decides in Kotlin whether to launch ([awaitRootDaemon]), and two
     * start sequences that both asked before either had launched each started a
     * daemon — the second one's start-up cleanup then stripped the first one's
     * tailnet addresses from tailscale0, and the routes went with them. The
     * service no longer runs two sequences ([StartGate]), but the launch itself
     * must not depend on that: it takes a lock, then looks for the process, and
     * launches only with the lock held and no process found.
     *
     * The lock is a directory, because `mkdir` is atomic and exists in every
     * shell; it holds the owner's pid and is removed when the shell exits (the
     * EXIT trap, which INT/TERM/HUP are routed through). A shell killed outright
     * — su's timeout — leaves it behind, so a lock whose owner is gone, or one
     * older than any launch can take, is taken over. A lock with no pid yet is
     * a launch between its `mkdir` and its first write, and is left alone unless
     * it is that old. Either refusal prints a [LAUNCH_SKIPPED_LOCKED] or
     * [LAUNCH_SKIPPED_RUNNING] line and ends the script with status 0; the
     * caller then waits for the socket of the daemon that is there.
     */
    internal fun launchGuardScript(lockDir: String, socketPath: String): String = buildString {
        append("TS_LAUNCH_LOCK=").append(shQuote(lockDir)).append('\n')
        append("TS_LAUNCH_PAT=").append(shQuote("--socket=$socketPath")).append('\n')
        append("ts_launch_lock() {\n")
        append("    mkdir \"\$TS_LAUNCH_LOCK\" 2>/dev/null && { echo \$\$ > \"\$TS_LAUNCH_LOCK/pid\"; return 0; }\n")
        append("    h=\$(cat \"\$TS_LAUNCH_LOCK/pid\" 2>/dev/null)\n")
        // An age that cannot be read counts as fresh: only the owner's pid can then free it.
        append("    b=\$(stat -c %Y \"\$TS_LAUNCH_LOCK\" 2>/dev/null) || b=\$(date +%s)\n")
        append("    if [ \$((\$(date +%s) - b)) -le $LAUNCH_LOCK_STALE_S ]; then\n")
        append("        [ -z \"\$h\" ] && return 1\n")
        append("        kill -0 \"\$h\" 2>/dev/null && return 1\n")
        append("    fi\n")
        append("    rm -rf \"\$TS_LAUNCH_LOCK\"\n")
        append("    mkdir \"\$TS_LAUNCH_LOCK\" 2>/dev/null || return 1\n")
        append("    echo \$\$ > \"\$TS_LAUNCH_LOCK/pid\"\n")
        append("}\n")
        append("ts_launch_lock || { echo ").append(shQuote(LAUNCH_SKIPPED_LOCKED)).append("; exit 0; }\n")
        append("trap 'rm -rf \"\$TS_LAUNCH_LOCK\"' EXIT\n")
        append("trap 'exit 1' HUP INT TERM\n")
        append("if pgrep -f -- \"\$TS_LAUNCH_PAT\" >/dev/null 2>&1; then echo ").append(shQuote(LAUNCH_SKIPPED_RUNNING)).append("; exit 0; fi\n")
    }

    /** A launch lock older than this is a leftover: su gives a launch 30 s, then kills it. */
    private const val LAUNCH_LOCK_STALE_S = 60

    /**
     * The file the Root Mode daemon appends to. Must stay in sync with the path
     * TailscaledService hands to [startRootDaemon] and with the default there.
     */
    fun rootDaemonLogFile(context: Context): File {
        val dataDir = context.filesDir.parentFile ?: context.filesDir
        return File(dataDir, "logs/tailscaled.log")
    }

    /**
     * Truncates the Root Mode daemon log in place.
     *
     * The daemon writes the file as root, so the app uid can read it (0644) but
     * cannot open it for writing, and `File.writeText("")` silently fails with
     * EACCES. `: >` truncates through the root shell and keeps the inode: the
     * daemon holds the file open with O_APPEND, so its next line lands at the
     * new end of the file and no restart is needed.
     */
    fun clearRootDaemonLog(context: Context): Boolean {
        val path = rootDaemonLogFile(context).absolutePath
        val res = runSu("daemon-log-clear", ": > ${shQuote(path)}\n", timeoutMs = 10_000L)
        return res.ok
    }

    /** Printed by the stop shell once each part has run, so one shell can report three results. */
    private const val STOP_CLEANUP_DONE = "TAILSOCKS_STOP: routing cleanup done"
    private const val STOP_HANDBACK_DONE = "TAILSOCKS_STOP: state handed back"

    /**
     * Removes the routing, terminates the root daemon and, given [handBackTo],
     * hands its state back to the app — in one root shell.
     *
     * The stop used to spend four: the caller's cleanup, the same cleanup again
     * as this function's first line, the kill, and the chown — about ninety
     * delete loops run twice, and a fixed one-second sleep between SIGTERM and
     * SIGKILL. The commands and their order are what they were; the second
     * cleanup pass is gone, and the sleep is a poll that ends as soon as the
     * daemon has exited, still SIGKILL after one second at the latest.
     *
     * Always true, as before: no caller can do anything with a failed stop.
     */
    fun stopRootDaemon(socketPath: String = "", handBackTo: Context? = null): Boolean {
        val handBack = handBackTo?.let { stateHandBackScript(it) }
        val script = buildString {
            append(routingCleanupScript())
            append("echo '$STOP_CLEANUP_DONE'\n")
            append(daemonKillScript(socketPath))
            if (handBack != null) {
                append(handBack)
                append("echo '$STOP_HANDBACK_DONE'\n")
            }
        }
        val res = runSu("daemon-stop", script, timeoutMs = 30_000L, quiet = true)
        Log.d(TAG, "Root daemon stopped exitCode=${res.exitCode}")
        val other = res.output.lineSequence()
            .filter { it.isNotBlank() && it != STOP_CLEANUP_DONE && it != STOP_HANDBACK_DONE }
            .joinToString("\n")
        if (res.ok && other.isNotEmpty()) rootLog("INFO", "daemon-stop: $other")
        if (res.output.contains(STOP_CLEANUP_DONE)) noteRoutingRemoved()
        rootLog("INFO", "Root daemon stopped")
        if (handBack != null && !res.output.contains(STOP_HANDBACK_DONE)) {
            rootLog("WARN", "could not hand the daemon state back to the app; a non-root start may ask you to log in again")
        }
        return true
    }

    /**
     * SIGTERM, up to a second for the daemon to exit, then SIGKILL, for the
     * daemon matched by its `--socket=` argument and nothing else. That argument
     * contains this app's private data path. `pkill -f libtailscale.so` matched
     * the full command line of every process on the device, so a second profile,
     * a Termux tailscaled, or any unrelated app shipping that library was killed
     * as root too.
     */
    internal fun daemonKillScript(socketPath: String): String = buildString {
        if (socketPath.isNotEmpty()) {
            val pat = shQuote("--socket=$socketPath")
            append("pkill -15 -f -- $pat 2>/dev/null || true\n")
            append("i=0; while [ \$i -lt 10 ] && pgrep -f -- $pat >/dev/null 2>&1; do sleep 0.1; i=\$((i+1)); done\n")
            append("pkill -9 -f -- $pat 2>/dev/null || true\n")
            append("rm -f ${shQuote(socketPath)}\n")
        } else {
            // No socket path to scope by; fall back to the exact process name.
            append("killall -15 libtailscale.so 2>/dev/null || true\n")
            append("i=0; while [ \$i -lt 10 ] && pidof libtailscale.so >/dev/null 2>&1; do sleep 0.1; i=\$((i+1)); done\n")
            append("killall -9 libtailscale.so 2>/dev/null || true\n")
        }
    }

    /**
     * Gives the daemon's state directory back to the app's uid.
     *
     * The root daemon writes `tailscaled.state` and `profile-data/` as root,
     * 0600 — it rewrites them atomically, so every login and every prefs change
     * produces a fresh root-owned file. The moment the app runs a daemon of its
     * own again (Root Mode off, or root simply unavailable) it cannot read its
     * own state, starts from nothing, and asks the user to log in while the node
     * is still registered. Observed on WSA: state owned by root, the app back at
     * "authentication required" with an empty peer list.
     *
     * Called whenever the root daemon is stopped, which is the last moment root
     * is guaranteed to be available.
     */
    fun handStateBackToApp(context: Context): Boolean {
        val script = (stateHandBackScript(context) ?: return true) + "exit 0\n"
        val res = runSu("state-chown", script, timeoutMs = 15_000L)
        if (!res.ok) {
            rootLog("WARN", "could not hand the daemon state back to the app; a non-root start may ask you to log in again")
        }
        return res.ok
    }

    /** [handStateBackToApp]'s commands, without the `exit`; null when there is nothing to hand back. */
    private fun stateHandBackScript(context: Context): String? {
        val uid = context.applicationInfo.uid
        val dir = File(context.filesDir, "states").absolutePath
        // Received Taildrop files: the root daemon writes them into files/taildrop/<account>
        // as root (feature/taildrop/send.go creates the partial, ext.go renames it). The
        // start scripts set umask 022 so the app can read them while the daemon runs;
        // this hands over ownership once root is about to go away, so a later non-root
        // session can rename or rewrite them too. Ownership only — modes are left alone.
        val taildrop = File(context.filesDir, "taildrop").absolutePath
        if (!File(dir).exists() && !File(taildrop).exists()) return null
        return stateHandBackScript(uid, dir, taildrop)
    }

    internal fun stateHandBackScript(uid: Int, dir: String, taildrop: String): String = buildString {
        append("if [ -d ${shQuote(dir)} ]; then\n")
        append("  chown -R $uid:$uid ${shQuote(dir)} 2>/dev/null || true\n")
        // Ownership is what matters; keep the modes the daemon expects.
        append("  find ${shQuote(dir)} -type d -exec chmod 700 {} + 2>/dev/null || true\n")
        append("  find ${shQuote(dir)} -type f -exec chmod 600 {} + 2>/dev/null || true\n")
        append("fi\n")
        append("[ -d ${shQuote(taildrop)} ] && chown -R $uid:$uid ${shQuote(taildrop)} 2>/dev/null\n")
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

    /**
     * One A query to 1.1.1.1 over UDP, for when the system resolver could not
     * answer. The reply is parsed ([DnsWire.firstA]): it must answer this query's
     * id with NOERROR, and the address is the first A record's. The packet used
     * to be scanned from its end for any four bytes that looked like an address,
     * which a trailing CNAME, SOA or OPT record turned into a wrong one — handed
     * to the root daemon as the control proxy's address. The socket is closed on
     * every path; a timeout, the common failure, used to leak it.
     */
    private fun resolveHostViaUdpDns(host: String): String? {
        return try {
            val id = java.security.SecureRandom().nextInt(0x10000)
            val query = DnsWire.aQuery(host, id) ?: return null
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val dnsServer = java.net.InetAddress.getByName("1.1.1.1")
                socket.send(java.net.DatagramPacket(query, query.size, dnsServer, 53))
                val buf = ByteArray(512)
                val reply = java.net.DatagramPacket(buf, buf.size)
                socket.receive(reply)
                DnsWire.firstA(reply.data, reply.length, id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct UDP DNS resolution failed for '$host': ${e.message}")
            null
        }
    }
}
