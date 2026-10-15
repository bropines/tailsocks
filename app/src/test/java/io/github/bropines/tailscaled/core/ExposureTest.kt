package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.ExposureGroup.DEVICE
import io.github.bropines.tailscaled.core.ExposureGroup.INTERNET
import io.github.bropines.tailscaled.core.ExposureGroup.LAN
import io.github.bropines.tailscaled.core.ExposureGroup.OFF
import io.github.bropines.tailscaled.core.ExposureGroup.TAILNET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this device exposes, decided from made-up facts (core/Exposure.kt): who reaches each
 * thing, which group it stands in, what the line at the top counts and what the report says.
 */
class ExposureTest {

    /** A fresh install as it runs: proxy mode, the default listeners on loopback, Taildrop, nothing else. */
    private val quiet = ExposureFacts(
        running = true,
        socks5 = "127.0.0.1:48115",
        dns = "127.0.0.1:1053",
        taildriveEnabled = true,
        taildriveShares = listOf("Downloads"),
        taildrivePolicy = TaildriveAccess.Verdict.DENIED,
        taildriveStorage = true,
        serveKnown = true,
        automation = true,
    )

    private fun ExposureFacts.rows() = Exposure.rows(this)
    private fun List<ExposureRow>.of(item: ExposureItem) = single { it.item == item }
    private fun List<ExposureRow>.groupOf(item: ExposureItem) = of(item).group

    @Test
    fun aListenerIsReachedByWhoeverItsAddressLetsIn() {
        val tailnet = setOf(ExposureReach.TAILNET)
        val lan = setOf(ExposureReach.LAN)
        val both = setOf(ExposureReach.LAN, ExposureReach.TAILNET)
        val nobody = emptySet<ExposureReach>()
        // netstack hands the tailnet's connections to 127.0.0.1 only where the port is opened to it.
        assertEquals(nobody, Exposure.listenerReach("127.0.0.1:48115", ExposureNet.NETSTACK))
        assertEquals(tailnet, Exposure.listenerReach("127.0.0.1:48115", ExposureNet.NETSTACK, openToTailnet = true))
        assertEquals(tailnet, Exposure.listenerReach("localhost:8080", ExposureNet.NETSTACK, openToTailnet = true))
        // A real interface never leads to loopback, opened or not.
        assertEquals(nobody, Exposure.listenerReach("127.0.0.1:48115", ExposureNet.INTERFACE))
        assertEquals(nobody, Exposure.listenerReach("127.0.0.1:48115", ExposureNet.INTERFACE, openToTailnet = true))
        // Another loopback address is not dialed at all.
        assertEquals(nobody, Exposure.listenerReach("127.44.12.9:1053", ExposureNet.NETSTACK, openToTailnet = true))
        assertEquals(nobody, Exposure.listenerReach("[::1]:53", ExposureNet.NETSTACK))
        // Every address: the local network; the tailnet with a real interface, or where it is opened to it.
        for (bind in listOf("0.0.0.0:48115", "[::]:48115", ":33445")) {
            assertEquals(both, Exposure.listenerReach(bind, ExposureNet.INTERFACE))
            assertEquals(both, Exposure.listenerReach(bind, ExposureNet.INTERFACE, openToTailnet = true))
            assertEquals(lan, Exposure.listenerReach(bind, ExposureNet.NETSTACK))
            assertEquals(both, Exposure.listenerReach(bind, ExposureNet.NETSTACK, openToTailnet = true))
        }
        assertEquals(tailnet, Exposure.listenerReach("100.101.102.103:80", ExposureNet.INTERFACE))
        assertEquals(tailnet, Exposure.listenerReach("[fd7a:115c:a1e0::1]:80", ExposureNet.INTERFACE))
        // The native engine: the app's answers never go back into the tunnel, so the tailnet reaches none of its listeners.
        for (bind in listOf("0.0.0.0:48115", ":33445")) assertEquals(lan, Exposure.listenerReach(bind, ExposureNet.VPN, openToTailnet = true))
        assertEquals(nobody, Exposure.listenerReach("127.0.0.1:48115", ExposureNet.VPN, openToTailnet = true))
        assertEquals(nobody, Exposure.listenerReach("100.101.102.103:80", ExposureNet.VPN))
        assertEquals(setOf(ExposureReach.LAN), Exposure.listenerReach("192.168.1.20:8080", ExposureNet.NETSTACK))
        // A carrier's 100.x address outside the tailnet's range is a network of its own.
        assertEquals(setOf(ExposureReach.LAN), Exposure.listenerReach("100.200.1.1:80", ExposureNet.NETSTACK))
    }

    @Test
    fun theModeDecidesHowTheDaemonCarriesTheAddress() {
        assertEquals(ExposureNet.NETSTACK, ExposureNet.of(rootMode = false, rootTun = false, tunMode = false, tunEngine = TunEngines.NATIVE))
        assertEquals(ExposureNet.VPN, ExposureNet.of(rootMode = false, rootTun = false, tunMode = true, tunEngine = TunEngines.NATIVE))
        assertEquals(ExposureNet.NETSTACK, ExposureNet.of(rootMode = false, rootTun = false, tunMode = true, tunEngine = TunEngines.HEV))
        assertEquals(ExposureNet.INTERFACE, ExposureNet.of(rootMode = true, rootTun = true, tunMode = false, tunEngine = TunEngines.HEV))
        // Root Mode never uses TUN mode: without its kernel interface the daemon is userspace.
        assertEquals(ExposureNet.NETSTACK, ExposureNet.of(rootMode = true, rootTun = false, tunMode = true, tunEngine = TunEngines.NATIVE))
    }

    @Test
    fun aQuietDeviceKeepsItsProxiesToItself() {
        val rows = quiet.rows()
        // Proxy mode: the daemon no longer hands the tailnet's connections to loopback (patch 23).
        assertEquals(DEVICE, rows.groupOf(ExposureItem.SOCKS5))
        assertEquals(DEVICE, rows.groupOf(ExposureItem.DNS_PROXY))
        assertEquals(TAILNET, rows.groupOf(ExposureItem.TAILDROP))
        // Nor anything else of the device: no other app's port is the tailnet's.
        assertEquals(OFF, rows.groupOf(ExposureItem.DEVICE_PORTS))
        // The policy says no: the folders are set, and nobody opens them.
        val shares = rows.of(ExposureItem.TAILDRIVE_SHARES)
        assertFalse(shares.on)
        assertEquals(ExposureNote.POLICY_DENIED, shares.note)
        // No token: the receiver refuses everything, so it is off and says why.
        val automation = rows.of(ExposureItem.AUTOMATION)
        assertFalse(automation.on)
        assertEquals(ExposureNote.NO_TOKEN, automation.note)
        for (item in listOf(ExposureItem.FUNNEL, ExposureItem.SERVE, ExposureItem.HTTP_PROXY, ExposureItem.WEB_UI, ExposureItem.REMOTE, ExposureItem.EXIT_NODE)) {
            assertEquals(item.name, OFF, rows.groupOf(item))
        }
        // Remote management's levels are not listed while it is off.
        assertTrue(rows.none { it.item == ExposureItem.REMOTE_FULL || it.item == ExposureItem.REMOTE_FILES })
        assertEquals(ExposureSummary(internet = 0, tailnet = 1, lan = 0, device = 2), Exposure.summary(rows))
    }

    @Test
    fun aProxyOpenedToTheTailnetIsTheTailnetsAndItsSwitchClosesIt() {
        val opened = quiet.copy(tailnetOpen = setOf(ExposureItem.SOCKS5))
        val rows = opened.rows()
        val socks5 = rows.of(ExposureItem.SOCKS5)
        assertEquals(TAILNET, socks5.group)
        assertEquals(DEVICE, rows.groupOf(ExposureItem.DNS_PROXY))
        // The row's switch closes it to the tailnet; one never opened has none here.
        assertTrue(Exposure.openedToTailnet(socks5, opened))
        assertTrue(ExposureSwitches.canTurnOff(socks5, opened))
        assertFalse(ExposureSwitches.canTurnOff(rows.of(ExposureItem.DNS_PROXY), opened))
        // On every address it is the local network's too; the group is the wider one.
        val lan = opened.copy(lanAccess = true, socks5 = "0.0.0.0:48115")
        val wide = lan.rows().of(ExposureItem.SOCKS5)
        assertEquals(setOf(ExposureReach.LAN, ExposureReach.TAILNET), wide.reach)
        assertEquals(LAN, wide.group)
        assertTrue(Exposure.openedToTailnet(wide, lan))
        // A real interface does not lead to loopback: the switch opens nothing there.
        for (net in listOf(ExposureNet.VPN, ExposureNet.INTERFACE)) {
            val real = opened.copy(net = net)
            val realSocks5 = real.rows().of(ExposureItem.SOCKS5)
            assertEquals(net.name, DEVICE, realSocks5.group)
            assertFalse(net.name, Exposure.openedToTailnet(realSocks5, real))
        }
        // A proxy that is off is off, opened or not.
        assertEquals(OFF, quiet.copy(tailnetOpen = setOf(ExposureItem.HTTP_PROXY)).rows().groupOf(ExposureItem.HTTP_PROXY))
    }

    @Test
    fun theNativeEngineKeepsLoopbackToThisDevice() {
        val rows = quiet.copy(net = ExposureNet.VPN).rows()
        assertEquals(DEVICE, rows.groupOf(ExposureItem.SOCKS5))
        assertEquals(DEVICE, rows.groupOf(ExposureItem.DNS_PROXY))
        assertEquals(ExposureSummary(internet = 0, tailnet = 2, lan = 0, device = 2), Exposure.summary(rows))
    }

    @Test
    fun localNetworkAccessMovesTheProxiesToTheLocalNetwork() {
        val rows = quiet.copy(lanAccess = true, socks5 = "0.0.0.0:48115", dns = "0.0.0.0:1053", http = "0.0.0.0:8080").rows()
        assertEquals(LAN, rows.groupOf(ExposureItem.LAN_ACCESS))
        assertEquals(LAN, rows.groupOf(ExposureItem.SOCKS5))
        assertEquals(LAN, rows.groupOf(ExposureItem.HTTP_PROXY))
        assertEquals(LAN, rows.groupOf(ExposureItem.DNS_PROXY))
        assertEquals(false, rows.of(ExposureItem.SOCKS5).guarded)
        // In proxy mode the Wi-Fi reaches them and the tailnet does not.
        assertEquals(setOf(ExposureReach.LAN), rows.of(ExposureItem.SOCKS5).reach)
        assertEquals(ExposureSummary(internet = 0, tailnet = 1, lan = 4, device = 0), Exposure.summary(rows))
        // With Root Mode's interface the tailnet reaches every address; counted by group, as the
        // groups' headers count them, what the Wi-Fi reaches is not counted again as the tailnet's.
        val lanAccess = quiet.copy(lanAccess = true, socks5 = "0.0.0.0:48115", dns = "0.0.0.0:1053", http = "0.0.0.0:8080")
        val root = lanAccess.copy(net = ExposureNet.INTERFACE).rows()
        assertEquals(setOf(ExposureReach.LAN, ExposureReach.TAILNET), root.of(ExposureItem.SOCKS5).reach)
        assertEquals(ExposureSummary(internet = 0, tailnet = 2, lan = 4, device = 0), Exposure.summary(root))
        // Under the native engine the app's own listeners are outside the VPN: the Wi-Fi only.
        val native = lanAccess.copy(net = ExposureNet.VPN).rows()
        assertEquals(setOf(ExposureReach.LAN), native.of(ExposureItem.SOCKS5).reach)
        assertEquals(TAILNET, native.groupOf(ExposureItem.DEVICE_PORTS))
    }

    @Test
    fun aBusyDeviceHasThePublicInternetFirst() {
        val busy = quiet.copy(
            served = listOf(
                ServedPort(443, paths = listOf("/", "/api"), funnel = true),
                ServedPort(8080, paths = listOf("/")),
                ServedPort(5432),
                ServedPort(443, service = "web", paths = listOf("/")),
            ),
            remote = true,
            pairings = listOf(
                RemotePairing("n1", "pixel-9-pro", owner = true),
                RemotePairing("n2", "ann-iphone", owner = false, elevatedUntil = 1_791_820_000),
            ),
            remoteFull = true,
            lanAccess = true,
            socks5 = "0.0.0.0:48115",
            socks5Login = true,
            tailcatServer = TailcatServing(anyone = true, keys = 0, ports = listOf(22)),
        )
        val rows = busy.rows()
        assertEquals(listOf(ExposureItem.FUNNEL, ExposureItem.TAILCAT_SERVER), rows.takeWhile { it.group == INTERNET }.map { it.item })
        assertEquals(listOf("443 /, /api"), rows.of(ExposureItem.FUNNEL).names)
        assertEquals(listOf("443 svc:web /", "5432 TCP", "8080 /"), rows.of(ExposureItem.SERVE).names.sorted())
        assertEquals(TAILNET, rows.groupOf(ExposureItem.REMOTE))
        assertEquals(TAILNET, rows.groupOf(ExposureItem.REMOTE_FULL))
        assertEquals(OFF, rows.groupOf(ExposureItem.REMOTE_FILES))
        val elevated = rows.of(ExposureItem.REMOTE_ELEVATED)
        assertEquals("REMOTE_ELEVATED:n2", elevated.key)
        assertEquals(1_791_820_000L, elevated.until)
        assertEquals(LAN, rows.groupOf(ExposureItem.SOCKS5))
        // Groups in the screen's order, the off ones last.
        assertEquals(rows.map { it.group.ordinal }.sorted(), rows.map { it.group.ordinal })
        assertEquals(2, Exposure.summary(rows).internet)
    }

    @Test
    fun shieldsUpClosesTheTailnetAndNothingElse() {
        val rows = quiet.copy(
            shieldsUp = true,
            lanAccess = true,
            socks5 = "0.0.0.0:48115",
            relayPort = 41641,
            tailcatServer = TailcatServing(anyone = false, keys = 2),
        ).rows()
        // Taildrop and the device's ports: nobody reaches them now.
        assertEquals(OFF, rows.groupOf(ExposureItem.TAILDROP))
        assertEquals(ExposureNote.BLOCKED, rows.of(ExposureItem.TAILDROP).note)
        assertEquals(OFF, rows.groupOf(ExposureItem.DEVICE_PORTS))
        // A listener is still this device's, and the Wi-Fi still reaches what listens on every address.
        assertEquals(DEVICE, rows.groupOf(ExposureItem.DNS_PROXY))
        assertEquals(LAN, rows.groupOf(ExposureItem.SOCKS5))
        assertEquals(setOf(ExposureReach.LAN), rows.of(ExposureItem.SOCKS5).effective)
        // Neither the relay's port nor TailCat goes through the packet filter.
        assertEquals(TAILNET, rows.groupOf(ExposureItem.PEER_RELAY))
        assertEquals(INTERNET, rows.groupOf(ExposureItem.TAILCAT_SERVER))
        assertNull(rows.of(ExposureItem.PEER_RELAY).note)
        assertEquals(ExposureSummary(internet = 1, tailnet = 1, lan = 2, device = 1), Exposure.summary(rows))
    }

    @Test
    fun whatOnlyTheDaemonKnowsWaitsForIt() {
        val rows = quiet.copy(running = false, serveKnown = false).rows()
        val serve = rows.of(ExposureItem.SERVE)
        assertFalse(serve.on)
        assertEquals(ExposureNote.NOT_KNOWN, serve.note)
        // Funnel is not called off when nobody could ask.
        assertTrue(rows.none { it.item == ExposureItem.FUNNEL })
    }

    @Test
    fun taildriveSharesNeedStorageAndThePolicy() {
        fun shares(f: ExposureFacts) = f.rows().of(ExposureItem.TAILDRIVE_SHARES)
        val allowed = quiet.copy(taildrivePolicy = TaildriveAccess.Verdict.ALLOWED, taildriveShares = listOf("Downloads", "sdcard"), taildriveWholeStorage = true)
        assertTrue(shares(allowed).on)
        assertEquals(2, shares(allowed).count)
        assertEquals(true, shares(allowed).guarded)
        assertEquals(TAILNET, shares(allowed).group)
        // Not known yet: listed as reachable, and says so.
        val unknown = shares(allowed.copy(taildrivePolicy = TaildriveAccess.Verdict.UNKNOWN))
        assertTrue(unknown.on)
        assertEquals(ExposureNote.POLICY_UNKNOWN, unknown.note)
        assertEquals(ExposureNote.NO_STORAGE, shares(allowed.copy(taildriveStorage = false)).note)
        assertFalse(shares(allowed.copy(taildriveEnabled = false)).on)
        assertFalse(shares(allowed.copy(taildriveShares = emptyList())).on)
    }

    @Test
    fun rootRowsComeWithRootMode() {
        assertTrue(quiet.rows().none { it.item.name.startsWith("ROOT_") })
        val rows = quiet.copy(rootMode = true, rootTun = true, net = ExposureNet.INTERFACE, bootScript = true, cli = null).rows()
        assertEquals(TAILNET, rows.groupOf(ExposureItem.ROOT_KERNEL_TUN))
        assertEquals(DEVICE, rows.groupOf(ExposureItem.ROOT_BOOT_SCRIPT))
        assertEquals(ExposureNote.NOT_CHECKED, rows.of(ExposureItem.ROOT_CLI).note)
        assertEquals(OFF, rows.groupOf(ExposureItem.ROOT_CLI))
    }

    @Test
    fun tailcatIsListedWhereTheDeviceHasIt() {
        assertTrue(quiet.copy(tailcatOffered = false).rows().none { it.item.name.startsWith("TAILCAT") })
        // Running anyway — set up before the device was taken for a television — it is listed.
        val rows = quiet.copy(tailcatOffered = false, tailcatPorts = listOf(8022, 1080)).rows()
        val forwards = rows.of(ExposureItem.TAILCAT_FORWARDS)
        // On 127.0.0.1, which the tailnet no longer reaches.
        assertEquals(DEVICE, forwards.group)
        assertEquals(listOf("1080", "8022"), forwards.names)
    }

    @Test
    fun theReportNamesNothingOfTheTailnet() {
        val text = Exposure.reportText(
            quiet.copy(
                served = listOf(ServedPort(443, service = "secret-svc", paths = listOf("/private"), funnel = true)),
                remote = true,
                pairings = listOf(RemotePairing("nX", "bobs-laptop", owner = false)),
                taildrivePolicy = TaildriveAccess.Verdict.ALLOWED,
                taildriveShares = listOf("Family photos"),
                routes = listOf("192.168.77.0/24"),
                socks5Login = true,
            )
        )
        for (secret in listOf("secret-svc", "/private", "bobs-laptop", "Family photos", "192.168.77.0")) {
            assertFalse("$secret in\n$text", secret in text)
        }
        assertTrue(text, text.startsWith("Exposure (running, net netstack, shields up false):"))
        assertTrue(text, "Widest reach: internet 1, tailnet " in text)
        assertTrue(text, "[internet] Funnel: ports 443" in text)
        assertTrue(text, "[device] SOCKS5: 127.0.0.1:48115, password" in text)
        assertTrue(text, "[tailnet] Remote management: port 28115, paired: 1" in text)
        assertTrue(text, "[tailnet] Taildrive shares: folders: 1" in text)
        assertTrue(text, "[tailnet] Subnet routes advertised: routes: 1" in text)
        assertTrue(text, "Off: " in text && "Automation broadcasts (no token)" in text)
    }

    @Test
    fun serveConfigIsReadIntoPorts() {
        val json = """
            {"TCP":{"443":{"HTTPS":true},"5432":{"TCPForward":"127.0.0.1:5432"}},
             "Web":{"phone.tail1234.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:8080"},"/api":{"Proxy":"http://127.0.0.1:9000"}}}},
             "AllowFunnel":{"phone.tail1234.ts.net:443":true},
             "Services":{"svc:web":{"TCP":{"80":{"HTTP":true}},"Web":{"web.tail1234.ts.net:80":{"Handlers":{"/":{"Text":"hi"}}}}}}}
        """.trimIndent()
        assertEquals(
            listOf(
                ServedPort(443, null, listOf("/", "/api"), funnel = true),
                ServedPort(5432, null, emptyList(), funnel = false),
                ServedPort(80, "web", listOf("/"), funnel = false),
            ),
            ExposureRead.servedPorts(json)
        )
        assertEquals(emptyList<ServedPort>(), ExposureRead.servedPorts("{}"))
        // The bridge's way of saying the daemon could not be asked.
        assertNull(ExposureRead.servedPorts("""{"Error": "not running"}"""))
        assertNull(ExposureRead.servedPorts(""))
    }
}
