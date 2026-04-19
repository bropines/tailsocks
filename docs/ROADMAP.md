# 🗺️ Roadmap & TODOs

This document tracks upcoming features, architectural improvements, and refactoring goals for TailSocks.

## ✅ Recently Completed
- [x] **Taildrop Hub:** Fully custom implementation of file sharing with Inbox/History and SAF integration.
- [x] **Multi-Account Manager:** Profile isolation with independent machine keys and state.
- [x] **Advanced Exit Node Support:** Discovery UI and self-healing validation.
- [x] **Peer Diagnostics:** Deep parsing of `status.json` for NAT traversal and handshake info.
- [x] **SagerNet Integration:** One-click copy for SOCKS5/UDP proxy links.
- [x] **Dynamic Core Integration:** Replaced static branching with dynamic compile-time injection to always use the latest official Tailscale source.
- [x] **Smart DNS Proxy:** Built a local Go DNS server that wraps UDP into TCP via SOCKS5, fixing Split DNS and MagicDNS without VpnService.

## 🧹 Housekeeping & Refactoring
- [x] **Clean Build Process:** Isolated all compiled binaries (`.so`) and libraries (`.aar`) into temporary (`tmp/`) directories.
- [ ] **Code Modernization:** Refactor `MainActivity.kt` to use a dedicated ViewModel for state management instead of hoisting everything in the Composable.

## 🚀 Planned Features
- [ ] **Local Services (`ts serve`):** Implement support for Tailscale Serve to expose local Android services to the Tailnet.
- [ ] **Public Exposure (`funnel`):** Integrate Tailscale Funnel to securely share local Android servers over the public internet.
- [ ] **Quick Settings Tiles:** Add dedicated tiles for toggling Exit Nodes and switching accounts.

## 🏗 Long-Term Architectural Changes
- [ ] **Hybrid Core (`tsnet` + PIE):** Explore using `tsnet` for better Taildrop integration while maintaining the PIE daemon for raw SOCKS5 performance and stability.