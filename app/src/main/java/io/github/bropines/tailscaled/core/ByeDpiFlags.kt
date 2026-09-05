package io.github.bropines.tailscaled.core

/**
 * Allow-list for user-supplied ByeDPI flags.
 *
 * The flags string is handed to byedpi's real `main()` inside the app
 * process, and its option table is much wider than DPI tuning: `--pidfile`
 * and `--cache-file` create/truncate/delete files the app can write,
 * `--hosts`/`--ipset`/`--fake-data <file>` read any file the app can read,
 * `-i 0.0.0.0` rebinds the unauthenticated SOCKS listener from its random
 * loopback address onto every interface, `--connect-to` redirects traffic,
 * `--daemon` forks. The string reaches this point from Settings, from the
 * automation receiver and AppFunctions, and — bypassing every setter — from a
 * restored backup, so the check lives at use time.
 *
 * Tokens are resolved the way getopt_long does (bundled short flags,
 * attached values, `--long=value`, unambiguous long-option prefixes) and a
 * token is kept only if every option in it is a desync/tuning option.
 */
object ByeDpiFlags {

    private class Opt(val short: Char, val long: String, val hasArg: Boolean, val allowed: Boolean)

    // Mirrors `options[]` in app/src/main/jni/byedpi/byedpi_core/main.c.
    private val table = listOf(
        Opt('D', "daemon", false, false),
        Opt('w', "pidfile", true, false),
        Opt('N', "no-domain", false, true),
        Opt('X', "no-ipv6", false, true),
        Opt('U', "no-udp", false, true),
        Opt('h', "help", false, false),
        Opt('v', "version", false, false),
        Opt('i', "ip", true, false),
        Opt('p', "port", true, false),
        Opt('E', "transparent", false, false),
        Opt('I', "conn-ip", true, false),
        Opt('b', "buf-size", true, true),
        Opt('c', "max-conn", true, true),
        Opt('x', "debug", true, true),
        Opt('F', "tfo", false, true),
        Opt('A', "auto", true, true),
        Opt('L', "auto-mode", true, true),
        Opt('u', "cache-ttl", true, true),
        Opt('T', "timeout", true, true),
        Opt('B', "copy", true, true),
        Opt('y', "cache-file", true, false),
        Opt('K', "proto", true, true),
        Opt('H', "hosts", true, false),
        Opt('V', "pf", true, true),
        Opt('R', "round", true, true),
        Opt('s', "split", true, true),
        Opt('d', "disorder", true, true),
        Opt('o', "oob", true, true),
        Opt('q', "disoob", true, true),
        Opt('f', "fake", true, true),
        Opt('S', "md5sig", false, true),
        Opt('n', "fake-sni", true, true),
        Opt('t', "ttl", true, true),
        Opt('l', "fake-data", true, true), // inline `:hex` payloads only, see isValueAllowed
        Opt('O', "fake-offset", true, true),
        Opt('Q', "fake-tls-mod", true, true),
        Opt('e', "oob-data", true, true),
        Opt('M', "mod-http", true, true),
        Opt('r', "tlsrec", true, true),
        Opt('m', "tlsminor", true, true),
        Opt('a', "udp-fake", true, true),
        Opt('g', "def-ttl", true, true),
        Opt('Z', "wait-send", false, true),
        Opt('W', "await-int", true, true),
        Opt('Y', "drop-sack", false, true),
        Opt('P', "protect-path", true, false),
        Opt('j', "ipset", true, false),
        Opt('C', "connect-to", true, false),
        Opt('#', "comment", true, true),
        Opt('/', "cache-merge", true, false),
    )

    private val byShort = table.associateBy { it.short }

    class Result(val accepted: List<String>, val rejected: List<String>)

    /** `--fake-data` reads a file unless the payload is given inline as `:hex`. */
    private fun isValueAllowed(opt: Opt, value: String?): Boolean =
        opt.short != 'l' || (value != null && value.startsWith(":"))

    private fun resolveLong(name: String): Opt? {
        table.firstOrNull { it.long == name }?.let { return it }
        val prefixed = table.filter { it.long.startsWith(name) }
        return if (name.isNotEmpty() && prefixed.size == 1) prefixed[0] else null
    }

    fun sanitize(flags: String): Result {
        val tokens = flags.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            var consumed = 1
            var ok = true

            when {
                tok.startsWith("--") && tok.length > 2 -> {
                    val name = tok.substring(2).substringBefore('=')
                    val attached = if (tok.contains('=')) tok.substringAfter('=') else null
                    val opt = resolveLong(name)
                    if (opt == null) {
                        ok = false
                    } else {
                        var value = attached
                        if (opt.hasArg && value == null) {
                            value = tokens.getOrNull(i + 1)
                            if (value != null) consumed = 2
                        }
                        ok = opt.allowed && isValueAllowed(opt, value)
                    }
                }
                tok.startsWith("-") && tok.length > 1 -> {
                    var j = 1
                    while (j < tok.length && ok) {
                        val opt = byShort[tok[j]]
                        if (opt == null) { ok = false; break }
                        if (opt.hasArg) {
                            val rest = tok.substring(j + 1)
                            var value: String? = rest.ifEmpty { null }
                            if (value == null) {
                                value = tokens.getOrNull(i + 1)
                                if (value != null) consumed = 2
                            }
                            ok = opt.allowed && isValueAllowed(opt, value)
                            break
                        }
                        if (!opt.allowed) ok = false
                        j++
                    }
                }
                else -> ok = false // a stray positional argument; byedpi has none
            }

            val slice = tokens.subList(i, minOf(i + consumed, tokens.size))
            if (ok) accepted.addAll(slice) else rejected.addAll(slice)
            i += consumed
        }
        return Result(accepted, rejected)
    }
}
