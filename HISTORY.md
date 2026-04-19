# 📜 TailSocks Development History & Archive

This file tracks architectural decisions, failed attempts, and successful implementations to provide context for future AI agents.

## 🟣 [2026-04-19] v1.8.0: Diagnostics & Self-Healing
- **Expanded Peer Diagnostics:** Completely rewrote the peer parser to extract deep technical data from `status.json`. Added visibility for `InMagicSock` (NAT traversal), `LastHandshake`, `LastWrite`, and real-time RX/TX traffic counters.
- **Stateless Configuration Flow:** Fixed the "Sticky Flags" bug where the daemon would remember old settings (like an old Exit Node) even after they were toggled off in the UI. We now explicitly pass negative flags (e.g., `--exit-node=`, `--accept-routes=false`) during the `up` command.
- **Exit Node Self-Healing:** Implemented a background validation loop that monitors the netmap. If the selected Exit Node becomes invalid (e.g., node went offline or account was switched), the app automatically clears the setting and notifies the user via Toast.
- **Forced Stable Core:** Updated `build.sh` to strictly filter for stable Tailscale tags, preventing unstable `dev` or `pre` builds from being pulled into the release. Settled on `v1.96.4` as the current baseline.
- **Log Management:** Suppressed routine `dns`, `netcheck`, and `ping` commands from spamming Logcat.
- **Tag-Based Releases:** Established a `v1.x.x` tagging convention to trigger automated GitHub Actions builds.

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
