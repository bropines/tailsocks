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

## 3. Native DNS Engine & Reactive Sync
Standard Android applications cannot route UDP packets into a userspace network map without a TUN interface. TailSocks solves this using a reactive, multi-stage resolution logic:
1.  **IPN Bus Synchronization:** The Go bridge maintains a persistent connection to the daemon's internal notification bus (`mask=1032`). This allows real-time tracking of `NetMap` changes, MagicDNS suffixes, and `SplitDNSRoutes`.
2.  **In-Memory Peer Resolution:** All nodes in the network are cached by their FQDN and short names. Resolution of `*.ts.net` names occurs in **0ms** by querying the internal memory map directly.
3.  **Split DNS (TCP-over-SOCKS5):** For domains matching corporate routes (e.g., `therodev.com`), the bridge wraps UDP queries into TCP frames and tunnels them via SOCKS5 to the specific internal resolver IP provided by the netmap.
4.  **Smart Upstream Fallback:** Public queries (e.g., `google.com`) are attempted via Tailscale's `dns-query` API first. If the daemon returns a `SERVFAIL` (common in userspace-only mode), the bridge automatically falls back to user-configured system/DoH resolvers.

## 4. Taildrop (JNI-less Implementation)
Official Taildrop usually relies on complex system-level integrations. TailSocks implements a standalone manager:
*   **Inbound:** Uses the `TS_TAILDROP_DIR` environment variable to point the core to an app-accessible directory. A background watcher in Go notifies the UI of new files.
*   **Outbound:** Leverages the Android Storage Access Framework (SAF) to read files and streams them directly into the daemon's file-copy logic via the bridge.

## 5. Self-Healing & Stateless Configuration
To mitigate "sticky" routing issues common in userspace engines:
*   **Explicit State Enforcement:** Every `tailscale up` command includes explicit "negation flags" (e.g., `--exit-node=`) to force the daemon to clear previous settings that are no longer selected in the UI.
*   **Health Validation Loop:** A background task periodically validates that the selected Exit Node exists in the current account's netmap, automatically clearing the configuration if the node becomes unreachable or invalid.
