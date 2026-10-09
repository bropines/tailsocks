package io.github.bropines.tailscaled.admin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {

    private fun caps(vararg scopes: String) =
        Capabilities.fromScopes(BackendKind.TAILSCALE, TailscaleBackend.FEATURES, scopes.toList())

    @Test
    fun allReadReachesEverythingForReading() {
        val c = caps("all:read")
        AdminArea.entries.forEach {
            assertTrue(it.name, c.canRead(it))
            assertFalse(it.name, c.canWrite(it))
        }
    }

    @Test
    fun allWritesEverythingButReadOnlyAreas() {
        val c = caps("all")
        assertTrue(c.canWrite(AdminArea.DEVICES))
        assertTrue(c.canWrite(AdminArea.POLICY))
        assertTrue(c.canRead(AdminArea.AUDIT_LOGS))
        assertFalse("there is no logs:configuration write scope", c.canWrite(AdminArea.AUDIT_LOGS))
    }

    @Test
    fun aWriteScopeImpliesReadAndTheStrongerScopeWins() {
        val c = caps("devices:core:read", "devices:core", "dns:read")
        assertEquals(Access.WRITE, c.access(AdminArea.DEVICES))
        assertEquals(Access.READ, c.access(AdminArea.DNS))
        assertEquals(Access.NONE, c.access(AdminArea.USERS))
        assertEquals(Access.NONE, c.access(AdminArea.ROUTES))
    }

    @Test
    fun unknownScopesAreIgnored() {
        val c = caps("oauth_apps", "tailnets:read", " ")
        AdminArea.entries.forEach { assertEquals(it.name, Access.NONE, c.access(it)) }
    }

    @Test
    fun refusalsNarrow() {
        val token = Capabilities(BackendKind.TAILSCALE, CredentialKind.API_TOKEN, TailscaleBackend.FEATURES)
        assertTrue(token.canWrite(AdminArea.USERS))
        val writeRefused = token.denied(AdminArea.USERS, write = true)
        assertTrue(writeRefused.canRead(AdminArea.USERS))
        assertFalse(writeRefused.canWrite(AdminArea.USERS))
        val readRefused = writeRefused.denied(AdminArea.USERS, write = false)
        assertFalse(readRefused.canRead(AdminArea.USERS))
        assertFalse("a refused read never comes back with a refused write", readRefused.denied(AdminArea.USERS, true).canRead(AdminArea.USERS))
    }

    @Test
    fun scopeNamesForMessages() {
        assertEquals("dns:read", AdminArea.DNS.scopeFor(write = false))
        assertEquals("devices:routes", AdminArea.ROUTES.scopeFor(write = true))
        assertEquals("logs:configuration:read", AdminArea.AUDIT_LOGS.scopeFor(write = true))
    }

    @Test
    fun keyIdsFromSecrets() {
        assertEquals("kAbC123CNTRL", AdminCredential.ApiToken("tskey-api-kAbC123CNTRL-xYz987").keyId)
        assertEquals("kCL1CNTRL", AdminCredential.OAuthClient("", "tskey-client-kCL1CNTRL-secret").keyId)
        assertEquals("given", AdminCredential.OAuthClient("given", "tskey-client-kCL1CNTRL-secret").keyId)
        assertNull(AdminCredential.ApiToken("not-a-key").keyId)
        assertNull(AdminCredential.ApiToken("tskey-api-").keyId)
        assertNull(AdminCredential.ApiToken("tskey-api-onlyid").keyId)
    }

    @Test
    fun credentialsNeverPrintTheirSecret() {
        val token = AdminCredential.ApiToken("tskey-api-kA1-supersecret")
        val client = AdminCredential.OAuthClient("kC1", "tskey-client-kC1-supersecret")
        assertFalse(token.toString().contains("supersecret"))
        assertFalse(client.toString().contains("supersecret"))
    }
}
