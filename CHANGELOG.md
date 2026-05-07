# Changelog

All notable changes to the TailSocks project will be documented in this file. This project follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) standard.

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
