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

## Plans
- [ ] **Encrypted Backups:** Manual app state backups encrypted with a user-defined password (AES-GCM) to protect node keys.
- [ ] **Exit Node Switcher Widget & Tiles:** Add a home screen widget and enhance Quick Settings Tiles to display active profile, connection state, and quick exit node toggles.
- [ ] **Traffic Analyzer & Diagnostics:** Add an in-app real-time connection logger to inspect active Tailnet connections and MagicDNS requests.
- [ ] **Advanced Networking:** Support for custom DERP maps and regional routing overrides.
