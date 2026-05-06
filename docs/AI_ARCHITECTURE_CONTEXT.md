# TailSocks AI Architecture Context (Memory Core)

This document is optimized for AI assistance. It contains the essential technical context, architectural constraints, and historical pitfalls required to develop TailSocks without regression.

## 🏛 Core Architecture: The "Hedgehog Bridge"

TailSocks avoids the Android `VpnService` entirely to allow coexistence with other VPNs (e.g., AdGuard). It operates in `userspace-networking` mode.

### 1. The Execution Hack (PIE Binaries)
- **Constraint:** Android prevents executing binaries from the app's data folder.
- **Solution:** The `tailscaled` daemon is compiled as a PIE (Position Independent Executable) and renamed to `libtailscale.so`.
- **Mechanism:** Android extracts `.so` files from the APK's `jniLibs` into `applicationInfo.nativeLibraryDir` with execute (+x) permissions. We symlink them back to the data folder for path consistency.

### 2. The Communication Layer (`appctr`)
- **Bridge:** A modular Go core (`appctr`) separated into `localapi.go`, `status.go`, `netcheck.go`, `dns.go`, and `taildrop.go` for better maintainability.
- **Lifecycle:** 100% native control via LocalAPI. Spawns `tailscaled` and manages it via Unix socket requests (`/start`, `/logout`, `/prefs`). CLI binary calls are eliminated for standard operations.
- **Serve & Funnel:** Managed via `POST /localapi/v0/serve-config`. 
    - **CRITICAL:** Requires `ETag` synchronization. `GetServeConfig` embeds the ETag into the JSON, and `SetServeConfig` must extract it to populate the `If-Match` header.
- **Event Bus:** Uses `/localapi/v0/watch-ipn-bus?mask=4095` for full real-time network state sync (Peers, NetMap, State).

### 3. Account Isolation
- **Structure:** Each account has a unique ID.
- **FS Isolation:** State is stored in `files/states/{id}/`.
- **Preference Isolation:** Settings are in `appctr_{id}` SharedPreferences.
- **Switching:** Requires a full daemon restart to swap the `--statedir` and machine keys. Exit Nodes are strictly isolated per-profile and force-cleared via `PATCH /prefs` during switching.

## 📡 Networking & DNS

### 1. DNS Wrapping (UDP-to-TCP)
- **Problem:** No `VpnService` means no way to intercept system UDP/53 queries.
- **Solution:** A custom Go DNS server on `127.0.0.1:1053`.
- **MagicDNS:** For internal domains (`*.ts.net`), it wraps UDP queries into TCP frames and tunnels them through the SOCKS5 proxy to `100.100.100.100:53`.
- **Reliability:** `appctr.GetSelfDNSName` provides the official hostname directly from the daemon status for building service links.

### 2. Native Diagnostics (Netcheck)
- **Constraint:** `tailscaled` core cannot access `netlink` on Android 10+.
- **Solution:** Native implementation in `appctr/netcheck.go` using `tailscale.com/net/netcheck` with a `NewStatic` monitor.

### 3. External Control Proxy
- **Constraint:** Android network stack often ignores SOCKS5 environment variables.
- **Best Practice:** Prefer HTTP proxies via `HTTP_PROXY`. If SOCKS5 is used, it's routed via `ALL_PROXY`.
- **CRITICAL:** When using SOCKS, `HTTP_PROXY` and `HTTPS_PROXY` env variables must be explicitly cleared to prevent Go's default behavior of trying HTTP CONNECT (Error 67).

## ⚠️ Historical Pitfalls & Critical Fixes

### 1. The "410 Gone" Login Wall
- **Cause:** Re-applying settings (ReUp) while the machine was waiting for OIDC registration caused the daemon to cycle its machine key, invalidating the login path.
- **Fix:** `appctr.ApplySettings` now blocks all updates if a `LoginURL` is active. **DO NOT remove this guard.**

### 2. "Sticky" Configurations
- **Problem:** The daemon is stateful. If you stop passing `--exit-node`, it remembers the last one used.
- **Fix:** We use "Stateless Flags" and explicit `PATCH` requests to zero-out fields (e.g., `ExitNodeID: "", ExitNodeIP: ""`).

### 3. JNI Stability
- **Guard:** All critical JNI entry points in `appctr` use `recover()` to prevent a Go panic from crashing the host Android JVM.

## 🛠 Engineering Standards & Commit Protocol
- **Atomic Local Commits:** Always perform a `git commit` after every logical code change.
- **Release Strategy:** Major feature updates (like full LocalAPI migration) trigger a minor version bump (e.g., v1.10.0).
