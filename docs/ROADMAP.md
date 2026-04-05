# 🗺️ Roadmap & TODOs

This document tracks upcoming features, architectural improvements, and refactoring goals for TailSocks.

## ✅ Recently Completed
- [x] **Dynamic Core Integration:** Replaced static branching with dynamic compile-time injection to always use the latest official Tailscale source.
- [x] **Smart DNS Proxy:** Built a local Go DNS server that wraps UDP into TCP via SOCKS5, fixing Split DNS and MagicDNS without VpnService.
- [x] **Extreme Binary Optimization:** Removed bloatware (Systray, AWS, SSH, Taildrop) via Go build tags.
- [x] **Web UI:** Implemented seamless access to the local Tailscale web administrator interface.

## 🧹 Housekeeping & Refactoring
- [ ] **Clean Build Process:** Isolate all compiled binaries (`.so`) and libraries (`.aar`) into temporary (`tmp/`) directories during the build scripts to keep the working tree clean and prevent accidental commits of large binaries.

## 🚀 Planned Features
- [ ] **Local Services (`ts serve`):** Implement support for Tailscale Serve. This will allow users to easily expose local Android services (like Termux web servers, local API testing, or file servers) directly to their Tailnet.
- [ ] **Public Exposure (`funnel`):** Integrate Tailscale Funnel to securely share local Android servers over the public internet using Tailscale's relay infrastructure, without exposing the entire device.

## 🏗 Long-Term Architectural Changes
- [ ] **Migrate to `tsnet`:** Abandon the `fork/exec` PIE binary approach and migrate the core to an in-process execution using `tailscale.com/tsnet`. This is required to safely pass the `JNIEnv` and resolve crashes related to `VpnService` and Taildrop.