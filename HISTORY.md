# 📜 TailSocks Development History & Archive

This file tracks architectural decisions, failed attempts, and successful implementations to provide context for future AI agents.

## 🔵 [2026-04-19] Taildrop Hub & Advanced Settings
- **Taildrop Evolution:** Implemented a full-featured Hub with Inbox, Devices, and History tabs.
- **Path Sanitization:** Fixed issues with Russian characters and spaces in file paths by using temp directories with original names and `RunTailscaleArgs`.
- **Global & Account Settings:** Split settings into global (SAF root, auto-start) and per-account (Auth, Proxy, DNS, Flags).
- **SagerNet Integration:** Added a one-click generator/copier for SagerNet-compatible SOCKS5 links.
- **Network Injection:** Implemented `InjectNetworkState` to push Android connectivity events into the Go core for faster handovers.
- **UI Unification:** Created `UIComponents.kt` and `Utils.kt` to consolidate shared logic and design across all activities.

## 🟢 [2026-04-19] Multi-Account & Stability Milestone
- **410 Wall Mitigated:** Identified that `http 410: auth path not found` is a persistent quirk of the Tailscale control plane for Android identities. We "solved" it by ensuring the daemon session survives long enough to pass the backoff period. 
- **Non-Destructive Sync:** Rewrote `ApplySettings` in `appctr`. It now performs incremental updates (`tailscale up`) without restarting the daemon. This allows the 410 error to resolve itself naturally without the session being killed by a `--reset`.
- **UX Feedback:** Added a warning banner for 410 errors. Since we can't eliminate the error, we now explicitly tell the user to wait 1-3 minutes for machine map propagation.
- **Hedgehog Debug Tunnel:** Integrated a modular TCP server (port 4567) for real-time log streaming and remote CLI commands.
- **Account Isolation:** 
    - Created `AccountManager` to handle multiple profiles.
    - Each account is fully isolated with its own `SharedPreferences` (`appctr_{id}`) and state directory (`files/states/{id}`).
    - Added UI for adding, renaming, and "Honest Restart" switching of accounts.

## 🟢 [2026-04-17] Taildrop & Core Monolith
- **Taildrop Enabled:** Successfully enabled Taildrop on Android by bypassing JNI. Used `TS_TAILDROP_DIR` and injected a pure Go filesystem provider (`fileops_fs.go`).
- **Unified Patch System:** Migrated to `appctr/patches/tailsocks.patch` applied via `patch -p0` during build.
- **Taildrop Manager (Files UI):** Created `FilesActivity` to manage the inbox and sent files history.

## 🟢 [2026-04-16] UI & Standalone Transition
- **Standalone Project:** Moved to `bropines/tailsocks`.
- **Dynamic Versioning:** Git-derived `versionCode` and `versionName` in Gradle.
- **Exit Node UI:** Added JSON status parsing for exit node selection.
- **DNS Proxy:** Added configurable Go-based DNS server (port 1053) with SOCKS5 wrapping.
- **IP Randomization:** Switched to full `127.0.0.0/8` range for proxy addresses.

---

## 🚀 Roadmap:
- **Taildrop Upgrades:** Implement file sending capabilities and deep Android Storage Access Framework (SAF) integration.
- **tsnet Investigation:** Evaluate moving to the embedded `tsnet` library for better process control.
- **VpnService:** (Optional) Add a traditional VPN mode while keeping the SOCKS5 "Hedgehog" mode as default.
