package io.github.bropines.tailscaled.admin.attention

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionTest {

    private val now = Rfc3339.millis("2026-10-09T12:00:00Z")!!

    private fun device(
        id: String,
        authorized: Boolean? = true,
        expires: String? = "2027-03-01T00:00:00Z",
        shared: Boolean = false,
        keyExpiryDisabled: Boolean? = false,
        ephemeral: Boolean? = null,
        update: Boolean? = null,
        lockError: String? = null,
        multiple: Boolean? = null,
        advertised: List<String>? = null,
        enabled: List<String>? = null,
    ) = ApiDevice(
        nodeId = id, name = "$id.tail1234.ts.net", authorized = authorized, expires = expires, isExternal = shared,
        keyExpiryDisabled = keyExpiryDisabled, isEphemeral = ephemeral, updateAvailable = update, tailnetLockError = lockError,
        multipleConnections = multiple, advertisedRoutes = advertised, enabledRoutes = enabled,
    )

    private fun kinds(input: AttentionInput) = Attention.compute(input, now).map { it.kind to it.targetId }

    @Test
    fun devicesAndUsersWaitingForApproval() {
        val items = kinds(
            AttentionInput(
                devices = listOf(device("nNEW", authorized = false), device("nSHARED", authorized = false, shared = true), device("nOK")),
                users = listOf(
                    ApiUser("uPENDING", loginName = "jordan@example.com", status = "needs-approval"),
                    ApiUser("uACTIVE", loginName = "alex@example.com", status = "active"),
                ),
            )
        )
        assertEquals(listOf(AttentionKind.DEVICE_APPROVAL to "nNEW", AttentionKind.USER_APPROVAL to "uPENDING"), items)
    }

    @Test
    fun deviceKeysWithinTheWindowEitherWay() {
        val items = Attention.compute(
            AttentionInput(
                devices = listOf(
                    device("nSOON", expires = "2026-10-12T12:00:00Z"),
                    device("nLATER", expires = "2026-10-20T12:00:00Z"),
                    device("nJUSTGONE", expires = "2026-10-07T12:00:00.123456Z"),
                    device("nLONGGONE", expires = "2026-08-01T00:00:00Z"),
                    device("nNOEXPIRY", expires = "2026-10-10T00:00:00Z", keyExpiryDisabled = true),
                    device("nEPHEMERAL", expires = "2026-10-10T00:00:00Z", ephemeral = true),
                    device("nPENDING", expires = "2026-10-10T00:00:00Z", authorized = false),
                    device("nSHARED", expires = "2026-10-10T00:00:00Z", shared = true),
                    device("nZERO", expires = "0001-01-01T00:00:00Z"),
                )
            ),
            now,
        )
        val expiring = items.filter { it.kind == AttentionKind.DEVICE_KEY_EXPIRING }
        // The one that already ran out sorts first: it is the most urgent.
        assertEquals(listOf("nJUSTGONE", "nSOON"), expiring.map { it.targetId })
        assertTrue(expiring[0].expiresAt!! < now)
        assertEquals("DEVICE_KEY_EXPIRING:nSOON:2026-10-12T12:00:00Z", expiring[1].notifyKey)
        assertEquals(listOf(AttentionKind.DEVICE_APPROVAL), items.filter { it.targetId == "nPENDING" }.map { it.kind })
    }

    @Test
    fun authKeysButNotTheConsolesOwnOrDeadOnes() {
        val keys = listOf(
            ApiKey(id = "kSOON", keyType = "auth", description = "ci runners", expires = "2026-10-11T00:00:00Z"),
            ApiKey(id = "kFAR", keyType = "auth", expires = "2026-12-01T00:00:00Z"),
            ApiKey(id = "kREVOKED", keyType = "auth", expires = "2026-10-11T00:00:00Z", revoked = "2026-10-01T00:00:00Z"),
            ApiKey(id = "kINVALID", keyType = "auth", expires = "2026-10-11T00:00:00Z", invalid = true),
            ApiKey(id = "kGONE", keyType = "auth", expires = "2026-10-01T00:00:00Z"),
            ApiKey(id = "kOTHERTOKEN", keyType = "api", expires = "2026-10-11T00:00:00Z"),
            ApiKey(id = "kOWN", keyType = "api", expires = "2026-10-13T00:00:00Z"),
        )
        val items = Attention.compute(AttentionInput(keys = keys, ownKeyId = "kOWN"), now)
        assertEquals(
            listOf(AttentionKind.CREDENTIAL_EXPIRING to "kOWN", AttentionKind.AUTH_KEY_EXPIRING to "kSOON"),
            items.map { it.kind to it.targetId },
        )
        assertEquals("ci runners", items[1].targetName)
    }

    @Test
    fun theConsolesCredential() {
        assertEquals(
            listOf(AttentionKind.CREDENTIAL_EXPIRING to "kOWN"),
            kinds(AttentionInput(ownKeyId = "kOWN", credentialExpires = "2026-10-14T00:00:00Z")),
        )
        assertEquals(emptyList<Any>(), kinds(AttentionInput(ownKeyId = "kOWN", credentialExpires = "2026-11-30T00:00:00Z")))
        // An OAuth client never expires.
        assertEquals(emptyList<Any>(), kinds(AttentionInput(ownKeyId = "kCLIENT")))
        // Refused: that is the item, not the expiry it may also have.
        assertEquals(
            listOf(AttentionKind.CREDENTIAL_REFUSED to "kOWN"),
            kinds(AttentionInput(ownKeyId = "kOWN", credentialExpires = "2026-10-14T00:00:00Z", credentialRefused = true)),
        )
    }

    @Test
    fun routesWaitingFromTheirOwnReadOrTheDevice() {
        val router = device("nROUTER")
        val items = Attention.compute(
            AttentionInput(
                devices = listOf(
                    router,
                    device("nDONE"),
                    device("nFIELDS", advertised = listOf("10.1.0.0/16"), enabled = emptyList()),
                    device("nSHARED", shared = true, advertised = listOf("10.2.0.0/16")),
                ),
                routes = mapOf(
                    "nROUTER" to DeviceRoutes(listOf("192.168.1.0/24", "0.0.0.0/0", "::/0"), listOf("192.168.1.0/24")),
                    "nDONE" to DeviceRoutes(listOf("10.0.0.0/8"), listOf("10.0.0.0/8")),
                ),
            ),
            now,
        ).filter { it.kind == AttentionKind.ROUTES_PENDING }
        assertEquals(listOf("nFIELDS", "nROUTER"), items.map { it.targetId })
        val r = items.single { it.targetId == "nROUTER" }
        assertEquals(listOf("0.0.0.0/0", "::/0"), r.pendingRoutes)
        assertEquals(listOf("192.168.1.0/24"), r.enabledRoutes)
        assertTrue(Attention.hasExitRoute(r.pendingRoutes))
    }

    @Test
    fun problemsAndUpdates() {
        val items = kinds(
            AttentionInput(
                devices = listOf(
                    device("nLOCK", lockError = "not signed"),
                    device("nCOPY", multiple = true),
                    device("nOLD", update = true),
                    device("nSHAREDOLD", update = true, shared = true),
                    device("nSHAREDCOPY", multiple = true, shared = true),
                )
            )
        )
        assertEquals(
            listOf(
                AttentionKind.TAILNET_LOCK_ERROR to "nLOCK",
                AttentionKind.MULTIPLE_CONNECTIONS to "nCOPY",
                AttentionKind.UPDATE_AVAILABLE to "nOLD",
            ),
            items,
        )
    }

    @Test
    fun whatWasNotReadIsNotReportedAsCalm() {
        assertEquals(emptyList<Any>(), kinds(AttentionInput()))
        val onlyDevices = kinds(AttentionInput(devices = listOf(device("nNEW", authorized = false)), users = null, keys = null))
        assertEquals(listOf(AttentionKind.DEVICE_APPROVAL to "nNEW"), onlyDevices)
    }

    @Test
    fun sectionsInOrderOfUrgency() {
        val items = Attention.compute(
            AttentionInput(
                devices = listOf(device("nOLD", update = true), device("nSOON", expires = "2026-10-10T00:00:00Z"), device("nNEW", authorized = false)),
                users = listOf(ApiUser("uPENDING", loginName = "a@example.com", status = "needs-approval")),
                ownKeyId = "kOWN",
                credentialExpires = "2026-10-12T00:00:00Z",
            ),
            now,
        )
        assertEquals(
            listOf(
                AttentionSection.APPROVALS, AttentionSection.APPROVALS, AttentionSection.CREDENTIAL,
                AttentionSection.EXPIRY, AttentionSection.UPDATES,
            ),
            items.map { it.kind.section },
        )
        assertEquals("DEVICE_APPROVAL:nNEW", items[0].notifyKey)
    }

    @Test
    fun rfc3339AsTheApiWritesIt() {
        assertEquals(now, Rfc3339.millis("2026-10-09T12:00:00Z"))
        assertEquals(now, Rfc3339.millis("2026-10-09T12:00:00.999999999Z"))
        assertEquals(now, Rfc3339.millis("2026-10-09T14:00:00+02:00"))
        assertNull(Rfc3339.millis("0001-01-01T00:00:00Z"))
        assertNull(Rfc3339.millis(""))
        assertNull(Rfc3339.millis("yesterday"))
    }
}
