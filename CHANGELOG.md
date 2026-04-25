# Changelog

All notable changes to the TailSocks project will be documented in this file. This project follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) standard.

## [1.9.0] - 2026-04-25
### Added
- **Native Local API Integration:** Replaced major CLI calls (`status`, `netcheck`, `dns query`) with high-performance HTTP requests to the `tailscaled` Unix socket.
- **Control Plane Proxy:** Added global settings to use an external SOCKS5/HTTP proxy for Tailscale coordination server requests. Supports authentication and easy configuration via separate fields (Type, Host, Port, User, Pass).
- **Reactive DNS Engine:** Implemented `IPN Bus Listener` using mask `1032` for real-time `NetMap` and `SplitDNSRoutes` synchronization.
- **Zero-Latency MagicDNS:** In-memory node cache for FQDN and short-name resolution (0ms lookup time).
- **Split DNS over SOCKS5:** Native TCP-wrapped DNS forwarding for corporate domains (e.g., `*.therodev.com`) through the SOCKS5 proxy.
- **Smart DNS Fallback:** Automatic failover to system/DoH resolvers when Tailscale returns `SERVFAIL` or timeouts.
- **Global Settings Architecture:** Major refactoring to separate Global (networking, DNS, system flags) and Profile (auth keys, hostname) configurations. Settings are now unified across all accounts for better UX.
- **Headscale Support:** Restored and improved support for custom control planes. Universal auth URL detection now automatically picks up login links from any domain.

### Fixed
- **Taildrop Collector:** Implemented a background collector that automatically drains the Tailscale incoming file queue via Local API and saves them to the local filesystem. This fixes the issue where received files were not being saved in userspace mode.
- **Universal Auth URL Detection:** Fixed logic to detect login URLs from custom control planes (e.g., Headscale). Previously, the app only looked for tailscale.com domains, preventing authentication on private networks.
- **Diagnostics Reliability:** Fixed panics and Gson parsing errors in `NetcheckActivity`. Implemented "bulletproof" JSON handling for inconsistent Local API responses.
- **Native Netcheck:** Implemented native `GetNetcheckFromAPI` using Go's `netcheck` package with a static `NetMon` to bypass Android permission issues.
- **Proxy Protocol Awareness:** Fixed "unknown Socks version" errors by correctly routing SOCKS5 traffic through `ALL_PROXY` while keeping `HTTP_PROXY` for traditional proxies.
- **Android Netmon Mitigation:** Integrated `TS_NET_STATE` for seamless interface synchronization between Kotlin and Go core.
- **Improved Diagnostics UI:** Added detailed network report (UDP, IPv4/v6, NAT type) and a **Copy to Clipboard** feature for easier debugging.
- Resolved **Panic (nil pointer dereference)** in Taildrop extension when receiving initial NetMap updates.
- Corrected DNS transaction ID mismatch to ensure compatibility with external tools like AdGuard.

## [1.8.1] - 2026-04-19
... (rest of the file)
