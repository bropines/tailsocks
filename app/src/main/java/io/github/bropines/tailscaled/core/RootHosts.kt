package io.github.bropines.tailscaled.core

import android.util.Log
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.TAG
import io.github.bropines.tailscaled.core.RootUtils.paths
import io.github.bropines.tailscaled.core.RootUtils.shQuote

/**
 * Tailnet names published into `/system/etc/hosts`: the validated copy bound
 * over the system file ([updateRootHosts]), the marker that names it ours
 * ([hostsMarker]) and the unbind that removes only our own layer
 * ([hostsUnbindScript]).
 */
internal object RootHosts {
    /** A hosts entry may only contain characters valid in a DNS name. */
    private val hostLabel = Regex("^[A-Za-z0-9._-]{1,253}$")

    private val ipv4Literal = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6Literal = Regex("^[0-9A-Fa-f:]{2,45}$")

    /** An address is written to a root-owned file, so it must really be one. */
    internal fun isIpLiteral(value: String): Boolean = when {
        ipv4Literal.matches(value) -> value.split(".").all { (it.toIntOrNull() ?: 256) <= 255 }
        ipv6Literal.matches(value) -> value.contains(":")
        else -> false
    }

    /**
     * Publishes tailnet names into /system/etc/hosts.
     *
     * Peer names are self-reported and are not validated by the control plane,
     * so they are treated as hostile input: anything that is not a plain DNS
     * label is dropped, and the file is written through a quoted heredoc rather
     * than one shell `echo` per entry. Interpolating a peer name into a root
     * shell let any device on the tailnet run code as root on this phone.
     */
    fun updateRootHosts(hostsMap: Map<String, String>): Boolean {
        val lines = mutableListOf<String>()
        for ((ip, aliases) in hostsMap) {
            if (!isIpLiteral(ip)) {
                Log.w(TAG, "hosts-sync: skipping non-address entry '$ip'")
                continue
            }
            val safe = aliases.split(" ").filter { it.isNotEmpty() && hostLabel.matches(it) }
            if (safe.isEmpty()) {
                Log.w(TAG, "hosts-sync: skipping entry with no usable names for $ip")
                continue
            }
            lines.add("$ip ${safe.joinToString(" ")}")
        }
        if (lines.isEmpty()) return true

        // 'TAILSOCKS_EOF' is quoted, so the shell performs no expansion at all
        // on the body — the entries are copied through verbatim.
        val sb = StringBuilder()
        val hosts = paths.hostsFile
        // Only our own bind comes off, and before `set -e`: the unbind is a
        // loop of tests that end in a non-zero status by design. Whatever else
        // is mounted there — Magisk's systemless hosts, AdAway — stays, and its
        // entries become the base ours are appended to.
        sb.append(hostsUnbindScript(hosts, hostsMarker(hosts)))
        sb.append("set -e\n")
        sb.append("mkdir -p ${paths.hostsDir}\n")
        sb.append("cp /system/etc/hosts $hosts 2>/dev/null || printf '127.0.0.1 localhost\\n::1 ip6-localhost\\n' > $hosts\n")
        sb.append("cat >> $hosts <<'TAILSOCKS_EOF'\n")
        // How the next sync and the stop know the file at /system/etc/hosts is ours.
        sb.append(hostsMarker(hosts)).append('\n')
        for (line in lines) sb.append(line).append('\n')
        sb.append("TAILSOCKS_EOF\n")
        sb.append("chmod 644 $hosts\n")
        sb.append("chcon u:object_r:system_file:s0 $hosts 2>/dev/null || true\n")
        sb.append("mount -o bind $hosts /system/etc/hosts 2>/dev/null || true\n")
        return runSu("hosts-sync", sb.toString()).ok
    }

    /**
     * The line [updateRootHosts] writes into its copy of the hosts file. Names
     * the copy, so the release app and the debug build (each with its own
     * file, [RootPaths]) never take each other's for their own. No peer entry
     * can equal it: entries start with an address, this with `#`.
     */
    internal fun hostsMarker(hostsFile: String): String = "# TailSocks MagicDNS names, $hostsFile"

    /**
     * Unmounts what is bound over `/system/etc/hosts` only while it is ours,
     * and keeps going while ours is what is visible (a bind left stacked by an
     * earlier failure), four layers at most.
     *
     * Every sync and every stop used to run a bare `umount /system/etc/hosts`.
     * On the first sync after a boot our bind is not there yet, so that removed
     * whatever another root module had put there (Magisk's systemless hosts,
     * AdAway) and took the stock file as the base. Ours is recognised two ways,
     * either one enough: the topmost mount on that path in
     * `/proc/self/mountinfo` has our copy as its source (its root field is the
     * path inside the `/data` filesystem, or the full path), or the visible
     * file carries [hostsMarker] — which also covers a bind made by a build
     * that wrote no marker, and a mount namespace whose mountinfo names the
     * source differently. Field 4 of mountinfo is the root, field 5 the mount
     * point; read with shell builtins.
     */
    internal fun hostsUnbindScript(hostsFile: String, marker: String): String = buildString {
        val full = shQuote(hostsFile)
        val rel = shQuote(hostsFile.removePrefix("/data"))
        append("i=0\n")
        append("while [ \$i -lt 4 ]; do\n")
        append("    i=\$((i+1))\n")
        append("    top=\"\"\n")
        append("    while read -r f1 f2 f3 root mnt rest; do\n")
        append("        if [ \"\$mnt\" = /system/etc/hosts ]; then top=\"\$root\"; fi\n")
        append("    done < /proc/self/mountinfo\n")
        append("    ours=0\n")
        append("    case \"\$top\" in $full|$rel) ours=1;; esac\n")
        append("    [ \$ours = 1 ] || grep -qxF ${shQuote(marker)} /system/etc/hosts 2>/dev/null || break\n")
        append("    umount /system/etc/hosts 2>/dev/null || break\n")
        append("done\n")
    }
}
