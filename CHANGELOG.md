# Changelog

All notable changes to the TailSocks project will be documented in this file. This project follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) standard.

## [1.9.1] - 2026-04-25
### Added
- **Control Plane Proxy:** Added global settings to use an external SOCKS5/HTTP proxy for Tailscale coordination server requests. Supports authentication and easy configuration via separate fields (Type, Host, Port, User, Pass).
- **Proxy Protocol Awareness:** Fixed "unknown Socks version" errors by correctly routing SOCKS5 traffic through `ALL_PROXY` while keeping `HTTP_PROXY` for traditional proxies.
### Fixed
- **Diagnostics Reliability:** Fixed panics and Gson parsing errors in `NetcheckActivity`. Implemented "bulletproof" JSON handling for inconsistent Local API responses.
- **Native Netcheck:** Implemented native `GetNetcheckFromAPI` using Go's `netcheck` package with a static `NetMon` to bypass Android permission issues.
- **Taildrop Collector:** Implemented a background collector that automatically drains the Tailscale incoming file queue via Local API and saves them to the local filesystem. This fixes the issue where received files were not being saved in userspace mode.
- **Android Netmon Mitigation:** Integrated `TS_NET_STATE` for seamless interface synchronization between Kotlin and Go core.
- **Improved Diagnostics UI:** Added detailed network report (UDP, IPv4/v6, NAT type) and a **Copy to Clipboard** feature for easier debugging.

## [1.9.0] - 2026-04-25
### Added
- **Native Local API Integration:** Replaced major CLI calls (`status`, `netcheck`, `dns query`) with high-performance HTTP requests to the `tailscaled` Unix socket.
- **Reactive DNS Engine:** Implemented `IPN Bus Listener` using mask `1032` for real-time `NetMap` and `SplitDNSRoutes` synchronization.
- **Zero-Latency MagicDNS:** In-memory node cache for FQDN and short-name resolution (0ms lookup time).
- **Split DNS over SOCKS5:** Native TCP-wrapped DNS forwarding for corporate domains (e.g., `*.therodev.com`) through the SOCKS5 proxy.
- **Smart DNS Fallback:** Automatic failover to system/DoH resolvers when Tailscale returns `SERVFAIL` or timeouts.

### Fixed
- Resolved **Panic (nil pointer dereference)** in Taildrop extension when receiving initial NetMap updates.
- Fixed `actualRunning` reference error in `MainActivity` watchdog.
- Corrected DNS transaction ID mismatch to ensure compatibility with external tools like AdGuard.
- Improved name normalization for node resolution (case-insensitive and dot-stripping).

## [1.8.1] - 2026-04-19
### Changed
- Migrated to a **Passive Management Model**: Removed aggressive configuration loops in favor of daemon-led state management.
- Standardized SOCKS5 default port and endpoint configurations.
- Disabled `Auto-Refresh` by default to improve battery efficiency.

### Fixed
- Resolved `410 Gone` authentication errors by implementing login session protection in the Go bridge.
- Fixed UI state synchronization issues using a direct process watchdog in `MainActivity`.
- Stabilized account switching with a centralized `RESTART_ACTION` in the background service.

## [1.8.0] - 2026-04-19
### Added
- **Taildrop Hub:** Fully custom file-sharing implementation with background reception and SAF support.
- **Multi-Account Manager:** Profile isolation via independent state directories and machine keys.
- **Deep Diagnostics:** Real-time visibility for `InMagicSock`, traffic counters, and WireGuard telemetry.
- **Exit Node Integration:** Dedicated selector UI with automated validation and "Self-Healing" logic.
- **Third-Party Integration:** SagerNet/Nekobox SOCKS5 URI generator.

### Changed
- Enforced stable Tailscale core v1.96.x.
- Implemented stateless configuration flow to ensure internal daemon state matches UI flags.

## [1.7.0] - 2026-04-17
### Added
- Initial support for Tailscale authentication and CLI integration.
- Custom DNS Proxy for MagicDNS resolution over SOCKS5.
- Background Service implementation for daemon persistence.

## [1.6.0] - 2026-04-15
### Added
- Initial stable PIE-binary execution on Android ARM64.
- SOCKS5 proxy exposure.
