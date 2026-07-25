# TailSocks: Developer Agent Guidelines & Project Mandate

This document contains mandatory rules, architectural principles, engineering standards, and practical instructions for any AI coding assistant (agent) or human developer working with the **TailSocks** repository.

---

## 🏗️ Core Architecture & Tech Stack

TailSocks is a hybrid, multi-layer Android client for Tailscale operating in **userspace-networking mode**:

1. **Core (Go Daemon)**: Patched `tailscaled` daemon (v1.98.3+), compiled as the native shared library `libtailscale.so` (PIE).
2. **Bridge (Go/Gomobile)**: The `appctr` module provides JNI bindings (`appctr.Appctr` in Kotlin) and handles LocalAPI v0 communication over Unix Domain Sockets (`tailscaled.sock`).
3. **UI (Kotlin/Compose)**: High-density, no-scroll Material 3 dashboard implemented in Jetpack Compose.
4. **TUN Engine (C)**: System-wide transparent VPN routing powered by the native `hev-socks5-tunnel` library (optional VPN mode).
5. **DPI Bypass (JNI/C)**: Native JNI ByeDPI implementation binding to a randomized loopback IP/port in `127.0.0.0/8` upon startup to bypass Control Plane SNI-based DPI.

### Fundamental Architectural Rules:
* **CLI-less Daemon Management**: Management is 100% CLI-less. Communicate exclusively via the Unix Socket (`tailscaled.sock`) using LocalAPI v0. Do not execute shell commands or wrap CLI binaries unless recovering from process lock.
* **Passive Management**: Trust the Tailscale daemon to manage its own lifecycle, policy synchronization, and connectivity recovery. Do not write aggressive, high-frequency configuration loops or watchdog restarts.
* **Stateless Configuration (Reset-then-Apply)**: Every configuration update (Prefs, Serve/Funnel, Virtual Services) must be explicit. For Serve/Funnel, first POST an empty object `{}` to `/localapi/v0/serve/config` (Reset) to clear stale daemon state, then apply the new configuration.
* **Account Isolation**: Strict filesystem and preference separation per profile. Independent accounts store state in `/files/states/{id}/` and preferences in `appctr_{id}`. Full daemon restart and re-initialization is required on profile switch.
* **Mitigation of the "410 Wall"**: Block all configuration updates while a Login URL is active in the daemon status to prevent invalidating authentication sessions.

---

## 📡 Networking, DNS & Serve Standards

* **Userspace Mode:** Operates natively in userspace-networking mode without requiring Android's `VpnService` permission, enabling coexistence with system ad-blockers and other VPN apps.
* **DNS Wrapping:** MagicDNS and Split DNS resolution are handled via a custom Go-based DNS server (port 1053) that wraps UDP queries into TCP-over-SOCKS5 frames to bypass Android network restrictions. Fallback chain: SOCKS5 UDP → Direct UDP → DoH.
* **Tailscale Services (`svc:`):** Virtual services host named endpoints (e.g. `svc:name`). They require manual approval in the Tailscale Admin Console after creation in the app. L3 TUN mode for virtual IPs is unsupported; use port-specific Serve/Funnel rules.
* **NAT Traversal:** Monitor NAT connectivity through real-time `InMagicSock` status notifications. Avoid disrupting the `magicsock` engine with unnecessary restarts.

---

## 🛠️ Build System, Compilation & Patch Pipeline

Modifications to native assets or core logic require executing the build pipeline:

### 1. Compile the Go Core (`appctr`)
When Go code, bridge JNI bindings, or Tailscale patches are modified:
```bash
cd appctr
bash build.sh
```
*   **Dynamic Patch Pipeline**: `build.sh` downloads clean Tailscale source code, applies 11 atomic patches from `appctr/patches/` in alphabetical order (`01-enable-socks-android` through `11-noop-dns-fallback`), and cross-compiles native `.so` binaries for 4 target architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
*   **Patch Management**: If code in `appctr/tailscale_src/` is modified, recreate the atomic patch files before committing:
    ```bash
    ./appctr/patches/recreate_patches.sh
    ```
    This diffs changes against clean sources in `appctr/orig/`. Never commit compiled binary `.so` files or unpatched sources.

### 2. Compile the Android APK
```bash
# Release Build
./gradlew app:assembleRelease

# Debug/Dev Build
./gradlew app:assembleDebug
```

---

## 📂 Project Layout

* [`app/src/main/java/io/github/bropines/tailscaled/`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/) — Kotlin Android source code.
  * [`admin/`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/admin/) — Device & network management via Tailscale Admin API.
  * [`core/`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/) — Background services, VPN tunnel, ByeDPI JNI, account storage, tasker receiver.
  * [`ui/`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/ui/) — Jetpack Compose dashboards, settings, serve UI, and dialogs.
* [`appctr/`](file:///Users/pinus/projects/tailsocks/appctr/) — Go Gomobile bridge and core engine.
  * [`patches/`](file:///Users/pinus/projects/tailsocks/appctr/patches/) — Atomic Tailscale patch files (`01-*.patch` .. `11-*.patch`).
  * [`tailscale_src/`](file:///Users/pinus/projects/tailsocks/appctr/tailscale_src/) — Active patched Tailscale source copy (git-ignored).
  * [`orig/`](file:///Users/pinus/projects/tailsocks/appctr/orig/) — Original clean Tailscale sources (git-ignored).

---

## 🚀 UI Guidelines & Quality Standards

* **Compact Dashboard**: Maintain a high-density, no-scroll main dashboard (2×4 grid design).
* **Standard UX Patterns**: Use `HorizontalPager` for swipeable tabs and `PullToRefreshBox` for list updates.
* **KISS & DRY Principles**: Keep code simple and reusable. Reuse common utilities in [`Utils.kt`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/Utils.kt) and preferences in [`GlobalSettings.kt`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt).
* **Data Portability**: Maintain full encrypted app state backup (ZIP) and individual account export/import (JSON).

---

## 📝 Versioning & CI/CD Integration

* **CI/CD Checkout depth**: Always use `fetch-depth: 0` in GitHub Actions checkout steps when running Gradle builds, as Gradle uses `git describe` to derive the version code and name.
* **Version Name Sanitation**: Version names follow these formats:
  - Release builds: `v<version>-<6_char_hash>(release)` (e.g., `v3.1.4-081be9(release)`)
  - Debug/Dev builds: `v<version>-<6_char_hash>-dev` (e.g., `v3.1.4-081be9-dev`)  
  Always use `.replace(Regex("[^0-9.]"), "")` when parsing or comparing version name strings in Kotlin (e.g., `isVersionNewer` in `MainActivity.kt`) to strip non-numeric suffixes before splitting.
* **Changelog Rules**: Track changes in [`CHANGELOG.md`](file:///Users/pinus/projects/tailsocks/CHANGELOG.md):
  - Always record new changes under a bumped version header (`## [X.Y.Z] - YYYY-MM-DD`). Do not append changes to an already released or tagged version header.
  - Document factual updates only ("What" and "Why"). Do not include discarded experiments or marketing fluff.

---

## 🐙 Git Workflow & Conventional Commits

1. **Conventional Commits**:
   Follow strict Conventional Commit format for all commit messages in English:
   - `feat(ui): add per-app TUN routing selector`
   - `fix(daemon): resolve ETag synchronization failure in Serve API`
   - `refactor(admin): migrate TailscaleApiClient to OkHttp`
   - `docs: update Russian documentation for v3.1.5`
2. **Atomic Local Commits**:
   - Make incremental local commits (`git commit -m "..."`) after each individual logical change. Do not stage everything at the end of the turn.
3. **Skill & Local Artifact Isolation**:
   - Do NOT commit files inside `.agents/`, `.skills/`, `skills-lock.json`, or `.gemini/` directories. Verify that these remain untracked using `git status`.

---

## 🤖 AI Agent Communication Directives

* **Language Preference**: Communicate with the user in **Russian** (clear, technical, professional tone). Write code, logs, inline comments, commit messages, and documentation in **English**.
* **Professional & Minimalist Tone**: Avoid fluff, marketing jargon, or unnecessary adjectives in code, commit logs, and documentation. Focus on factual, concise engineering.
