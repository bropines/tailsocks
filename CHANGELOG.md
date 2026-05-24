# Changelog

All notable changes to the TailSocks project will be documented in this file. This project follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) standard.

## [2.0.6-beta] - 2026-05-24
### Added
- Persistent command history in Terminal console stored locally in `console_cmd_history.dat` (decoupled from clear screen action).
- Support for inserting preset commands into terminal input by long-pressing preset buttons.
- Native LocalAPI Let's Encrypt TLS certificate pair export in Serve & Funnel screen.

### Changed
- Moved text wrapping and console clearing buttons to the TopAppBar.
- Removed deprecated Auto-Refresh switch from Global Flags Settings.

### Fixed
- Serve/Funnel HTTP 404 error by replacing wildcard asterisk host key with node's FQDN in serve-config.
- Serve/Funnel HTTP 404 error when exporting TLS certificates by enabling the `/cert` endpoint compilation on Android.

## [2.0.5-beta] - 2026-05-23
### Added
- Active account name display in Quick Settings tile subtitle.
- Account switcher bottom sheet triggered directly from Quick Settings tile settings.
- Battery optimization warning card on main screen with direct settings shortcut.
- TCP-based backend health monitoring in Serve & Funnel screens.
- Serve & Funnel log viewer tab filtering requests.
- Account dropdown selection and refresh button in Share screen.

### Refactored
- Extracted Go HTTP local client and state helpers, removed unused aliases.
- Replaced tailscale up CLI call with LocalAPI /start.

### Fixed
- Version name calculation in Gradle and CI builds by fetching full git history with tags.
- Suffix tolerance in the application update checker version comparison.
- Restricted CI build triggers to execute only on Go, Kotlin, and Actions file changes.

## [2.0.4-beta] - 2026-05-18
### Added
- Quick Exit Node switcher on the main dashboard.
- Forced settings synchronization on Go core startup.
- Detailed system info in log exports.

### Fixed
- Exit Node routing (switched to StableID).
- Consolidated `debug` and `dev` build types.
- Flattened CI artifact structure.

## [2.0.0-beta] - 2026-05-07
### Added
- **Major Serve & Funnel Overhaul**: Completely redesigned the architecture for exposing services.
- **Enhanced Serve UI**: Introduced a chip-based interface for managing handlers, protocols, and host mappings.
- **Wildcard Host Mapping**: Support for `*` hostnames in node-scoped rules.
- **Improved State Management**: Reimplemented LocalAPI synchronization with a two-step "reset-then-apply" pattern to ensure configuration consistency.
- **Auto-generated Links**: The UI now automatically constructs and allows copying of service URLs based on the current configuration.

### Fixed
- **Serve Persistence**: Resolved issues where Serve configurations would desync or fail to persist after daemon restarts.
- **Funnel Visibility**: Fixed a bug preventing Funnel status from being correctly reflected in the UI.
- **TCPPortHandler Serialization**: Corrected the protocol field mapping for raw TCP handlers.

## [1.11.0] - 2026-05-07
### Added
- **Advertise Services on Android**: Enabled support for publishing Tailscale Services (`svc:`) and Serve/Funnel configurations to the coordination server.
- **C2N Protocol**: Re-enabled Client-to-Node (C2N) protocol in the core build to support remote service discovery.
- **Immediate Hostinfo Update**: Added a trigger to update Hostinfo (ServicesHash) immediately after Serve configuration changes via LocalAPI.

### Fixed
- **Android Netmon**: Fixed a potential panic and incorrect IP masking in the network monitoring layer for Android 10+.
- **SOCKS5 Support**: Enabled SOCKS support in the Tailscale core specifically for the Android environment.
- **Taildrop FS**: Improved robustness of Taildrop file operations on Android to avoid JNI panics by using a dedicated Go-based filesystem provider.
- **VIP Service Advertisement (Critical)**: Fixed `SetServeConfig` to send `PATCH /prefs` with `AdvertiseServices` + `AdvertiseServicesSet: true` after every service-scoped configuration update. Previously, the daemon's `vipServicesFromPrefsLocked` had no knowledge of the service from the Prefs side, causing the coordination server to receive an incomplete `/vip-services` response and never activating the VIP DNS entry. This mirrors the exact behavior of the official `tailscale serve --service=svc:*` CLI command.
- **AdvertiseServices Two-Step Sync**: The `AdvertiseServices` PATCH now uses a reset-then-apply pattern (Step A: clear with `[]`; Step B: apply new list), mirroring the same pattern used in `SetServeConfig`. This prevents stale `svc:` entries from persisting across renames or deletions.
- **ServeConfig HostPort Key Inconsistency**: Fixed a bug where node-scoped rules were rendered using `":port"` keys but saved using `"*:port"`, causing them to appear reset to default after a refresh. All keys are now normalized to `"*:port"` for node-scoped and `"fqdn:port"` for service-scoped rules.
- **Robust ServeConfig ETag Handling**: Improved the Go bridge to fetch a fresh ETag via `GET` if the `POST` reset response doesn't provide one, ensuring consistent `If-Match` headers and preventing silent apply failures.
- **ServeConfig Payload Sanitization**: Stripped Tailsocks-specific `etag` field from the JSON payload sent to the daemon. Re-enabled `Services` field transmission as it is required for VIP service advertisement in this version.
- **Serve Rule Edit Logic**: Fixed UI desync in `ServeActivity.kt` where editing a rule would create a duplicate instead of updating the existing one. Added `oldPort` and `oldServiceName` tracking to ensure the previous rule is correctly deleted before applying the update.

### Changed
- **Code Language**: All Russian-language comments across the `appctr/` Go package have been translated to English for consistency and maintainability.


## [1.10.0] - 2026-05-06
### Added
- **Full CLI Elimination:** The application now controls the Tailscale daemon lifecycle exclusively via the native LocalAPI (`/start`, `/logout`, `/prefs`). External binary calls are reduced to 0 for standard operations.
- **Tailscale Serve & Funnel:** Comprehensive UI for exposing local ports to the Tailnet or the public internet. Supports both raw TCP and Web (HTTPS) modes.
- **Tailscale Virtual Services:** Support for creating named services (e.g., `svc:webapp`) with dedicated TailVIPs and DNS names, managed directly from the Android UI.
- **Smart Link Generator:** Integrated DNS resolver in the Serve UI that automatically builds and copies ready-to-use URLs for hosted services, including automatic `https` switching for Funnel.
- **Rule Lifecycle Management:** Added ability to temporarily disable/enable rules without deleting them, and full editing support for existing configurations.
- **Modular Go Core:** Massive refactoring of the `appctr` module. Separated logic into `localapi.go`, `status.go`, `netcheck.go`, `dns.go`, and `taildrop.go` for better maintainability.

### Fixed
- **Optimized Settings Sync:** `ApplySettings` now uses high-speed `PATCH` requests to LocalAPI instead of restarting the configuration via CLI. This results in instant UI responsiveness and cleaner logs.
- **Reliable DNS Sync:** Increased IPN Bus Listener mask to `4095` to capture all network topology changes in real-time.
- **Profile-Specific Exit Nodes:** Fixed "phantom" exit nodes when switching accounts. Settings are now strictly isolated and force-cleared via API during profile transitions.
- **Diagnostics Reliability:** Rewrote `BackendState` detection to use robust JSON parsing, fixing intermittent "Unknown" state errors.
- **Native Logout:** Replaced the legacy CLI reset mechanism with a clean, native API logout dialog.
- **Stability Guard:** Added panic recovery in Go-JNI bridge to prevent the entire Android app from crashing during unexpected network events (e.g., server port scans).

## [1.9.0] - 2026-04-25
### Added
- **Native Local API Integration:** Replaced major CLI calls (`status`, `netcheck`, `dns query`) with high-performance HTTP requests to the `tailscaled` Unix socket.
...
