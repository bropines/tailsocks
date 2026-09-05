# Project Roadmap

This document outlines the planned features, architectural improvements, and refactoring goals for the TailSocks project.

## Completed Milestones
- [x] **Core Stability:** Passive daemon management and stateless configuration.
- [x] **File Sharing:** Taildrop implementation with Storage Access Framework integration.
- [x] **Profile Isolation:** Multi-account system with independent state persistence.
- [x] **Connectivity:** Custom DNS wrapping and Exit Node support.
- [x] **Tailscale Serve/Funnel:** Native UI for service hosting and public internet exposure.
- [x] **Local API v2:** 100% CLI-less operation via Go-HTTP bridge.
- [x] **System Integration:** Basic Quick Settings Tile for connectivity toggling.
- [x] **Service Monitoring:** Add health checks and request logs for hosted Serve/Funnel services.
- [x] **Search & Filter:** Implement search functionality for Peer list and Logs.
- [x] **Battery Optimization UI:** Add a prompt to help users whitelist the app from system battery restrictions.
- [x] **Account Switching in Tiles:** Enhance Quick Settings to allow switching between profiles.
- [x] **Socks5 proxy to Control Panel:** Added SOCKS5/HTTP proxy support for control plane traffic with atomic patches and netns bypass fix.
- [x] **Encrypted Backups:** Manual app state backups encrypted with a user-defined password (AES-GCM) to protect node keys.
- [x] **Quick Settings Tiles:** Quick Settings Tiles for profile switching and connection state management.
- [x] **R8 minification (4.0.0):** `isMinifyEnabled`/`isShrinkResources` are on for release builds. Gson was replaced by `kotlinx.serialization`, the AppFunctions service constructs the KSP-generated registries directly, and the `verifyReleaseNativeMethods` Gradle task fails the build if R8 drops a JNI method.
- [x] **Root Mode hardening (4.0.0):** dedicated `TAILSOCKS_MARK`/`TAILSOCKS_DNS` chains, masked fwmark `0x1000000/0x1000000`, Check Routing diagnostics and the ROOT log tab.
- [x] **Automation security (4.0.0):** broadcast receiver requires a secret token; AppFunctions (Gemini, Android 16+) actually execute and honour the automation switch.
- [x] **LAN Access, auto-reconnect, background revival and versioned backups (4.0.0).**

## Plans
- [ ] **AI Model Context Protocol (MCP) Server:** Expose an embedded MCP server endpoint (JSON-RPC over SSE / LocalSocket) wrapping `appctr/api.go` LocalAPI, enabling AI assistants (Claude, Cursor, local LLMs) to query status, switch exit nodes, and manage profiles natively.
- [ ] **Native Kotlin LocalAPI Bridge:** Refactor LocalAPI client requests from Go (`appctr`) to direct OkHttp/UnixDomainSocket calls in Kotlin, keeping `libtailscale.so` purely for daemon execution.
- [ ] **Traffic Analyzer & Diagnostics:** Add an in-app real-time connection logger to inspect active Tailnet connections and MagicDNS requests.
- [ ] **Advanced Networking:** Support for custom DERP maps and regional routing overrides.
- [ ] **APK Size Reduction:** Before R8 the arm64 release held 106.9 MB uncompressed — 53.1 MB of dex and 50.2 MB of native code (`libtailscale.so` 23.1, `libtailscale_cli.so` 15.4, `libgojni.so` 11.3). R8 and resource shrinking are now on (see above); the remaining lever:
  - Make the bundled `tailscale` CLI binary optional. It exists for the Root Mode shell wrapper and the legacy `RunTailscaleArgs` path, and costs 15.4 MB of every install.
  - Note: `useLegacyPackaging` cannot be turned off. The daemon is `exec()`d from `nativeLibraryDir`, which requires the libraries to be extracted to disk.
