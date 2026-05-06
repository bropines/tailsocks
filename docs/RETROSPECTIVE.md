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

**Conclusion:**
Moving away from the PoC "active" model to a professional "passive" bridge and eventually to a native Local API architecture has resulted in the most stable and performant build of TailSocks to date.
