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
- **Professional & Minimalist Tone:** Use concise, fact-based engineering language. Avoid fluff, unnecessary adjectives, or "pompous" descriptions in logs and documentation.
- **Code Principles:** Strictly adhere to **DRY (Don't Repeat Yourself)**. Prefer surgical updates and shared helper functions over duplicated logic.
- **Atomic Local Commits:** ALWAYS perform a local `git commit -m "..."` after each logical change. Do not wait for the end of the session.
- **Concise Changelog:** Document significant changes in `CHANGELOG.md` using short, direct English sentences. Focus on "What" and "Why" without marketing fluff.
- **Core Patching:** Maintain minimal patches in `appctr/patches/tailsocks.patch` for capabilities not exposed via LocalAPI.
- **Clean Build System:** Use `appctr/build.sh` for core modifications. Never commit compiled binaries.

## 🚀 Quality & UI Goals
- **Compact UI:** Maintain a high-density, no-scroll main dashboard (currently 2x4 grid).
- **Standard UX:** Use `HorizontalPager` for swipeable tabs and `PullToRefreshBox` for all list updates.
- **Data Portability:** Support full app state backups (ZIP) and individual account exports (JSON).
- **Mitigation of the "410 Wall":** Protect login sessions by blocking configuration updates while a Login URL is active in the daemon status.

## 🤖 AI & LLM Guidelines
- **CI/CD Versioning:** Always use `fetch-depth: 0` in checkout actions of the workflow when Gradle build processes are executed. This is required because Gradle uses `git describe` to determine the version code and name.
- **Version Suffix Compatibility:** Version names must follow these formats:
  - Release builds: `v<version>(release)` (e.g. `v2.0.5-beta(release)`)
  - Debug/Dev builds: `v<version>-<6_char_hash>-dev` (e.g. `v2.0.5-beta-081be9-dev`)
  Use `.replace(Regex("[^0-9.]"), "")` inside version comparison code (like `isVersionNewer` in `MainActivity.kt`) to clean version name string before splitting, which prevents crashes or wrong checks with non-numeric suffixes like `(release)`.
- **Russian Translation:** The user prefers communicating in Russian. Respond to user queries in clear, professional Russian, but maintain English for logs and code.
- **Atomic Commits:** Make incremental, atomic Git commits after every logical change, rather than staging all modifications at the end.

TEMP: The user is Russian-speaking, answer him in Russian.
