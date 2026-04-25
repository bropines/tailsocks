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

## 4. Native Diagnostics (Netcheck)
*   **Android Limitations:** Permission restrictions (`netlinkrib: permission denied`) prevent the `tailscaled` daemon from identifying network interfaces, leading to failed diagnostics in the core.
*   **Solution:** `appctr` implements a native `GetNetcheckFromAPI` method that runs the `tailscale.com/net/netcheck` package within the application process. 
*   **Interface Synchronization:** Using `TS_NET_STATE` environment variable, the bridge passes interface states (injected from Kotlin) to the core. Netcheck uses a `NewStatic` network monitor to provide accurate STUN/DERP reports bypassing daemon limitations.

## 5. Taildrop & Files API
TailSocks implements a robust file transfer manager:
*   **Inbound:** Uses the `TS_TAILDROP_DIR` environment variable to point the core to an app-accessible directory.
*   **DocumentsProvider:** `TailsocksFileProvider` exposes the app's internal `files` directory to the Android Storage Access Framework (SAF). This allows users to browse Taildrop files using the system "Files" app.
*   **Outbound:** Leverages the SAF to read files and streams them into the daemon's `file-put` API via the bridge, with proper URL path escaping for reliability.

## 6. Self-Healing & Stateless Configuration
To mitigate "sticky" routing issues common in userspace engines:
*   **Explicit State Enforcement:** Every `tailscale up` command includes explicit "negation flags" (e.g., `--exit-node=`) to force the daemon to clear previous settings that are no longer selected in the UI.
*   **Health Validation Loop:** A background task periodically validates that the selected Exit Node exists in the current account's netmap, automatically clearing the configuration if the node becomes unreachable or invalid.
*   **Control Plane Proxy:** Supports global SOCKS5/HTTP proxies for coordination server communication. Specifically routes SOCKS5 traffic through `ALL_PROXY` with forced `HTTP_PROXY` clearing to prevent protocol errors (e.g., "unknown Socks version").
