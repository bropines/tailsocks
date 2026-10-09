package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registration links as devices show them; policy checks and the HuJSON the policy is written in. */
class RegistrationAndPolicyTest {

    private fun ok(text: String) = (RegistrationLink.parse(text) as RegistrationLink.Parsed.Ok).link
    private fun problem(text: String) = (RegistrationLink.parse(text) as RegistrationLink.Parsed.Invalid).problem

    @Test
    fun linksFromTailscaleUp() {
        val link = ok("https://hs.example.com/register/hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0")
        assertEquals("hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0", link.authId)
        assertEquals("hs.example.com", link.host)
        assertTrue(link.isPrefixed)
        assertTrue(link.isFor("https://hs.example.com"))
        assertFalse(link.isFor("https://other.example.com/headscale"))
        // The text tailscale up prints around it, a confirm page, and a server behind a path.
        assertEquals(
            "hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0",
            ok("To authenticate, visit:\n\n\thttps://hs.example.com/register/hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0\n").authId,
        )
        assertEquals("hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0", ok("https://hs.example.com/register/confirm/hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0").authId)
        assertEquals("AbCdEfGh_jKlMnOp-rStUvWx", ok("https://example.com/hs/register/AbCdEfGh_jKlMnOp-rStUvWx?x=1").authId)
    }

    @Test
    fun idsAndCommandsWithoutALink() {
        assertNull(ok("hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0").host)
        assertEquals("hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0", ok("headscale auth register --auth-id hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0 --user alice").authId)
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWx", ok("headscale nodes register --user alice --key AbCdEfGhIjKlMnOpQrStUvWx").authId)
        assertFalse(ok("AbCdEfGhIjKlMnOpQrStUvWx").isPrefixed)
    }

    @Test
    fun whatIsNotALink() {
        assertEquals(RegistrationLink.Problem.EMPTY, problem("  "))
        assertEquals(RegistrationLink.Problem.NOT_A_LINK, problem("https://hs.example.com/admin"))
        assertEquals(RegistrationLink.Problem.NOT_A_LINK, problem("hello"))
        assertEquals(RegistrationLink.Problem.BAD_ID, problem("https://hs.example.com/register/short"))
        assertEquals(RegistrationLink.Problem.BAD_ID, problem("https://hs.example.com/register/hskey-authreq-tooshort"))
    }

    @Test
    fun huJsonBecomesJsonWithStringsLeftAlone() {
        val text = """
            {
              // a comment
              "tagOwners": {"tag:web": ["alice@"], /* inline */ "tag:db": [],},
              "hosts": {"url": "https://x.example/a//b", "t": "a,]"},
            }
        """.trimIndent()
        assertEquals(listOf("tag:db", "tag:web"), PolicyText.tags(text))
        val plain = HuJson.standardize(text)
        assertTrue(plain.contains("https://x.example/a//b"))
        assertTrue(plain.contains("\"a,]\""))
        assertFalse(plain.contains("comment"))
        // Unparseable: every quoted tag still counts.
        assertEquals(listOf("tag:a", "tag:b"), PolicyText.tags("""{ "x": "tag:b", broken "tag:a" """))
    }

    @Test
    fun theEtagIsHeadscalesOwn() {
        // The /api/v2 ETag of Headscale's default policy, as the bench sent it.
        assertEquals("\"69aa7cf0dc30c5b15611e17052920b5b926ead907933fc237434878264f8b6de\"", PolicyText.etag(PolicyText.DEFAULT))
    }

    @Test
    fun aPolicyIsCheckedByTheServerWhereItCan() = runBlocking {
        val bad = FakeTransport().on("POST", "/api/v1/policy/check", null, true, hs("v029", "policy_check_bad"))
        val refused = v1Backend(bad).validatePolicy("""{"acls":[{"action":"jump"}]}""")
        assertFalse(refused.ok)
        assertTrue(refused.message!!.contains("invalid ACL action"))

        val good = FakeTransport().on("POST", "/api/v1/policy/check", null, true, status(200, "{}"))
        assertTrue(v1Backend(good).validatePolicy("{}").ok)
        assertTrue("0.30 checks through v1", v2Backend(good).validatePolicy("{}").ok)

        // 0.28 has no check: parsed here, nothing sent.
        val none = FakeTransport()
        val old = v1Backend(none, HeadscaleVersion.parse("v0.28.0"))
        assertTrue(old.validatePolicy("{\"acls\": [],}").ok)
        assertFalse(old.validatePolicy("{\"acls\": [").ok)
        assertTrue(none.requests.isEmpty())
    }
}
