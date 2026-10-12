package io.github.bropines.tailscaled.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Tailnet Lock's status read the way `tailscale lock status` reads it, and the sign request. */
class TailnetLockTest {

    private val self = "nlpub:" + "ab".repeat(32)
    private val other = "nlpub:" + "cd".repeat(32)
    private val nodeKey = "nodekey:" + "12".repeat(32)

    /** Shaped like ipnstate.TailnetLockStatus as the daemon marshals it, with the fields the app skips. */
    private fun status(enabled: Boolean = true, signed: Boolean = true, trusted: List<String> = listOf(self, other), publicKey: String = self) = """
        {"Enabled":$enabled,"Head":[1,2,3],"PublicKey":"$publicKey","NodeKey":"$nodeKey","NodeKeySigned":$signed,
         "NodeKeySignature":{"SigKind":1,"Pubkey":"AAAA","KeyID":"AAAA"},
         "TrustedKeys":[${trusted.joinToString(",") { """{"Kind":"25519","Key":"$it","Metadata":null,"Votes":1}""" }}],
         "VisiblePeers":null,
         "FilteredPeers":[{"Name":"laptop.tail1234.ts.net.","ID":42,"StableID":"nAbc","TailscaleIPs":["100.64.0.7","fd7a:115c:a1e0::7"],
                           "NodeKey":"nodekey:${"ef".repeat(32)}","NodeKeySignature":{"SigKind":0}}],
         "StateID":12345}
    """.trimIndent()

    @Test
    fun aTrustedSignerSeesWhoWaits() {
        val st = TailnetLock.parse(status())!!
        assertTrue(st.enabled)
        assertTrue(st.hasKey)
        assertTrue(st.isTrustedSigner)
        assertFalse(st.isLockedOut)
        val peer = st.lockedOutPeers.single()
        assertEquals("laptop", peer.shortName)
        assertEquals(listOf("100.64.0.7", "fd7a:115c:a1e0::7"), peer.tailscaleIps)
    }

    @Test
    fun aSignedNodeWithoutATrustedKeyCannotSign() {
        val st = TailnetLock.parse(status(trusted = listOf(other)))!!
        assertFalse(st.isTrustedSigner)
        assertFalse(st.isLockedOut)
    }

    @Test
    fun anUnsignedNodeIsLockedOutAndGetsTheCommandForASigner() {
        val st = TailnetLock.parse(status(signed = false, trusted = listOf(other)))!!
        assertTrue(st.isLockedOut)
        assertEquals("tailscale lock sign $nodeKey tlpub:${"ab".repeat(32)}", TailnetLock.signCommand(st))
    }

    @Test
    fun lockOffMeansNothingWaitsAndNobodySigns() {
        val st = TailnetLock.parse(status(enabled = false))!!
        assertFalse(st.isTrustedSigner)
        assertFalse(st.isLockedOut)
        assertTrue(st.lockedOutPeers.isEmpty())
    }

    @Test
    fun aZeroKeyIsNoKey() {
        val st = TailnetLock.parse(status(publicKey = "nlpub:" + "0".repeat(64), trusted = listOf("nlpub:" + "0".repeat(64))))!!
        assertFalse(st.hasKey)
        assertFalse("a zero key matches nothing, not even another zero key", st.isTrustedSigner)
        assertNull(TailnetLock.signCommand(st))
    }

    @Test
    fun errorsAndGarbageAreNoStatus() {
        assertNull(TailnetLock.parse("Error: HTTP 403: lock status access denied"))
        assertNull(TailnetLock.parse(""))
        assertNull(TailnetLock.parse("<html>"))
    }

    @Test
    fun keysAreShownTheCliWay() {
        assertEquals("tlpub:" + "ab".repeat(32), TailnetLock.cliKey(self))
        assertEquals("tlpub:abc", TailnetLock.cliKey("tlpub:abc"))
        assertEquals("nodekey:121212…1212", TailnetLock.shortKey(nodeKey))
        assertEquals("nodekey:1234", TailnetLock.shortKey("nodekey:1234"))
        assertTrue(TailnetLock.sameKey(self, "tlpub:" + "AB".repeat(32)))
    }

    @Test
    fun theSignRequestIsWhatTheCliSends() {
        val o = AppJson.parseToJsonElement(TailnetLock.signRequest(nodeKey)).jsonObject
        assertEquals(JsonPrimitive(nodeKey), o["NodeKey"])
        // `tailscale lock sign <node-key>` passes the zero key's verifier: 32 zero bytes.
        val rotation = Base64.getDecoder().decode((o["RotationPublic"] as JsonPrimitive).content)
        assertEquals(32, rotation.size)
        assertTrue(rotation.all { it == 0.toByte() })
    }
}
