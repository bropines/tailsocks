package io.github.bropines.tailscaled.admin.console

import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.profile.SoftwareSecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The console's copy on the phone: sealed per profile, secrets left out, never planned from. */
class ConsoleCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val devices = listOf(
        ApiDevice(nodeId = "n1", name = "pangolin.tail1234.ts.net", advertisedRoutes = listOf("10.0.0.0/16"), enabledRoutes = emptyList()),
        ApiDevice(nodeId = "n2", name = "phone.tail1234.ts.net", tags = listOf("tag:phone")),
    )

    private fun loaded() = ConsoleState(
        devices = Loadable(devices, loadedAt = 1_000),
        users = Loadable(listOf(ApiUser(id = "u1", loginName = "a@example.com")), loadedAt = 2_000),
        keys = Loadable(listOf(ApiKey(id = "k1", key = "tskey-auth-k1-SECRET", description = "ci")), loadedAt = 3_000),
        dns = Loadable(DnsConfiguration(), loadedAt = 4_000),
        webhooks = Loadable(listOf(ApiWebhook(endpointId = "w1", endpointUrl = "https://hooks.example/T0K3N")), loadedAt = 5_000),
        policyTags = listOf("tag:phone"),
    )

    @Test
    fun aSnapshotComesBackAsTheCopyFromDisk() {
        val cache = ConsoleCache(tmp.root, SoftwareSecretBox())
        cache.write("p1", ConsoleCache.snapshotOf(loaded()))
        val seeded = ConsoleCache.seed(ConsoleState(), cache.read("p1"))
        assertEquals(devices, seeded.devices.value)
        assertTrue(seeded.devices.fromDisk)
        assertEquals(1_000, seeded.devices.loadedAt)
        assertFalse("a copy is never fresh: the server is asked at once", seeded.devices.fresh(1_001, 60_000))
        assertEquals("a@example.com", seeded.users.value!!.single().loginName)
        assertEquals(listOf("tag:phone"), seeded.policyTags)
        assertNull("webhooks are not kept", seeded.webhooks.value)
    }

    @Test
    fun noSecretReachesTheFile() {
        val box = SoftwareSecretBox()
        val cache = ConsoleCache(tmp.root, box)
        cache.write("p1", ConsoleCache.snapshotOf(loaded()))
        val sealed = File(tmp.root, "p1").readText()
        assertFalse(sealed.contains("pangolin"))
        val plain = box.open(sealed, "admin-console-cache:p1")
        assertFalse(plain.contains("SECRET"))
        assertFalse(plain.contains("T0K3N"))
        assertNull(cache.read("p1")!!.keys!!.value.single().key)
    }

    @Test
    fun aCopyOpensOnlyAsItsOwnProfile() {
        val cache = ConsoleCache(tmp.root, SoftwareSecretBox())
        cache.write("p1", ConsoleCache.snapshotOf(loaded()))
        File(tmp.root, "p1").copyTo(File(tmp.root, "p2"))
        assertNull(cache.read("p2"))
        assertFalse("an unreadable copy is dropped", File(tmp.root, "p2").exists())
        assertNotNull(cache.read("p1"))
        cache.clear("p1")
        assertNull(cache.read("p1"))
    }

    @Test
    fun anotherPhonesCopyStartsEmpty() {
        ConsoleCache(tmp.root, SoftwareSecretBox()).write("p1", ConsoleCache.snapshotOf(loaded()))
        assertNull(ConsoleCache(tmp.root, SoftwareSecretBox()).read("p1"))
    }

    @Test
    fun whatTheCredentialMayNotReadIsNotKept() {
        val caps = Capabilities(
            backend = BackendKind.TAILSCALE, credential = CredentialKind.OAUTH_CLIENT, features = TailscaleBackend.FEATURES,
            access = mapOf(AdminArea.USERS to Access.NONE, AdminArea.DEVICES to Access.READ),
        )
        val snap = ConsoleCache.snapshotOf(loaded().copy(caps = caps))
        assertNull(snap.users)
        assertNotNull(snap.devices)
    }

    @Test
    fun onlyAnswersFromTheServerAreWorthWriting() {
        val seeded = ConsoleCache.seed(ConsoleState(), ConsoleCache.snapshotOf(loaded()))
        assertNull(ConsoleCache.writeKey(seeded))
        assertNull(ConsoleCache.writeKey(ConsoleState()))
        val refreshed = seeded.copy(devices = Loadable(devices, loadedAt = 9_000))
        assertNotNull(ConsoleCache.writeKey(refreshed))
    }

    @Test
    fun noChangeIsPlannedFromTheCopy() {
        val seeded = ConsoleCache.seed(ConsoleState(), ConsoleCache.snapshotOf(loaded()))
        assertNotNull(seeded.fromDisk(AdminArea.ROUTES))
        assertNotNull(seeded.fromDisk(AdminArea.AUTH_KEYS))
        assertNull("webhooks were never kept", seeded.fromDisk(AdminArea.WEBHOOKS))
        val failed = seeded.copy(dns = seeded.dns.copy(error = RuntimeException("offline")))
        assertNotNull("a failed refresh leaves the copy a copy", failed.fromDisk(AdminArea.DNS))
        assertNull(seeded.copy(devices = Loadable(devices, loadedAt = 9_000)).fromDisk(AdminArea.DEVICES))
    }
}
