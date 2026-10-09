package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Which backend a Headscale server needs, from `GET /version` and probes; the release's quirks by version. */
class HeadscaleDetectTest {

    @Test
    fun versionsParse() {
        val v = HeadscaleVersion.parse("v0.29.4")!!
        assertEquals(0, v.major)
        assertEquals(29, v.minor)
        assertEquals(4, v.patch)
        assertEquals("0.29.4", v.label)
        assertFalse(v.development)
        assertEquals(30, HeadscaleVersion.parse("0.30.0-beta.1")!!.minor)
        val dev = HeadscaleVersion.parse("v0.0.0-20261009094437-a8d6f5be81e5")!!
        assertTrue(dev.development)
        assertFalse("a development build is not compared by number", dev.atLeast(26))
        assertNull(HeadscaleVersion.parse("not a version"))
        assertNull(HeadscaleVersion.parse(""))
        assertTrue(HeadscaleVersion.parse("v0.29.0")!! > HeadscaleVersion.parse("v0.28.9")!!)
    }

    @Test
    fun eachBreakingReleaseIsOneSwitch() {
        fun level(v: String) = V1Level(HeadscaleVersion.parse(v)!!)
        assertTrue(level("v0.25.1").routesApi)
        assertFalse(level("v0.25.1").preAuthKeyUserIsId)
        assertFalse(level("v0.26.1").routesApi)
        assertTrue(level("v0.26.1").preAuthKeyUserIsId)
        assertFalse(level("v0.27.1").singleTags)
        assertTrue(level("v0.28.0").singleTags)
        assertTrue(level("v0.28.0").preAuthKeysListAll)
        assertFalse(level("v0.28.0").authRegister)
        assertTrue(level("v0.29.4").authRegister)
        assertTrue(level("v0.29.4").policyCheck)
        assertTrue(level("v0.29.4").disableExpiry)
        val dev = V1Level(HeadscaleVersion.parse("v0.0.0-20261009094437-a8d6f5be81e5")!!)
        assertTrue("a development build is the newest", dev.authRegister && !dev.routesApi)
    }

    private fun detect(t: FakeTransport) = runBlocking { v1Backend(t, null).detectServer() }

    @Test
    fun release029IsTheV1Adapter() {
        val t = FakeTransport().on("GET", "/version", null, true, hs("v029", "version"))
        val (kind, version) = detect(t)
        assertEquals(BackendKind.HEADSCALE_V1, kind)
        assertEquals("0.29.4", version.label)
        assertNull("/version is asked without the key", t.requests.single().headers["Authorization"])
        assertTrue(t.requests.none { it.url.contains("/api/v2") })
    }

    @Test
    fun aDevelopmentBuildWithApiV2IsTheV2Backend() {
        val t = FakeTransport()
            .on("GET", "/version", null, true, hs("main", "version"))
            .on("GET", "/tailnet/-/devices", null, true, hs("main", "devices"))
        val (kind, version) = detect(t)
        assertEquals(BackendKind.HEADSCALE_V2, kind)
        assertTrue(version.development)
    }

    @Test
    fun release030WithoutV2StaysOnV1() {
        val t = FakeTransport()
            .on("GET", "/version", null, true, { HttpResponse(200, """{"version":"v0.30.1"}""") })
            .on("GET", "/tailnet/-/devices", null, true, status(404, "404 page not found"))
        assertEquals(BackendKind.HEADSCALE_V1, detect(t).first)
    }

    @Test
    fun withoutVersionTheRoutesApiTells025From026() {
        val old = FakeTransport()
            .on("GET", "/version", null, true, status(404, "404 page not found"))
            .on("GET", "/api/v1/routes", null, true, { HttpResponse(200, """{"routes":[]}""") })
        assertEquals(HeadscaleVersion.V0_25, detect(old).second)
        val newer = FakeTransport()
            .on("GET", "/version", null, true, status(404, "404 page not found"))
            .on("GET", "/api/v1/routes", null, true, hs("v029", "routes_probe"))
        assertEquals(HeadscaleVersion.V0_26, detect(newer).second)
    }

    @Test
    fun aRefusedKeyStopsDetection() {
        val t = FakeTransport()
            .on("GET", "/version", null, true, hs("main", "version"))
            .on("GET", "/tailnet/-/devices", null, true, hs("main", "unauthorized"))
        try {
            detect(t)
            fail("expected Unauthorized")
        } catch (e: AdminApiException.Unauthorized) {
            assertEquals("unauthorized", e.apiMessage)
        }
    }

    @Test
    fun apiKeyPrefixesAsTheListShowsThem() {
        assertEquals("hskey-api-AbCd-EfGh123-***", HsApiKey.listedPrefixOf(HeadscaleFixtures.KEY_029))
        assertEquals("abcdefg", HsApiKey.listedPrefixOf("abcdefg.0123456789secret"))
        assertNull(HsApiKey.listedPrefixOf("tskey-api-kABC-secret"))
        assertEquals("AbCd-EfGh123", HsApiKey(prefix = "hskey-api-AbCd-EfGh123-***").bareprefix)
    }
}
