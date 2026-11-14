package io.github.bropines.tailscaled.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The control-proxy fallback resolver: a strict A query and a strict reading of its answer. */
class DnsWireTest {

    private val id = 0x4a2b

    /** A DNS message built by hand: header, the question for [qname], then [answers] as raw records. */
    private fun reply(
        answers: List<ByteArray>,
        qname: String = "proxy.example.com",
        replyId: Int = id,
        flags: Int = 0x8180,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
        u16(replyId); u16(flags); u16(1); u16(answers.size); u16(0); u16(0)
        for (label in qname.split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0); u16(1); u16(1)
        answers.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** One resource record whose name is a pointer to the question name (offset 12). */
    private fun rr(type: Int, rdata: ByteArray, cls: Int = 1): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
        out.write(0xc0); out.write(12)
        u16(type); u16(cls)
        u16(0); u16(300) // TTL
        u16(rdata.size)
        out.write(rdata)
        return out.toByteArray()
    }

    private fun a(vararg octets: Int) = rr(1, ByteArray(4) { octets[it].toByte() })
    /** A CNAME to `edge.cdn.net`, written out in labels. */
    private fun cname() = rr(5, byteArrayOf(4, 'e'.code.toByte(), 'd'.code.toByte(), 'g'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'd'.code.toByte(), 'n'.code.toByte(), 3, 'n'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte(), 0))

    private fun parse(msg: ByteArray, replyLen: Int = msg.size) = DnsWire.firstA(msg, replyLen, id)

    @Test
    fun `the query is an A IN question with recursion desired`() {
        val q = DnsWire.aQuery("proxy.example.com", id)!!
        val expected = byteArrayOf(0x4a, 0x2b, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(5) + "proxy".toByteArray() + byteArrayOf(7) + "example".toByteArray() +
            byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        assertArrayEquals(expected, q)
        // A trailing dot is the same name.
        assertArrayEquals(q, DnsWire.aQuery("proxy.example.com.", id))
    }

    @Test
    fun `a name that is not one gives no query`() {
        assertNull(DnsWire.aQuery("", id))
        assertNull(DnsWire.aQuery("a..b", id))
        assertNull(DnsWire.aQuery("x".repeat(64) + ".com", id))
    }

    @Test
    fun `a single A record`() {
        assertEquals("203.0.113.7", parse(reply(listOf(a(203, 0, 113, 7)))))
    }

    @Test
    fun `a CNAME chain ahead of the address is stepped over`() {
        assertEquals("198.51.100.20", parse(reply(listOf(cname(), a(198, 51, 100, 20)))))
    }

    @Test
    fun `the first A record wins, not the last bytes of the packet`() {
        assertEquals("198.51.100.20", parse(reply(listOf(a(198, 51, 100, 20), a(203, 0, 113, 7)))))
    }

    @Test
    fun `a CNAME with no address is nothing`() {
        assertNull(parse(reply(listOf(cname()))))
    }

    @Test
    fun `a trailing record that is not an address is not read as one`() {
        // TXT whose bytes look like an IPv4 address, after the real answer.
        val txt = rr(16, byteArrayOf(4, 10, 20, 30, 40))
        assertEquals("192.0.2.1", parse(reply(listOf(a(192, 0, 2, 1), txt))))
        assertNull(parse(reply(listOf(txt))))
    }

    @Test
    fun `an error reply is nothing`() {
        assertNull(parse(reply(listOf(a(192, 0, 2, 1)), flags = 0x8183))) // NXDOMAIN
        assertNull(parse(reply(emptyList(), flags = 0x8182))) // SERVFAIL
    }

    @Test
    fun `a reply to another query is nothing`() {
        assertNull(parse(reply(listOf(a(192, 0, 2, 1)), replyId = id + 1)))
    }

    @Test
    fun `a query echoed back is not a reply`() {
        assertNull(parse(reply(listOf(a(192, 0, 2, 1)), flags = 0x0100)))
    }

    @Test
    fun `a truncated reply gives nothing rather than a guess`() {
        val full = reply(listOf(a(192, 0, 2, 1)))
        assertNull(parse(full, full.size - 2))
        assertNull(parse(full, 11))
    }

    @Test
    fun `a blocked name's null address is nothing`() {
        assertNull(parse(reply(listOf(a(0, 0, 0, 0)))))
        assertNull(parse(reply(listOf(a(127, 0, 0, 1)))))
    }

    @Test
    fun `a record of another class is skipped`() {
        assertEquals("192.0.2.9", parse(reply(listOf(rr(1, byteArrayOf(10, 0, 0, 1), cls = 3), a(192, 0, 2, 9)))))
    }
}
