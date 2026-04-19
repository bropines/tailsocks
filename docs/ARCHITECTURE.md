# System Architecture & Technical Implementation

TailSocks provides robust Tailscale connectivity on Android by running the engine in userspace. This document details the technical solutions used to overcome platform-specific limitations.

## 1. Hybrid Communication Bridge
The system is divided into three functional layers:
*   **Tailscale Daemon (Core):** A Position Independent Executable (PIE) shared library (`libtailscale.so`) built from upstream source with specific build tags to exclude non-mobile components (D-Bus, Kubernetes, etc.).
*   **Management Bridge (`appctr`):** A Go-based controller that interacts with the daemon via local unix sockets and exposes a JNI-friendly API to the Android layer.
*   **Kotlin/Compose UI:** The presentation layer that handles user preferences and renders real-time diagnostics.

## 2. Multi-Account Isolation
To support multiple Tailscale profiles on a single device, TailSocks implements strict filesystem and configuration isolation:
*   **State Redirection:** Each account is assigned a unique ID, mapping to an isolated state directory in `files/states/{id}`. This ensures that machine keys and netmap caches never overlap.
*   **Preference Scoping:** Settings are stored in account-specific `SharedPreferences` (`appctr_{id}`).
*   **Process Lifecycle:** A full daemon termination and re-initialization occurs during account switching to prevent cross-account state leakage.

## 3. DNS Implementation (UDP-to-TCP Wrapping)
Standard Android applications cannot route UDP packets into a userspace network map without a TUN interface. TailSocks solves this using a tri-tier resolution logic:
1.  **Local Peer Resolution:** If a query matches a known node in the local netmap, the bridge resolves the IP instantly from memory.
2.  **UDP-to-TCP Proxying:** For internal domains (e.g., `*.ts.net`), the bridge intercepts UDP queries, wraps them into TCP frames, and tunnels them through the SOCKS5 interface to Tailscale’s internal DNS coordinator (`100.100.100.100`).
3.  **Upstream Fallback:** Public queries (e.g., `google.com`) are routed natively to system resolvers or ad-blockers, ensuring zero DNS leaks and maximum performance.

## 4. Taildrop (JNI-less Implementation)
Official Taildrop usually relies on complex system-level integrations. TailSocks implements a standalone manager:
*   **Inbound:** Uses the `TS_TAILDROP_DIR` environment variable to point the core to an app-accessible directory. A background watcher in Go notifies the UI of new files.
*   **Outbound:** Leverages the Android Storage Access Framework (SAF) to read files and streams them directly into the daemon's file-copy logic via the bridge.

## 5. Self-Healing & Stateless Configuration
To mitigate "sticky" routing issues common in userspace engines:
*   **Explicit State Enforcement:** Every `tailscale up` command includes explicit "negation flags" (e.g., `--exit-node=`) to force the daemon to clear previous settings that are no longer selected in the UI.
*   **Health Validation Loop:** A background task periodically validates that the selected Exit Node exists in the current account's netmap, automatically clearing the configuration if the node becomes unreachable or invalid.
