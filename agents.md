# TailSocks: Developer Agent Guidelines

This document contains mandatory rules, architectural principles, and practical instructions for any AI coding assistant (agent) working with the **TailSocks** repository.

---

## 🏗️ Core Architecture & Tech Stack

TailSocks is a hybrid multi-layer system:
1. **Core (Go daemon)**: Patched `tailscaled` daemon (v1.98.3), compiled as the native library `libtailscale.so`.
2. **Bridge (Go/Gomobile)**: The `appctr` module provides JNI bindings (class `appctr.Appctr` in Kotlin) for controlling the daemon via Unix Socket (`tailscaled.sock`) using LocalAPI v0.
3. **UI (Kotlin/Compose)**: Compact Material 3 dashboard implemented in Jetpack Compose.
4. **TUN Engine (C)**: System-wide transparent VPN routing powered by the native `hev-socks5-tunnel` library.
5. **DPI Bypass (JNI/C)**: Integrated native JNI ByeDPI implementation binding to a randomized loopback IP/port on each startup.

### Key Architectural Guidelines:
* **CLI-less Daemon Management**: Daemon lifecycle is managed 100% via Unix socket LocalAPI v0. Do not implement aggressive configuration polling. Do not call shell commands (no CLI wrapper) unless recovering from process lock.
* **Reset-then-Apply Pattern**: Every Serve/Funnel or AdvertiseServices configuration update must be explicit. First, post an empty object `{}` (Reset) to clear stale daemon state, then apply the new configuration.
* **Account Isolation**: Independent accounts must store state in `/files/states/{id}/` and preferences in `appctr_{id}`.

---

## 🛠️ Build System & Compilation

Modifications to the project require compiling native assets:

### 1. Compile the Go Core (`appctr`)
If Go code, bridge bindings, or Tailscale patches are modified:
```bash
cd appctr
./build.sh
```
*This script downloads pristine Tailscale sources (if missing), applies atomic patches from `appctr/patches/` in alphabetical order, and compiles native `.so` binaries for 4 target architectures.*

* **Patch Management**: If you modify code in `appctr/tailscale_src/`, recreate the atomic patch files before committing:
  ```bash
  ./appctr/patches/recreate_patches.sh
  ```
  This diffs your changes against clean sources in `appctr/orig/`.

### 2. Compile the Android APK
```bash
./gradlew app:assembleRelease
```

---

## 📂 Project Layout

* [`app/src/main/java/io/github/bropines/tailscaled/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/) — Kotlin Android app code.
  * [`admin/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/admin/) — Device management via Tailscale Admin API.
  * [`core/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/) — Background services, VPN tunnel, ByeDPI, account storage.
  * [`ui/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/ui/) — Compose dashboards and settings screens.
* [`appctr/`](file:///home/pinus/projects/tailsocks/appctr/) — Go Gomobile bridge.
  * [`patches/`](file:///home/pinus/projects/tailsocks/appctr/patches/) — Uppermost Tailscale patch files.
  * [`tailscale_src/`](file:///home/pinus/projects/tailsocks/appctr/tailscale_src/) — Active Tailscale source copy (git-ignored).
  * [`orig/`](file:///home/pinus/projects/tailsocks/appctr/orig/) — Original clean Tailscale sources (git-ignored).

---

## 🧼 Code Principles: DRY & KISS

1. **KISS (Keep It Simple, Stupid)**:
   - Trust the Tailscale daemon to handle policy sync and connectivity recovery. Do not write complex watchdog loops.
   - Avoid nesting non-standard components in Compose UI.
2. **DRY (Don't Repeat Yourself)**:
   - Reuse common utilities in [`Utils.kt`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/Utils.kt) and preferences in [`GlobalSettings.kt`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt).

---

## 📝 Changelog & Versioning Rules

We track changes in [`CHANGELOG.md`](file:///home/pinus/projects/tailsocks/CHANGELOG.md):
1. **New Release Bumping**:
   - If the last release tag (e.g., `v3.1.0`) already exists in Git history, **always record new changes under a bumped version header** (e.g. `## [3.1.1] - YYYY-MM-DD`).
   - Do not append changes to a version header that is already tagged or released.
2. **Factual Updates Only**:
   - Only document changes that have been successfully merged/committed into the codebase.
   - Do not write about changes "conceived on the way" by the agent that were not actually implemented or were discarded. Keep it simple and focused on "What" and "Why".

---

## 🐙 Git Workflow & Guidelines

1. **Atomic Commits**:
   - Make local commits (`git commit -m "..."`) after each individual logical change (e.g., UI layout fix, settings option addition). Do not wait until the end of the session to stage everything.
2. **Skill Isolation**:
   - Do not commit any files inside the `.agents/` or `.skills/` directories.
   - Verify that these remain untracked using `git status`.
