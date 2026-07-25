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
* **Tailscale Core Source Auditing**: ALWAYS inspect the original Tailscale source code (located in `appctr/tailscale_src/` or `appctr/orig/`) before modifying Go bridge code or creating/modifying patches. Design solutions based on native Tailscale patterns rather than guessing.

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

## 🎨 UI & UX Standards (Jetpack Compose)

* **Compose Architecture & Material 3**:
  - The UI is built entirely with Jetpack Compose using Material 3 design tokens.
  - Rely on `androidx.compose.material3.*` components (`Card`, `Surface`, `Scaffold`, `ModalBottomSheet`, `AlertDialog`).
  - Colors, shapes, and typography must dynamically adapt via `TailSocksTheme`, which supports multiple presets, AMOLED pure black mode, and dynamic color (`dynamicLightColorScheme` / `dynamicDarkColorScheme`). Use semantic colors (e.g., `MaterialTheme.colorScheme.surfaceContainerHigh`) rather than hardcoded hex values.

* **High-Density 2×4 Grid Dashboard**:
  - The main dashboard ([`MainActivity.kt`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt)) utilizes a dense, scroll-free vertical layout encapsulated in a `Column`.
  - Core control is a large `StatusCard` with 28.dp rounded corners serving as the primary connection toggle.
  - Sub-menus are arranged in a 2×4 grid using `Row` components where each `MenuCard` uses `Modifier.weight(1f)` for a precise 2-column distribution.

* **Component & Layout Patterns**:
  - **Cards & Surfaces**: Extensively use `ElevatedCard` or `Surface` with `RoundedCornerShape(16.dp)` for list items and configuration containers.
  - **Pull-To-Refresh**: Use Material 3's `PullToRefreshBox` with `rememberPullToRefreshState` for lists requiring manual sync (e.g., Peers list, Logs).
  - **Horizontal Pager**: Use `HorizontalPager` for tabbed views (e.g., Serve/Funnel/Logs in [`ServeActivity.kt`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/ui/ServeActivity.kt)).
  - **Chip-Based Controls**: For mutually exclusive settings (e.g., Serve Mode [Web/TCP], Transport [HTTP/HTTPS]), use `FilterChip` organized in horizontally scrollable rows: `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))`.

* **Dialog & BottomSheet Patterns**:
  - Favor `ModalBottomSheet` (with `rememberModalBottomSheetState(skipPartiallyExpanded = true)`) for quick selections (Exit Nodes, Account Switcher).
  - Use `AlertDialog` for settings, creation forms, and destructive actions. Forms within dialogs should use `OutlinedTextField` and `FilterChip` stacked with `Arrangement.spacedBy(12.dp)`.

* **State Management & Reactivity**:
  - **Local State**: Use `remember { mutableStateOf(...) }` for ephemeral UI state.
  - **Preference Sync**: Use `DisposableEffect` with `SharedPreferences.OnSharedPreferenceChangeListener` to react to global/account preference changes instantly across the app.
  - **Side Effects**: Use `LaunchedEffect` and `CoroutineScope(Dispatchers.IO)` for backend API calls via `Appctr`, returning state to the main thread via `withContext(Dispatchers.Main)`. Never block the UI thread during JNI calls.

* **KISS & DRY Principles**: Keep code simple and reusable. Reuse common utilities in [`Utils.kt`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/Utils.kt) and preferences in [`GlobalSettings.kt`](file:///Users/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt).

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

## 🐙 Development Workflow & Git Standards (WSL / Local)

1. **Professional Code Quality**:
   - Write clean, production-grade, highly maintainable code. Avoid quick hacks, temporary workarounds, or silent error suppression.
2. **Local Iterative Development Cycle**:
   - **Step 1 (Implement)**: Write high-quality code modifications.
   - **Step 2 (Local Commit)**: Make an incremental local commit using Conventional Commit format:
     - `feat(ui): add per-app TUN routing selector`
     - `fix(daemon): resolve ETag synchronization failure in Serve API`
     - `refactor(admin): migrate TailscaleApiClient to OkHttp`
     - `docs: update Russian documentation for v3.1.5`
   - **Step 3 (User Verification)**: Prompt the user to test and verify the build/feature on their device or local environment.
   - **Step 4 (Push Approval)**: **NEVER run `git push` automatically.** Only push changes to remote repository AFTER the user explicitly tests and approves the result ("все хорошо" / "good").
3. **Skill & Local Artifact Isolation**:
   - Do NOT commit files inside `.agents/`, `.skills/`, `skills-lock.json`, or `.gemini/` directories. Verify that these remain untracked using `git status`.

---

## 🤖 AI Agent Communication Directives

* **User Language Matching**: Always communicate with the user in **whatever language they write in** (e.g. if the user writes in Russian, respond in clear, professional Russian; if in English, respond in English).
* **Code & Commit Language**: Write all code, logs, inline comments, commit messages, and documentation strictly in **English**.
* **Professional & Minimalist Tone**: Avoid fluff, marketing jargon, or unnecessary adjectives in code, commit logs, and documentation. Focus on factual, concise engineering.
