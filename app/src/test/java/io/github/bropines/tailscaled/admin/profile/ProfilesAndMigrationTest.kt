package io.github.bropines.tailscaled.admin.profile

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.secure.CredentialVault
import io.github.bropines.tailscaled.admin.secure.MemoryKeyValueStore
import io.github.bropines.tailscaled.admin.secure.SealedBlob
import io.github.bropines.tailscaled.admin.secure.SecretBox
import io.github.bropines.tailscaled.admin.secure.SecretUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** The Keystore box's algorithm with a software key: what the vault logic needs to be tested. */
class SoftwareSecretBox : SecretBox {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var failSeal = false

    override fun seal(plaintext: String, aad: String): String {
        if (failSeal) throw IllegalStateException("keystore unavailable")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(aad.toByteArray())
        return SealedBlob.encode(c.iv, c.doFinal(plaintext.toByteArray()))
    }

    override fun open(blob: String, aad: String): String {
        val (iv, ct) = SealedBlob.decode(blob)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad.toByteArray())
            String(c.doFinal(ct))
        } catch (e: Exception) {
            throw SecretUnavailableException("cannot open", e)
        }
    }
}

class ProfilesAndMigrationTest {

    private val vaultStore = MemoryKeyValueStore()
    private val box = SoftwareSecretBox()
    private val vault = CredentialVault(vaultStore, box)
    private val store = AdminProfileStore(MemoryKeyValueStore())
    private var ids = 0
    private fun newId() = "p${++ids}"

    @Test
    fun theVaultHoldsNoPlaintextAndBindsBlobsToTheirSlot() {
        vault.put("p1", CredentialVault.Slot.API_TOKEN, "tskey-api-kA-supersecret")
        val raw = vaultStore.getString("p1/API_TOKEN")!!
        assertFalse(raw.contains("supersecret"))
        assertEquals("tskey-api-kA-supersecret", vault.get("p1", CredentialVault.Slot.API_TOKEN))
        // Moved into another profile's slot, the blob no longer opens.
        vaultStore.putString("p2/API_TOKEN", raw)
        try {
            vault.get("p2", CredentialVault.Slot.API_TOKEN)
            fail()
        } catch (_: SecretUnavailableException) {
        }
        assertNull(vault.get("p1", CredentialVault.Slot.OAUTH_SECRET))
        vault.put("p1", CredentialVault.Slot.API_TOKEN, "")
        assertFalse(vault.has("p1", CredentialVault.Slot.API_TOKEN))
    }

    @Test
    fun aBlobFromAnotherDeviceIsUnavailableNotACrash() {
        val other = CredentialVault(vaultStore, SoftwareSecretBox())
        other.put("p1", CredentialVault.Slot.API_TOKEN, "tskey-api-kA-x")
        try {
            vault.get("p1", CredentialVault.Slot.API_TOKEN)
            fail()
        } catch (_: SecretUnavailableException) {
        }
        vaultStore.putString("p1/API_TOKEN", "garbage")
        try {
            vault.get("p1", CredentialVault.Slot.API_TOKEN)
            fail()
        } catch (_: SecretUnavailableException) {
        }
    }

    @Test
    fun legacyTokenAndOauthTailnetsBecomeProfiles() {
        val legacy = mapOf(
            "tail1234.ts.net" to "tskey-api-kOLD1-secret",
            "tail1234.ts.net_auth_type" to "TOKEN",
            "tail1234.ts.net_proxy_mode" to "CUSTOM_SOCKS5",
            "tail1234.ts.net_proxy_host" to "10.0.0.2",
            "tail1234.ts.net_proxy_port" to 1080,
            "tail1234.ts.net_proxy_user" to "me",
            "tail1234.ts.net_proxy_pass" to "pw",
            "work.example.com" to "",
            "work.example.com_auth_type" to "OAUTH",
            "work.example.com_oauth_client_id" to "kCLIENT9",
            "work.example.com_oauth_client_secret" to "tskey-client-kCLIENT9-s",
            "empty.ts.net_auth_type" to "TOKEN",
        )
        val result = LegacyMigration.migrate(legacy, store, vault, "work.example.com", ::newId, now = 42)
        assertTrue(result.complete)
        assertEquals(listOf("empty.ts.net"), result.skipped)
        val byName = store.profiles().associateBy { it.name }
        val token = byName.getValue("tail1234.ts.net")
        assertEquals(AuthType.API_TOKEN, token.authType)
        assertEquals("-", token.tailnet)
        assertEquals("tail1234.ts.net", token.tailnetDnsName)
        assertEquals(AdminProxySettings("CUSTOM_SOCKS5", "10.0.0.2", 1080, "me"), token.proxy)
        assertEquals("tskey-api-kOLD1-secret", vault.get(token.id, CredentialVault.Slot.API_TOKEN))
        assertEquals("pw", vault.get(token.id, CredentialVault.Slot.PROXY_PASSWORD))
        val oauth = byName.getValue("work.example.com")
        assertEquals(AuthType.OAUTH_CLIENT, oauth.authType)
        assertEquals("kCLIENT9", oauth.oauthClientId)
        assertEquals("tskey-client-kCLIENT9-s", vault.get(oauth.id, CredentialVault.Slot.OAUTH_SECRET))
        assertEquals("the tailnet this phone was on is the active one", oauth.id, store.active()!!.id)
    }

    @Test
    fun theChosenTypeWinsWhenCompleteAndTheOtherOneOtherwise() {
        val legacy = mapOf(
            "a.ts.net" to "tskey-api-kA-x",
            "a.ts.net_auth_type" to "TOKEN",
            "a.ts.net_oauth_client_id" to "kC",
            "a.ts.net_oauth_client_secret" to "tskey-client-kC-y",
            "b.ts.net_auth_type" to "TOKEN",
            "b.ts.net_oauth_client_id" to "kD",
            "b.ts.net_oauth_client_secret" to "tskey-client-kD-z",
        )
        LegacyMigration.migrate(legacy, store, vault, null, ::newId, 0)
        val byName = store.profiles().associateBy { it.name }
        assertEquals(AuthType.API_TOKEN, byName.getValue("a.ts.net").authType)
        assertFalse("only the secret in use is kept", vault.has(byName.getValue("a.ts.net").id, CredentialVault.Slot.OAUTH_SECRET))
        assertEquals(AuthType.OAUTH_CLIENT, byName.getValue("b.ts.net").authType)
    }

    @Test
    fun aSecondRunDoesNotDuplicate() {
        val legacy = mapOf("a.ts.net" to "tskey-api-kA-x")
        LegacyMigration.migrate(legacy, store, vault, null, ::newId, 0)
        val again = LegacyMigration.migrate(legacy, store, vault, null, ::newId, 0)
        assertTrue(again.complete)
        assertEquals(listOf("a.ts.net"), again.skipped)
        assertEquals(1, store.profiles().size)
    }

    @Test
    fun aFailedSealKeepsTheLegacyFile() {
        box.failSeal = true
        val result = LegacyMigration.migrate(mapOf("a.ts.net" to "tskey-api-kA-x"), store, vault, null, ::newId, 0)
        assertFalse("the caller must not delete the old file", result.complete)
        assertTrue(store.profiles().isEmpty())
    }

    @Test
    fun tailnetNamesFromLegacyKeys() {
        assertEquals(
            setOf("a.ts.net", "b.example"),
            LegacyMigration.tailnets(mapOf("a.ts.net" to "t", "b.example_proxy_port" to 1, "a.ts.net_auth_type" to "TOKEN"))
        )
    }

    @Test
    fun profileStoreActiveAndTailnetLookup() {
        val a = AdminProfile("a", "Home", tailnetDnsName = "tail1.ts.net")
        val b = AdminProfile("b", "Work")
        store.save(a)
        assertEquals(a, store.forTailnet("tail1.ts.net."))
        assertNull("a profile that learned another suffix does not answer", store.forTailnet("other.ts.net"))
        store.save(b)
        assertNull("two candidates: no guessing", store.forTailnet("other.ts.net"))
        assertEquals(a, store.active())
        store.setActive("b")
        assertEquals(b, store.active())
        store.update("b") { it.copy(deniedReads = setOf(AdminArea.USERS)) }
        assertEquals(setOf(AdminArea.USERS), store.get("b")!!.deniedReads)
        store.remove("b")
        assertEquals(a, store.active())
        store.remove("a")
        assertNull(store.active())
    }

    @Test
    fun aSingleUnlearnedProfileServesTheTailnet() {
        val only = AdminProfile("x", "Only")
        store.save(only)
        assertEquals(only, store.forTailnet("whatever.ts.net"))
    }

    @Test
    fun baseUrlsAreHttpsOrLoopback() {
        fun ok(raw: String) = (BaseUrlRules.check(raw) as BaseUrlRules.Result.Ok).url
        fun bad(raw: String) = (BaseUrlRules.check(raw) as BaseUrlRules.Result.Invalid).reason
        assertEquals("https://api.tailscale.com", ok("https://api.tailscale.com/"))
        assertEquals("https://hs.example.org/headscale", ok(" https://hs.example.org/headscale/ "))
        assertEquals("http://127.0.0.1:8080", ok("http://127.0.0.1:8080"))
        assertEquals("http://localhost:8080", ok("http://LOCALHOST:8080/"))
        assertEquals("http://[::1]:8080", ok("http://[::1]:8080"))
        assertEquals(BaseUrlRules.Reason.HTTPS_REQUIRED, bad("http://hs.example.org"))
        assertEquals(BaseUrlRules.Reason.HTTPS_REQUIRED, bad("http://192.168.1.10:8080"))
        assertEquals(BaseUrlRules.Reason.NO_CREDENTIALS_IN_URL, bad("https://user:pw@hs.example.org"))
        assertEquals(BaseUrlRules.Reason.MALFORMED, bad("ftp://hs.example.org"))
        assertEquals(BaseUrlRules.Reason.MALFORMED, bad("hs.example.org"))
        assertEquals(BaseUrlRules.Reason.MALFORMED, bad("https://hs.example.org/?x=1"))
        assertEquals(BaseUrlRules.Reason.EMPTY, bad("  "))
        assertTrue(BaseUrlRules.isTailscaleCloud("https://api.tailscale.com/"))
    }
}
