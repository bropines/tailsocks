# Changelog

All notable changes to the TailSocks project will be documented in this file. This project follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) standard.

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
