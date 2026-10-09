package io.github.bropines.tailscaled.admin.dns

import java.net.URI
import java.util.Locale

enum class DnsInputError {
    EMPTY,
    /** Neither an IPv4 nor an IPv6 address nor a DoH URL. */
    NOT_RESOLVER,
    /** A URL, but not https:// with a host: DNS over HTTPS only. */
    DOH_NOT_HTTPS,
    BAD_DOMAIN,
    DUPLICATE,
}

/** A field's value as it will be sent, or why it will not be. */
data class DnsCheck(val value: String?, val error: DnsInputError?) {
    val ok: Boolean get() = error == null && value != null
}

/**
 * What the DNS tab accepts, checked here before anything is proposed — without a lookup:
 * InetAddress would resolve a name it was handed, so addresses are parsed by hand.
 */
object DnsInput {

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.length in 1..3 && p.all { it.isAsciiDigit() } && (p.length == 1 || p[0] != '0') && p.toInt() <= 255 }
    }

    fun isIpv6(s: String): Boolean {
        if (s.isEmpty() || '%' in s || ':' !in s) return false
        var str = s
        val lastColon = s.lastIndexOf(':')
        val tail = s.substring(lastColon + 1)
        if ('.' in tail) {
            if (!isIpv4(tail)) return false
            str = s.substring(0, lastColon + 1) + "0:0"
        }
        fun hex(g: String) = g.length in 1..4 && g.all { it.isAsciiDigit() || it.lowercaseChar() in 'a'..'f' }
        val dbl = str.indexOf("::")
        if (dbl != str.lastIndexOf("::")) return false
        return if (dbl >= 0) {
            val left = str.substring(0, dbl)
            val right = str.substring(dbl + 2)
            val l = if (left.isEmpty()) emptyList() else left.split(':')
            val r = if (right.isEmpty()) emptyList() else right.split(':')
            (l + r).all(::hex) && l.size + r.size <= 7
        } else {
            val g = str.split(':')
            g.size == 8 && g.all(::hex)
        }
    }

    /** A DNS-over-HTTPS endpoint: https, a host, no credentials in it. */
    fun isDoh(s: String): Boolean {
        val uri = runCatching { URI(s) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null && s.none { it.isWhitespace() }
    }

    /** A resolver for the nameserver and split-DNS lists: an IP address or a DoH URL. */
    fun resolver(raw: String, existing: Collection<String> = emptyList()): DnsCheck {
        val s = raw.trim()
        if (s.isEmpty()) return DnsCheck(null, DnsInputError.EMPTY)
        val value = when {
            isIpv4(s) -> s
            isIpv6(s.removePrefix("[").removeSuffix("]")) -> s.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
            "://" in s -> if (isDoh(s)) s else return DnsCheck(null, DnsInputError.DOH_NOT_HTTPS)
            else -> return DnsCheck(null, DnsInputError.NOT_RESOLVER)
        }
        if (existing.any { it.equals(value, ignoreCase = true) }) return DnsCheck(null, DnsInputError.DUPLICATE)
        return DnsCheck(value, null)
    }

    /** A domain for split DNS or the search list: letters, digits and hyphens, dot-separated labels. */
    fun domain(raw: String, existing: Collection<String> = emptyList()): DnsCheck {
        val s = raw.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (s.isEmpty()) return DnsCheck(null, DnsInputError.EMPTY)
        if (s.length > 253) return DnsCheck(null, DnsInputError.BAD_DOMAIN)
        val labels = s.split('.')
        val ok = labels.all { l ->
            l.length in 1..63 && l.first() != '-' && l.last() != '-' && l.all { it in 'a'..'z' || it.isAsciiDigit() || it == '-' }
        }
        if (!ok) return DnsCheck(null, DnsInputError.BAD_DOMAIN)
        if (existing.any { it.equals(s, ignoreCase = true) }) return DnsCheck(null, DnsInputError.DUPLICATE)
        return DnsCheck(s, null)
    }

    /** A comma- or space-separated list of resolvers; the first bad one is the answer. */
    fun resolvers(raw: String): Pair<List<String>, DnsInputError?> {
        val parts = raw.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList<String>() to DnsInputError.EMPTY
        val out = mutableListOf<String>()
        for (p in parts) {
            val c = resolver(p, out)
            if (!c.ok) return emptyList<String>() to c.error
            out += c.value!!
        }
        return out to null
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
