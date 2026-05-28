# Project Evolution & Architectural Retrospective

This document documents the transition from early Proof-of-Concept (PoC) iterations to the current stable architecture, highlighting lessons learned from early design assumptions.

## Phase 1: Stateless Proxy (Early v1.x)
The initial goal was to verify if the Tailscale daemon could run as a Position Independent Executable (PIE) on Android. During this phase, authentication was handled manually via the CLI, and the app functioned as a static SOCKS5 wrapper.

## Phase 2: Active Management (The "Harassment" Model)
As authentication and dynamic settings were integrated, we operated under the assumption that the daemon might lose its internal state due to Android's aggressive battery optimization and network interface flapping.

**The Design Choice:**
To ensure consistency, the application implemented an "Active Management" loop. This loop executed `tailscale up` with full parameters every 10–15 seconds to "force" the daemon into alignment with the UI state.

**Observed Issues:**
This approach introduced several critical instabilities:
*   **Authentication Loops:** Constant re-injection of parameters triggered session resets, leading to frequent `410 Gone` errors.
*   **P2P Disruption:** Frequent configuration updates reset the `magicsock` engine, preventing it from establishing stable direct NAT-traversed connections between peers.
*   **Complexity Overhead:** A significant amount of "helper" logic (fragile delays, broadcast receivers) was required to manage the side effects of this aggressive polling.

## Phase 3: Passive Bridge Architecture (Current)
We realized that the Tailscale daemon is a highly capable state machine that manages its own lifecycle and network recovery. The architecture was shifted to a passive model.

**Key Improvements:**
1.  **Stateless Initialization:** We provide comprehensive configuration flags only during the initial start or explicit settings updates.
2.  **Watchdog Monitoring:** The UI now observes the daemon's state via non-intrusive status queries and a process-check watchdog, rather than trying to control it.
3.  **Isolation Priority:** Stability is achieved through clean filesystem isolation (unique state paths) rather than constant intervention.

**Update (2026-05-06): Full Local API Sovereignty & Serve/Funnel**
The project has reached 100% independence from CLI binary calls for lifecycle management.
- **The ETag Breakthrough:** Discovered that Tailscale's `ServeConfig` API requires strict ETag synchronization via `If-Match` headers. Failure to provide a fresh ETag results in persistent 500 errors, which was resolved by embedding ETag extraction into the Go HTTP transport.
- **Virtual Service Identity:** Successfully implemented Tailscale Services, allowing the Android client to host named virtual nodes (`svc:*`). This proved that the Android PIE daemon is capable of full L7 service advertisement.
- **Proxy limitations on Android:** Confirmed that the Tailscale network stack on Android has deeply ingrained restrictions against SOCKS5 outbound proxies. Even with `netns` patching and `SetProxyFunc` hooks, the daemon prefers native HTTP proxies.
- **Event Bus Maximization:** Moving to Mask `4095` eliminated all "stale state" issues in the UI, as the app now receives immediate delta updates for NetMap and Peers.

**Update (2026-05-28): Taildrive in Userspace-Networking & Netstack Routing**
We successfully achieved full, high-performance Taildrive (WebDAV) integration to list and open files on both the local device and remote peers without Android's `VpnService` (VPN-less mode).
- **The FakeTUN Loopback Barrier:** In userspace mode, self-addressed packets sent to `100.100.100.100:8080` (the virtual WebDAV service) were discarded by the fake TUN device. We bypassed this by implementing loopback intercept routing in `netstack.go` (`inject`), delivering loopback packets directly using `DeliverLoopback`.
- **Remote Peer Dialer Routing:** Browsing remote shares requires the local file manager to query the remote peerapi endpoint (e.g. `100.x.y.z:37350`). Because standard network sockets cannot reach the Tailnet without a system VPN, we routed peer requests in `driveimpl` using `tsdial.Dialer.UserDial`, while isolating local loopback traffic (`127.0.0.1`) on the standard dialer to prevent namespace (`netns`) crashes on Android.
- **Netstack Source IP Selection Fix:** We observed that gVisor netstack, when initiating outbound peer connections without an explicit bind, selected the virtual service IP `100.100.100.100` as the source IP instead of the node's registered Tailscale IP. This caused WireGuard to immediately drop the outbound packets. We solved this by modifying `netstack.go`'s `DialContextTCP` and `DialContextUDP` to automatically find the local Tailscale IP and explicitly bind the outgoing socket to it.

**Conclusion:**
Moving away from the PoC "active" model to a professional "passive" bridge, adopting Local API architecture, and resolving netstack-level routing anomalies has resulted in the most stable, features-complete build of TailSocks to date.

## Appendix: Key Investigations & Debugging
*   **Serve Redirect Persistence:** We found that setting HTTP Redirect handlers via LocalAPI requires strict validation. The daemon rejects Redirect URLs without a scheme (`https://` is now auto-prepended). Additionally, sending conflicting boolean flags (e.g., `HTTPS: true` and `HTTP: false`) caused silent configuration resets. Kotlin models were adjusted to omit inactive flags entirely.
*   **SOCKS5 & SagerNet Export:** To ensure seamless integration with proxy apps like SagerNet, TailSocks dynamically generates standardized `socks5://` URI schemes containing the correct authentication credentials injected at daemon startup via `TS_SOCKS5_USER` and `TS_SOCKS5_PASS`.
*   **Userspace WebDAV Binding & Routing:** We discovered that gVisor's netstack allows multiple addresses on the same NIC. When executing outgoing connections, it defaults to the first available or most recently added address without considering coordinator routing maps. By implementing strict `DialContextTCPWithBind` constraints in `netstack.go`, we enforced routing compliance, achieving zero-packet-drop delivery over userspace WireGuard links.
