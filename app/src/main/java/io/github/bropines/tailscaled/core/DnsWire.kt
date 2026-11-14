package io.github.bropines.tailscaled.core

/**
 * The two ends of one plain DNS exchange over UDP: an A query for a name, and
 * the first A record of the matching answer. Used by RootUtils to resolve the
 * control proxy's host when the system resolver cannot (its result goes into
 * the root daemon's TS_STATIC_HOSTS), so it is strict about what it accepts: a
 * reply to another query, an error reply, or a malformed message gives no
 * address rather than four bytes that merely look like one.
 */
internal object DnsWire {
    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    /** An A/IN query for [host] with transaction [id]; null when [host] is not a valid DNS name. */
    fun aQuery(host: String, id: Int): ByteArray? {
        val name = host.trimEnd('.')
        if (name.isEmpty() || name.length > 253) return null
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
        u16(id and 0xffff)
        u16(0x0100) // standard query, recursion desired
        u16(1)      // one question
        u16(0); u16(0); u16(0)
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) return null
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(TYPE_A)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * The first usable A record of [message] (its first [length] bytes), or null.
     *
     * Usable: the message is a response to transaction [id] with RCODE 0, and
     * the record is TYPE A, CLASS IN, four bytes long, and not in `0.0.0.0/8`
     * or `127.0.0.0/8` — the answers a filtering resolver gives for a blocked
     * name, and never the address of a proxy. The question section is skipped
     * and the answer records are walked in order, so a CNAME chain ahead of the
     * address is stepped over and a reply with no A record yields nothing.
     */
    fun firstA(message: ByteArray, length: Int, id: Int): String? {
        val end = minOf(length, message.size)
        if (end < 12) return null
        fun u8(i: Int) = message[i].toInt() and 0xff
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != (id and 0xffff)) return null
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null // not a response
        if (flags and 0x000f != 0) return null // NXDOMAIN, SERVFAIL, ...
        val qd = u16(4)
        val an = u16(6)
        var pos = 12
        repeat(qd) {
            pos = skipName(message, pos, end) ?: return null
            pos += 4
            if (pos > end) return null
        }
        repeat(an) {
            pos = skipName(message, pos, end) ?: return null
            if (pos + 10 > end) return null
            val type = u16(pos)
            val cls = u16(pos + 2)
            val rdLength = u16(pos + 8)
            pos += 10
            if (pos + rdLength > end) return null
            if (type == TYPE_A && cls == CLASS_IN && rdLength == 4) {
                val b0 = u8(pos)
                if (b0 != 0 && b0 != 127) return "$b0.${u8(pos + 1)}.${u8(pos + 2)}.${u8(pos + 3)}"
            }
            pos += rdLength
        }
        return null
    }

    /** The offset just past the name at [start]: labels up to a zero byte, or a compression pointer. */
    private fun skipName(message: ByteArray, start: Int, end: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= end) return null
            val len = message[pos].toInt() and 0xff
            when {
                len == 0 -> return pos + 1
                len and 0xc0 == 0xc0 -> return if (pos + 2 <= end) pos + 2 else null
                len and 0xc0 != 0 -> return null // reserved label types
                else -> pos += 1 + len
            }
        }
    }
}
