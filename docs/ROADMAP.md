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

## Plans
- [ ] **AI Model Context Protocol (MCP) Server:** Expose an embedded MCP server endpoint (JSON-RPC over SSE / LocalSocket) wrapping `appctr/api.go` LocalAPI, enabling AI assistants (Claude, Cursor, local LLMs) to query status, switch exit nodes, and manage profiles natively.
- [ ] **Native Kotlin LocalAPI Bridge:** Refactor LocalAPI client requests from Go (`appctr`) to direct OkHttp/UnixDomainSocket calls in Kotlin, keeping `libtailscale.so` purely for daemon execution.
- [ ] **Traffic Analyzer & Diagnostics:** Add an in-app real-time connection logger to inspect active Tailnet connections and MagicDNS requests.
- [ ] **Advanced Networking:** Support for custom DERP maps and regional routing overrides.
- [ ] **APK Size Reduction:** The arm64 release holds 106.9 MB uncompressed — 53.1 MB of dex and 50.2 MB of native code (`libtailscale.so` 23.1, `libtailscale_cli.so` 15.4, `libgojni.so` 11.3). Two levers, in order of payoff:
  - Enable R8 and resource shrinking (`isMinifyEnabled`/`isShrinkResources` are currently off). Requires keep rules for Gson model reflection, the KSP-generated AppFunctions registries and the gomobile bindings, plus on-device verification — a mis-shrunk build fails at runtime, not at compile time.
  - Make the bundled `tailscale` CLI binary optional. It exists for the Root Mode shell wrapper and the legacy `RunTailscaleArgs` path, and costs 15.4 MB of every install.
  - Note: `useLegacyPackaging` cannot be turned off. The daemon is `exec()`d from `nativeLibraryDir`, which requires the libraries to be extracted to disk.
