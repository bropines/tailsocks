# TailSocks AI Architecture Context (Memory Core)

This document is optimized for AI assistance. It contains the essential technical context, architectural constraints, and historical pitfalls required to develop TailSocks without regression.

## 🏛 Core Architecture: The "Hedgehog Bridge"

TailSocks avoids the Android `VpnService` entirely to allow coexistence with other VPNs (e.g., AdGuard). It operates in `userspace-networking` mode.

### 1. The Execution Hack (PIE Binaries)
- **Constraint:** Android prevents executing binaries from the app's data folder.
- **Solution:** The `tailscaled` daemon is compiled as a PIE (Position Independent Executable) and renamed to `libtailscale.so`.
- **Mechanism:** Android extracts `.so` files from the APK's `jniLibs` into `applicationInfo.nativeLibraryDir` with execute (+x) permissions. We symlink them back to the data folder for path consistency.

### 2. The Communication Layer (`appctr`)
- **Bridge:** A Go module (`appctr`) compiled via `gomobile bind` into an `.aar`.
- **Lifecycle:** It spawns `tailscaled` as a subprocess via `os/exec`.
- **API:** It provides a JNI-friendly interface for Kotlin to run CLI commands, manage logs, and control the daemon.

### 3. Account Isolation
- **Structure:** Each account has a unique ID.
- **FS Isolation:** State is stored in `files/states/{id}/`.
- **Preference Isolation:** Settings are in `appctr_{id}` SharedPreferences.
- **Switching:** Requires a full daemon restart to swap the `--statedir` and machine keys.

## 📡 Networking & DNS

### 1. DNS Wrapping (UDP-to-TCP)
- **Problem:** No `VpnService` means no way to intercept system UDP/53 queries.
- **Solution:** A custom Go DNS server on `127.0.0.1:1053`.
- **MagicDNS:** For internal domains (`*.ts.net`), it wraps UDP queries into TCP frames and tunnels them through the SOCKS5 proxy to `100.100.100.100:53`.
- **Upstream:** External queries are bypassed to system resolvers.

### 2. Native Diagnostics (Netcheck)
- **Constraint:** `tailscaled` core cannot access `netlink` on Android 10+, making `netcheck` useless in the core daemon.
- **Solution:** Native implementation in `appctr` using `tailscale.com/net/netcheck` with a `NewStatic` monitor. It uses interface data injected from Kotlin.

### 3. External Control Proxy
- **Format:** `socks5://user:pass@host:port` or `http://...`
- **Routing:** SOCKS5 traffic must be routed via `ALL_PROXY`. 
- **CRITICAL:** When using SOCKS, `HTTP_PROXY` and `HTTPS_PROXY` env variables must be explicitly cleared to prevent Go's default behavior of trying HTTP CONNECT (Error 67).

## ⚠️ Historical Pitfalls & Critical Fixes

### 1. The "410 Gone" Login Wall
- **Cause:** Re-applying settings (ReUp) while the machine was waiting for OIDC registration caused the daemon to cycle its machine key, invalidating the login path.
- **Fix:** `appctr.ApplySettings` now blocks all updates if a `LoginURL` is active. **DO NOT remove this guard.**

### 2. "Sticky" Configurations
- **Problem:** The daemon is stateful. If you stop passing `--exit-node`, it remembers the last one used.
- **Fix:** We use "Stateless Flags". We explicitly pass negative values (e.g., `--exit-node=`, `--accept-routes=false`) to force the internal state machine to clear when a toggle is switched off in the UI.

### 3. Taildrop Pathing
- **API Change:** Use `/localapi/v0/files` (no trailing slash) to get the file list.
- **Data Enrichment:** The Local API doesn't return file paths. `appctr` must manually join `TS_TAILDROP_DIR` and the filename to provide valid paths to Kotlin.

## ⚙️ Intentional Platform Adaptation (Essential Hacks)

### 1. Manual DNS Proxy (Port 1053)
- **Bootstrap:** It allows name resolution to function *before* the system fully recognizes the userspace tunnel.

### 2. Network State Injection (`InjectNetworkState`)
- **Purpose:** Feeding the `netcheck` engine and `netmon` enough data to function in a restricted environment.

### 3. DocumentsProvider (`TailsocksFileProvider`)
- **Purpose:** Bridges the app's private storage to the system Files app, enabling manual Taildrop file management.

## 🛠 Engineering Standards & Commit Protocol
- **Atomic Local Commits:** Always perform a `git commit` after every logical code change.
- **Push Policy:** Only `git push` when explicitly requested by the user.
