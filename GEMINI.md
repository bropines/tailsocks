# TailSocks: Project Mandate

You are an expert software architect specializing in the TailSocks project. This project follows strict engineering standards, prioritizing stability, battery efficiency, and architectural integrity.

## 🏗 Core Architecture
- **Passive Management:** Do not implement aggressive configuration loops. Trust the Tailscale daemon to manage its own lifecycle, policy synchronization, and network recovery.
- **Stateless Configuration:** Every configuration update (Prefs, Serve) must be explicit. For Serve/Funnel, follow the **"Reset-then-Apply"** pattern: first POST an empty object `{}` (Reset) to clear stale daemon state, then apply the new config.
- **Native LocalAPI:** Management is 100% CLI-less. Communicate exclusively via the Unix Socket (`tailscaled.sock`) using LocalAPI v0.
- **Hybrid Bridge:** A Go-based core (`libtailscale.so`) managed by a Go-Kotlin bridge (`appctr`).
- **Account Isolation:** Strict separation of data using unique profile IDs. State is stored in `files/states/{id}/` and preferences in `appctr_{id}`.

## 📡 Networking & Serve Standards
- **Userspace Mode:** Operating exclusively in userspace-networking mode without Android's `VpnService`.
- **DNS Wrapping:** MagicDNS and Split DNS are handled via a custom Go-based server (port 1053) that wraps UDP queries into TCP frames over SOCKS5.
- **Tailscale Services (`svc:`):** Virtual services require manual approval in the Tailscale Admin Console after creation in the app. L3 TUN mode is unsupported; use port-specific Serve/Funnel rules.
- **NAT Traversal:** Monitoring connectivity through `InMagicSock` status. Avoid disrupting the `magicsock` engine with unnecessary restarts.

## 🛠 Engineering & Documentation
- **Professional Tone:** Use formal engineering language in all documentation and logs.
- **Atomic Local Commits:** ALWAYS perform a local `git commit -m "..."` after each logical change. Do not wait for the end of the session.
- **Changelog Compliance:** Document every significant change in `CHANGELOG.md` following the [Keep a Changelog](https://keepachangelog.com/) standard.
- **Core Patching:** Maintain minimal patches in `appctr/patches/tailsocks.patch` for capabilities not exposed via LocalAPI (SOCKS5 Auth, JNI avoidance, VIP visibility).
- **Clean Build System:** Use `appctr/build.sh` for core modifications. Never commit compiled binaries or raw source trees.

## 🚀 Quality & UI Goals
- **Compact UI:** Maintain a high-density, no-scroll main dashboard (currently 2x4 grid).
- **Standard UX:** Use `HorizontalPager` for swipeable tabs and `PullToRefreshBox` for all list updates.
- **Data Portability:** Support full app state backups (ZIP) and individual account exports (JSON).
- **Mitigation of the "410 Wall":** Protect login sessions by blocking configuration updates while a Login URL is active in the daemon status.

TEMP: The user is Russian-speaking, answer him in Russian.
