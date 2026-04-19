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

### 2. NAT Traversal (`magicsock`)
- **Success Criteria:** `InMagicSock: true` in peer status.
- **Stability:** Frequent `tailscale up` commands (configuration refreshes) reset the engine and break established P2P paths. **Avoid active management loops.**

## ⚠️ Historical Pitfalls & Critical Fixes

### 1. The "410 Gone" Login Wall
- **Cause:** Re-applying settings (ReUp) while the machine was waiting for OIDC registration caused the daemon to cycle its machine key, invalidating the login path.
- **Fix:** `appctr.ApplySettings` now blocks all updates if a `LoginURL` is active. **DO NOT remove this guard.**

### 2. "Sticky" Configurations
- **Problem:** The daemon is stateful. If you stop passing `--exit-node`, it remembers the last one used.
- **Fix:** We use "Stateless Flags". We explicitly pass negative values (e.g., `--exit-node=`, `--accept-routes=false`) to force the internal state machine to clear when a toggle is switched off in the UI.

### 3. UI State Desync (Watchdog)
- **Problem:** Depending only on broadcasts is fragile (Service can be killed).
- **Fix:** A 2-second watchdog in `MainActivity` queries `Appctr.isRunning()` directly. UI state follows process reality, not just intent.

## 🛠 Build Standards
- **Script:** `appctr/build.sh` is the source of truth.
- **Patching:** Core fixes are applied via `appctr/patches/`. Essential patch: `fix_android_netmon.go` (injects `anet`).
- **Stable-only:** The build system filters for stable Tailscale tags to ensure relay/DERP reliability.

## 📋 Standard Diagnostics
When debugging connectivity, check the following fields in the Peer Details modal:
1. `InMagicSock`: Must be `true` for direct P2P.
2. `LastHandshake`: Indicates if WireGuard is actively negotiating.
3. `Relay (DERP)`: If populated, P2P has failed, and traffic is relayed (high latency).
