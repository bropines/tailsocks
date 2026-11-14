package io.github.bropines.tailscaled.core

import android.util.Log
import io.github.bropines.tailscaled.core.RootDaemon.isDaemonAlive
import io.github.bropines.tailscaled.core.RootDaemon.isPolicyDenial
import io.github.bropines.tailscaled.core.RootDaemon.probeSocket
import io.github.bropines.tailscaled.core.RootShell.rootLog
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.TAG
import io.github.bropines.tailscaled.core.RootUtils.shQuote
import java.io.File

/**
 * The SELinux repair for the daemon socket: when the app's connect to the
 * root daemon is refused by policy, both domains are read at runtime
 * ([appDomain], [daemonDomain]) and one live `connectto` rule is injected
 * between them ([allowSocketConnect], [injectSocketConnectRule]).
 */
internal object RootSelinux {
    /** SELinux type of a context like `u:r:untrusted_app_32:s0:c512,c768`. */
    private val selinuxTypeName = Regex("^[a-z][a-z0-9_]*$")

    private fun selinuxType(context: String): String? {
        val fields = context.replace("\u0000", "").trim().split(":")
        if (fields.size < 3) return null
        val type = fields[2]
        return if (selinuxTypeName.matches(type)) type else null
    }

    /** The domain this app runs in, straight from the kernel. */
    private fun appDomain(): String? = try {
        selinuxType(File("/proc/self/attr/current").readText())
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read own SELinux context: ${e.message}")
        null
    }

    /**
     * The domain the root daemon runs in. Matched by its `--socket=` argument,
     * like [stopRootDaemon], so a second profile or an unrelated tailscaled is
     * never sampled. Falls back to the domain of the root shell itself, which is
     * what the daemon inherits when it is spawned from one.
     */
    private fun daemonDomain(socketPath: String): String? {
        val pat = shQuote("--socket=$socketPath")
        val script = buildString {
            append("pid=\$(pgrep -f -- $pat 2>/dev/null | head -n1)\n")
            append("if [ -n \"\$pid\" ]; then cat /proc/\$pid/attr/current; else cat /proc/self/attr/current; fi\n")
        }
        val res = runSu("daemon-domain", script, timeoutMs = 10_000L)
        if (!res.ok) return null
        return res.output.lineSequence().firstNotNullOfOrNull { selinuxType(it) }
    }

    /**
     * Makes the app able to reach the root daemon socket when SELinux is what is
     * stopping it — and does nothing at all otherwise.
     *
     * The daemon is started from a root shell, so it runs in the root solution's
     * domain while the app connects from its own. Stock policy grants no
     * `connectto` across that pair, and the connect fails with EACCES.
     *
     * Up to 3.6 the fix was a fixed `allow untrusted_app magisk
     * unix_stream_socket connectto`, injected on every service start and on
     * every boot. It named domains nobody had looked up: `untrusted_app` is not
     * what an app with a modern targetSdk runs as, and `magisk` is not the
     * domain of a KernelSU or APatch daemon — so on many devices it patched the
     * policy of every untrusted app on the device and still did not fix
     * anything. Now both domains are read at runtime, and the rule is only
     * applied after a connect was actually denied.
     *
     * Returns true when the socket is reachable afterwards.
     */
    fun allowSocketConnect(socketPath: String): Boolean {
        val error = probeSocket(socketPath) ?: return true
        if (!isPolicyDenial(error)) return false
        return injectSocketConnectRule(socketPath) && isDaemonAlive(socketPath)
    }

    internal fun injectSocketConnectRule(socketPath: String): Boolean {
        val app = appDomain()
        val daemon = daemonDomain(socketPath)
        if (app == null || daemon == null) {
            rootLog("ERROR", "SELinux denied the daemon socket, but the domains could not be read (app=$app daemon=$daemon)")
            return false
        }
        if (app == daemon) {
            rootLog("ERROR", "SELinux denied the daemon socket although both sides run as $app; not a domain-transition problem")
            return false
        }
        val rule = shQuote("allow $app $daemon unix_stream_socket connectto")
        // magiskpolicy (Magisk, and APatch which ships the same tool), then
        // KernelSU's ksud, then SuperSU's supolicy. Whichever exists wins; the
        // rule is live-only and gone after a reboot.
        val script = "magiskpolicy --live $rule 2>/dev/null || " +
            "ksud sepolicy patch $rule 2>/dev/null || " +
            "supolicy --live $rule 2>/dev/null"
        val res = runSu("sepolicy-allow", script, timeoutMs = 10_000L)
        if (!res.ok) {
            rootLog("ERROR", "No SELinux policy tool accepted: allow $app $daemon unix_stream_socket connectto")
            return false
        }
        rootLog("INFO", "SELinux denied the app -> daemon socket connect; injected: allow $app $daemon unix_stream_socket connectto")
        return true
    }
}
