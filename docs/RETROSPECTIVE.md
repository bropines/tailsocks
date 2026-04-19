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

**Conclusion:**
Moving away from the PoC "active" model to a professional "passive" bridge has resulted in the most stable build of TailSocks to date, resolving long-standing connectivity and authentication issues.
