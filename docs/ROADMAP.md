# Project Roadmap

This document outlines the planned features, architectural improvements, and refactoring goals for the TailSocks project.

## Completed Milestones
- [x] **Core Stability:** Passive daemon management and stateless configuration.
- [x] **File Sharing:** Taildrop implementation with Storage Access Framework integration.
- [x] **Profile Isolation:** Multi-account system with independent state persistence.
- [x] **Connectivity:** Custom DNS wrapping and Exit Node support.

## Short-Term Goals
- **Code Modernization:** Refactor `MainActivity` to utilize `ViewModel` patterns for state management.
- **System Integration:** Implement Quick Settings Tiles for rapid connectivity toggling and account switching.
- **Enhanced Monitoring:** Add real-time throughput graphs to the diagnostics interface.

## Long-Term Goals
- **Tailscale Serve/Funnel:** Implement support for exposing local services to the Tailnet.
- **Architectural Hybridization:** Evaluate the integration of `tsnet` for specific features while maintaining the performance of the standalone daemon.
- **VPN Mode:** Explore optional `VpnService` integration for transparent routing as an alternative to the proxy mode.
