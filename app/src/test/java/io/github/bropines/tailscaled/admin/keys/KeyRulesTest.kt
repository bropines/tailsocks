package io.github.bropines.tailscaled.admin.keys

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.KeyCapabilities
import io.github.bropines.tailscaled.admin.api.KeyCreateOptions
import io.github.bropines.tailscaled.admin.api.KeyDeviceCapabilities
import io.github.bropines.tailscaled.admin.api.KeyType
import io.github.bropines.tailscaled.admin.api.recorded
import io.github.bropines.tailscaled.admin.safety.maskSecret
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyListTest {
    /** 2026-10-09T12:00:00Z. */
    private val now = 1_791_547_200_000L
    private val day = 24L * 3600 * 1000

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    private fun key(id: String, type: String, expires: Long? = null, created: String = "2026-09-01T00:00:00Z", revoked: String? = null, invalid: Boolean? = null) =
        ApiKey(id = id, keyType = type, expires = expires?.let(::iso), created = created, revoked = revoked, invalid = invalid)

    @Test
    fun expiryWarnsWithinSevenDays() {
        assertEquals(ExpiryState.NEVER, KeyList.expiryOf(null, now).state)
        assertEquals(ExpiryState.NEVER, KeyList.expiryOf("0001-01-01T00:00:00Z", now).state)
        assertEquals(ExpiryState.OK, KeyList.expiryOf(iso(now + 8 * day), now).state)
        assertEquals(ExpiryState.SOON, KeyList.expiryOf(iso(now + 7 * day), now).state)
        assertEquals(ExpiryState.SOON, KeyList.expiryOf(iso(now + 3600_000), now).state)
        assertEquals(ExpiryState.EXPIRED, KeyList.expiryOf(iso(now - 1000), now).state)
        assertEquals(3 * day, KeyList.expiryOf(iso(now + 3 * day), now).remainingMs)
    }

    @Test
    fun spansRoundDownToOneUnit() {
        assertEquals(Span(TimeUnitShown.DAYS, 3), KeyList.span(3 * day + 5 * 3600_000))
        assertEquals(Span(TimeUnitShown.HOURS, 23), KeyList.span(day - 1))
        assertEquals(Span(TimeUnitShown.MINUTES, 1), KeyList.span(10_000))
        assertEquals("past spans count the same way", Span(TimeUnitShown.DAYS, 2), KeyList.span(-2 * day))
    }

    @Test
    fun sectionsByTypeInTheTabsOrder() {
        val keys = listOf(
            key("api1", "api", now + 30 * day),
            key("fed1", "federated"),
            key("auth-late", "auth", now + 60 * day),
            key("cl1", "client"),
            key("auth-soon", "auth", now + 2 * day),
            key("auth-revoked", "auth", now + 60 * day, revoked = "2026-09-02T00:00:00Z"),
            key("auth-expired", "auth", now - day),
            key("odd", "something-new"),
        )
        val sections = KeyList.sections(keys, now)
        assertEquals(listOf(KeyGroup.AUTH, KeyGroup.CLIENT, KeyGroup.FEDERATED, KeyGroup.API, KeyGroup.OTHER), sections.map { it.group })
        assertEquals(
            "usable first, soonest expiry on top, the dead after them",
            listOf("auth-soon", "auth-late", "auth-revoked", "auth-expired").toSet(),
            sections.first().keys.map { it.id }.toSet(),
        )
        assertEquals(listOf("auth-soon", "auth-late"), sections.first().keys.take(2).map { it.id })
        assertTrue(sections.first().keys.drop(2).none { KeyList.isUsable(it, now) })
    }

    @Test
    fun theRecordedListGroups() {
        val keys = AppJson.parseToJsonElement(recorded("keys.json")).let { (it as JsonObject)["keys"]!!.jsonArray }
            .map { AppJson.decodeFromJsonElement<ApiKey>(it) }
        val sections = KeyList.sections(keys, now)
        assertEquals(listOf(KeyGroup.AUTH, KeyGroup.CLIENT, KeyGroup.FEDERATED, KeyGroup.API), sections.map { it.group })
        val auth = sections.first().keys
        assertEquals("the live key before the revoked one", listOf("kAUTH1CNTRL", "kAUTH2CNTRL"), auth.map { it.id })
        assertFalse(KeyList.isUsable(auth[1], now))
    }

    @Test
    fun revokingNeedsTheScopeOfTheKeysType() {
        assertEquals(AdminArea.AUTH_KEYS, KeyList.revokeArea(KeyType.AUTH))
        assertEquals(AdminArea.OAUTH_KEYS, KeyList.revokeArea(KeyType.CLIENT))
        assertEquals(AdminArea.FEDERATED_KEYS, KeyList.revokeArea(KeyType.FEDERATED))
        assertEquals(AdminArea.API_TOKENS, KeyList.revokeArea(KeyType.API))
    }

    @Test
    fun capabilitiesAreReadAsTheyCome() {
        val k = ApiKey(id = "k", keyType = "auth", capabilities = KeyCapabilities(KeyDeviceCapabilities(KeyCreateOptions(reusable = false, ephemeral = true))))
        assertEquals(false, k.createOptions?.reusable)
        assertEquals(true, k.createOptions?.ephemeral)
        assertNull(k.createOptions?.preauthorized)
    }
}

class KeyRulesTest {

    @Test
    fun descriptionAsTheSchemaAllows() {
        assertNull(KeyRules.descriptionProblem(""))
        assertNull(KeyRules.descriptionProblem("homelab servers-2"))
        assertEquals(DescriptionProblem.MISSING, KeyRules.descriptionProblem("  ", required = true))
        assertEquals(DescriptionProblem.TOO_LONG, KeyRules.descriptionProblem("a".repeat(51)))
        assertNull(KeyRules.descriptionProblem("a".repeat(50)))
        assertEquals(DescriptionProblem.BAD_CHARACTERS, KeyRules.descriptionProblem("ci_key"))
        assertEquals(DescriptionProblem.BAD_CHARACTERS, KeyRules.descriptionProblem("сервер"))
    }

    @Test
    fun expiryPresetsAndCustomDays() {
        assertEquals(3600L, KeyRules.expirySeconds(ExpiryPreset.HOUR, ""))
        assertEquals(86_400L, KeyRules.expirySeconds(ExpiryPreset.DAY, "5"))
        assertEquals(90 * 86_400L, KeyRules.expirySeconds(ExpiryPreset.QUARTER, ""))
        assertEquals(14 * 86_400L, KeyRules.expirySeconds(ExpiryPreset.CUSTOM, " 14 "))
        assertNull(KeyRules.expirySeconds(ExpiryPreset.CUSTOM, "0"))
        assertNull(KeyRules.expirySeconds(ExpiryPreset.CUSTOM, "91"))
        assertNull(KeyRules.expirySeconds(ExpiryPreset.CUSTOM, ""))
    }

    @Test
    fun typedTagsGetTheirPrefixAndBadOnesAreNamed() {
        val (good, bad) = KeyRules.typedTags("server, tag:ci  tag:web-1,tag:,tag:bad!")
        assertEquals(listOf("tag:server", "tag:ci", "tag:web-1"), good)
        assertEquals(listOf("tag:", "tag:bad!"), bad)
        assertTrue(KeyRules.isTag("tag:k8s_node"))
        assertFalse(KeyRules.isTag("tag:-x"))
        assertFalse(KeyRules.isTag("server"))
    }

    @Test
    fun emails() {
        assertTrue(KeyRules.isEmail("a@example.com"))
        assertTrue(KeyRules.isEmail(" a.b+c@sub.example.org "))
        assertFalse(KeyRules.isEmail("a@example"))
        assertFalse(KeyRules.isEmail("a example@x.com"))
    }

    @Test
    fun aSecretIsMaskedPastItsName() {
        assertEquals("tskey-auth-kD3m0CNTRL-" + "•".repeat(12), maskSecret("tskey-auth-kD3m0CNTRL-6RdkZq8v1sQpL2yBxWnT9cHjU4aMfE7"))
        assertEquals("https://login.tailscale.com/uinv/" + "•".repeat(12), maskSecret("https://login.tailscale.com/uinv/abcdef123456"))
        assertEquals("•".repeat(12), maskSecret("plainsecret"))
    }
}

class TagOwnersTest {

    private val policy = """
        // The tailnet's policy, as an admin writes it.
        {
          "groups": {"group:ops": ["alex@example.com"],},
          /* Who may hand out which tag. */
          "tagOwners": {
            "tag:server": ["group:ops"],
            "tag:ci":     ["tag:server", "autogroup:admin"], // CI nodes, owned by the servers
            "tag:web":    [],
          },
          "acls": [
            {"action": "accept", "src": ["*"], "dst": ["tag:server:22", "http://x//y:*"]},
          ],
        }
    """.trimIndent()

    @Test
    fun hujsonCommentsAndTrailingCommas() {
        val owners = TagOwners.parse(policy)!!
        assertEquals(listOf("tag:ci", "tag:server", "tag:web"), owners.keys.toList())
        assertEquals(listOf("tag:server", "autogroup:admin"), owners["tag:ci"])
        assertEquals(emptyList<String>(), owners["tag:web"])
    }

    @Test
    fun stringsKeepTheirSlashesAndCommas() {
        val text = TagOwners.standardize("""{"a": "http://x//y, z", /* c */ "b": [1,2,],}""")
        assertEquals("""{"a": "http://x//y, z",   "b": [1,2]}""", text)
    }

    @Test
    fun aPolicyWithoutTagOwnersHasNoTags() {
        assertEquals(emptyMap<String, List<String>>(), TagOwners.parse("""{"acls": []}"""))
    }

    @Test
    fun somethingThatIsNotAPolicyIsNull() {
        assertNull(TagOwners.parse("<html>proxy error</html>"))
        assertNull(TagOwners.parse("""{"tagOwners": ["tag:x"]}"""))
    }

    @Test
    fun aScopedClientMayUseItsTagsAndWhatTheyOwn() {
        val owners = TagOwners.parse(policy)!!
        assertEquals(listOf("tag:ci", "tag:server", "tag:web"), TagOwners.assignable(owners, emptyList(), scoped = false))
        assertEquals(listOf("tag:ci", "tag:server"), TagOwners.assignable(owners, listOf("tag:server"), scoped = true))
        assertEquals("its own tags even when the policy is unknown", listOf("tag:x"), TagOwners.assignable(emptyMap(), listOf("tag:x"), scoped = true))
    }
}

class OAuthScopesTest {

    @Test
    fun levelsBecomeScopes() {
        val scopes = OAuthScopes.toScopes(
            mapOf("devices:core" to ScopeLevel.WRITE, "dns" to ScopeLevel.READ, "users" to ScopeLevel.NONE, "logs:configuration" to ScopeLevel.WRITE)
        )
        assertEquals("a read-only area never gets its write half", listOf("devices:core", "dns:read", "logs:configuration:read"), scopes)
    }

    @Test
    fun allMakesTheSameLevelRedundant() {
        assertEquals(listOf("all"), OAuthScopes.toScopes(mapOf("all" to ScopeLevel.WRITE, "dns" to ScopeLevel.READ)))
        assertEquals(
            listOf("all:read", "dns"),
            OAuthScopes.toScopes(mapOf("all" to ScopeLevel.READ, "dns" to ScopeLevel.WRITE, "users" to ScopeLevel.READ)),
        )
        assertTrue(OAuthScopes.toScopes(emptyMap()).isEmpty())
    }

    @Test
    fun tagsAreMandatoryForDeviceAndKeyWrites() {
        assertTrue(OAuthScopes.needsTags(listOf("auth_keys")))
        assertTrue(OAuthScopes.needsTags(listOf("devices:core", "dns:read")))
        assertTrue(OAuthScopes.needsTags(listOf("all")))
        assertFalse(OAuthScopes.needsTags(listOf("all:read", "auth_keys:read", "dns")))
    }

    @Test
    fun everyAreaIsOffered() {
        val offered = OAuthScopes.options.map { it.scope }
        assertEquals("all", offered.first())
        AdminArea.entries.forEach { assertTrue(it.scope, it.scope in offered) }
    }
}
