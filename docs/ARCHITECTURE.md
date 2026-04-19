# 🧠 Architecture & Deep Dive: The Hedgehog Bridge

TailSocks operates entirely in `userspace-networking` mode without utilizing Android's `VpnService`. To make this reliable and feature-complete, we use a hybrid Go-Kotlin architecture known as the **Hedgehog Bridge**.

## 1. The Hedgehog Bridge
The system is split into three distinct layers:
*   **Official Core:** The `tailscaled` daemon is compiled as a Position Independent Executable (PIE) shared library (`libtailscale.so`). We use extensive build tags to strip desktop bloat while preserving core networking logic.
*   **The Go Nanny (`appctr`):** A Go module compiled into an `.aar` via `gomobile bind`. It acts as a controller that spawns and monitors the core process, handles JNI callbacks, and implements custom logic (DNS proxy, Taildrop manager).
*   **Kotlin UI:** A modern Jetpack Compose interface that communicates with the bridge to manage accounts, settings, and real-time diagnostics.

## 2. Multi-Account Isolation
Unlike traditional Tailscale clients, TailSocks supports multiple profiles on a single device:
*   **State Directories:** Each account has its own isolated directory in `files/states/{id}` containing unique machine keys and netmap caches.
*   **Preference Isolation:** Settings are stored in account-specific `SharedPreferences` (`appctr_{id}`).
*   **Clean Transitions:** When switching accounts, the bridge performs a full daemon restart to ensure no state leak between different Tailnets.

## 3. The DNS Routing Masterpiece
Standard Android applications cannot route UDP packets into a userspace network without a TUN interface. TailSocks features a custom-built local DNS server in Go (port 1053) using a tri-tier logic:
*   **Local Netmap Resolution:** If you query a known local node, the proxy extracts the IP directly from memory.
*   **UDP-to-TCP Wrapping (Split DNS):** For internal domains (e.g., `*.ts.net`), the proxy intercepts the UDP query, wraps it into a TCP frame, and pushes it through the SOCKS5 tunnel to Tailscale's internal DNS coordinator (`100.100.100.100`).
*   **External DoH Fallback:** Queries for the public web bypass the Go daemon entirely, routing natively to system filters like AdGuard.

## 4. Taildrop: JNI-less Implementation
Official Taildrop on Android usually requires complex JNI callbacks to handle `VpnService` permissions. TailSocks implements a standalone Taildrop manager:
*   **Environment-Based Storage:** We inject `TS_TAILDROP_DIR` via environment variables to the core.
*   **Incoming Hub:** A background watcher in Go monitors the incoming folder and provides a JSON API for the Kotlin UI.
*   **Outgoing Bridge:** Files are read from Android's Storage Access Framework and streamed to the `tailscale file cp` CLI logic.

## 5. Self-Healing Routing
A common issue in userspace networking is "sticky" routing configurations.
*   **Stateless Flags:** TailSocks explicitly passes negative flags (e.g., `--exit-node=`, `--accept-routes=false`) during the `up` command to force the daemon to clear its internal state when a setting is toggled off.
*   **Exit Node Validation:** A фоновый (background) loop continuously validates that the selected Exit Node exists in the current account's netmap, auto-clearing it if it becomes invalid.