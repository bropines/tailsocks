# TailSocks: Developer Agent Guidelines & Project Mandate

This document contains mandatory rules, architectural principles, engineering standards, and practical instructions for any AI coding assistant (agent) or human developer working with the **TailSocks** repository.

---

## 🏗️ Core Architecture & Tech Stack

TailSocks is a hybrid, multi-layer Android client for Tailscale operating in **userspace-networking mode**:

1. **Core (Go Daemon)**: Patched `tailscaled` daemon (version pinned in `appctr/TAILSCALE_VERSION`, currently v1.102.1), compiled as the native shared library `libtailscale.so` (PIE).
2. **Bridge (Go/Gomobile)**: The `appctr` module provides JNI bindings (`appctr.Appctr` in Kotlin) and handles LocalAPI v0 communication over Unix Domain Sockets (`tailscaled.sock`).
3. **UI (Kotlin/Compose)**: High-density, no-scroll Material 3 dashboard implemented in Jetpack Compose.
4. **TUN Engine (C)**: System-wide transparent VPN routing powered by the native `hev-socks5-tunnel` library (optional VPN mode). This is what ships. The rebuild in which `tailscaled` owns the `VpnService` fd directly and `hev` disappears is a **plan** ([`docs/NATIVE_TUN_PLAN.md`](docs/NATIVE_TUN_PLAN.md), targeted at 4.1) — do not write, document or reason as if it exists.
5. **DPI Bypass (JNI/C)**: Native JNI ByeDPI implementation binding to a randomized loopback IP/port in `127.0.0.0/8` upon startup to bypass Control Plane SNI-based DPI.

### Fundamental Architectural Rules:
* **CLI-less Daemon Management**: Management is 100% CLI-less. Communicate exclusively via the Unix Socket (`tailscaled.sock`) using LocalAPI v0. Do not execute shell commands or wrap CLI binaries unless recovering from process lock.
* **Passive Management**: Trust the Tailscale daemon to manage its own lifecycle, policy synchronization, and connectivity recovery. Do not write aggressive, high-frequency configuration loops or watchdog restarts. The only sanctioned recovery paths are the opt-in ones in `core/ServiceWatchdog.kt` (inexact 15-minute alarm that revives a killed service) and the auto-reconnect check in `TailscaledService` (three unhealthy ticks, bounded attempts, never while the daemon waits for login). Both are cancelled by a manual Stop, which is final — never add code that restarts the service after `desired_running` was cleared.
* **Automation is gated**: `TaskerReceiver` refuses every broadcast until a secret token is configured (`secret` / `token` / `key` extra); all mutating AppFunctions in `appfunctions/TailSocksFunctions.kt` return an error while *Allow External Automation* is off. Keep both checks when adding actions or functions.
* **Root Mode is a guest on the device**: it routes *below* Android's own VPN plumbing, so every rule it writes can outrank another VPN client's. The tiering that keeps that honest is mandatory and is spelled out under *Root Mode Standards* below. Treat peer names and settings fields as untrusted before they reach a root shell (`RootUtils.shQuote`).
* **Stateless Configuration (Reset-then-Apply)**: Every configuration update (Prefs, Serve/Funnel, Virtual Services) must be explicit. For Serve/Funnel, first POST an empty object `{}` to `/localapi/v0/serve/config` (Reset) to clear stale daemon state, then apply the new configuration.
* **Account Isolation**: Strict filesystem and preference separation per profile. Independent accounts store state in `/files/states/{id}/` and preferences in `appctr_{id}`. Full daemon restart and re-initialization is required on profile switch.
* **Tailscale Core Source Auditing**: ALWAYS inspect the original Tailscale source code (located in `appctr/tailscale_src/` or `appctr/orig/`) before modifying Go bridge code or creating/modifying patches. Design solutions based on native Tailscale patterns rather than guessing.

---

## 📡 Networking, DNS & Serve Standards

* **Userspace Mode:** Operates natively in userspace-networking mode without requiring Android's `VpnService` permission, enabling coexistence with system ad-blockers and other VPN apps.
* **DNS Wrapping:** MagicDNS and Split DNS resolution are handled via a custom Go-based DNS server (port 1053) that wraps UDP queries into TCP-over-SOCKS5 frames to bypass Android network restrictions. Fallback chain: SOCKS5 UDP → Direct UDP → DoH.
* **Tailscale Services (`svc:`):** Virtual services host named endpoints (e.g. `svc:name`). They require manual approval in the Tailscale Admin Console after creation in the app. L3 TUN mode for virtual IPs is unsupported; use port-specific Serve/Funnel rules.
* **NAT Traversal:** Monitor NAT connectivity through real-time `InMagicSock` status notifications. Avoid disrupting the `magicsock` engine with unnecessary restarts.

### Root Mode Standards

Root Mode installs policy routing and iptables rules below netd. The full
reference, with the measurements behind it, is [`docs/ROOT.md`](docs/ROOT.md);
these are the rules a change must not break.

* **Three tiers, installed separately.** **T1 — tailnet reachability**: table `53`, two rules at priority 100, the `TAILSOCKS_MARK` mangle chain, the `tailscale0` FORWARD pair, `/etc/hosts` publication. **T2 — default-route capture**: one rule at priority 200 into the daemon's own table `52`, the LAN `throw` routes, the per-app exclusions at priority 190. **T3 — device-wide DNS**: the `TAILSOCKS_DNS` nat chain and its two `nat OUTPUT` hooks.
* **T1 coexists with anything; T2 and T3 have exactly one owner per device.** When another VPN client holds Android's VPN slot, T2/T3 are yielded — entirely, or narrowed to exactly the uids that client bypasses (matched by the netId netd stamps into the mark), with that client's own DNS left alone. The sticky "take the device anyway" override is the only way past this, and it must keep saying what it costs. Never install a device-wide tier without running the coexistence check first.
* **Table `53`, never `1099`.** `1099` is inside netd's per-network table space; it was ours up to 3.5.x and the migration exists only to delete it. Steer by masked fwmark `0x1000000/0x1000000` — never a bare `--set-mark`, which overwrites Android's own fwmark bits.
* **Per-app exclusion is a routing rule, not a mark.** `ip rule … uidrange <uid> goto <netd's lowest priority> priority 190`: the target priority is *discovered* at runtime (`16000` was hardcoded before 4.0.0 and is not universal) and the rule is read back, because the kernel accepts an unresolvable `goto` and then silently skips it. The old `TAILSOCKS_BYPASS` mangle-mark chain is gone and must not return — a mark set in `mangle OUTPUT` arrives after the kernel has already chosen the route and the source address.
* **Device-wide tiers are gated, not assumed.** T2 goes in only once the daemon is *known* to mark its own sockets (`SO_MARK 0x2000000`, patch 16) — otherwise table `52`'s default route swallows the tunnel's own packets. Two witnesses, in order: the probe line in the current run's log, and — when the log is merely silent, which is normal for a boot-started daemon whose start has scrolled away — a check that the running `libtailscale.so` is the binary we ship (`/proc/<pid>/exe` under our `nativeLibraryDir`). Keep both; an explicit negative *from the daemon* still wins, and "cannot tell" still means no priority 200. T3 is armed only while MagicDNS actually answers. Interfaces holding an address inside `100.64.0.0/10` get a `throw` route, so a carrier CGNAT range or a foreign tunnel living there is not captured.
* **The boot script installs the routing half of T1 and nothing else.** At `late_start` there is no `PackageManager`, so it cannot resolve packages to uids and cannot install the exclusions and LAN `throw` routes that make T2/T3 safe — and no other VPN client has started yet, so a coexistence check there would answer "the device is free" every time. `/etc/hosts` publication is not in it either: the MagicDNS host entries are written by `RootUtils.updateRootHosts` from the app's peer-refresh tick, which needs a peer list and therefore an app process. The app installs the rest of T1 and all of T2/T3 on its first `Running` tick. State the cost plainly wherever it matters: with *keep running in background* off, a reboot leaves no exit node and no device-wide MagicDNS until the app is opened, and tailnet *addresses* are reachable while tailnet *names* are not.
* **Chain hygiene.** MARK/DNAT rules go only inside `TAILSOCKS_MARK` / `TAILSOCKS_DNS`; `OUTPUT` carries only the jump into those chains (guarded with `-C`), and `FORWARD` only the `tailscale0` ACCEPT pair.

---

## 🛠️ Build System, Compilation & Patch Pipeline

Modifications to native assets or core logic require executing the build pipeline:

### 1. Compile the Go Core (`appctr`)
When Go code, bridge JNI bindings, or Tailscale patches are modified:
```bash
cd appctr
bash build.sh
```
*   **Dynamic Patch Pipeline**: `build.sh` downloads clean Tailscale source code, applies the atomic patches in `appctr/patches/` in alphabetical order (`01-enable-socks-android` through `16-android-somark`), and cross-compiles native `.so` binaries for 4 target architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
*   **Patch Management**: If code in `appctr/tailscale_src/` is modified, recreate the atomic patch files before committing:
    ```bash
    ./appctr/patches/recreate_patches.sh
    ```
    This diffs changes against clean sources in `appctr/orig/`. Never commit compiled binary `.so` files or unpatched sources.

### 2. Compile the Android APK
```bash
# Release Build — requires a real keystore; the build refuses to sign with the debug key
KEYSTORE_FILE="$PWD/tailsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease

# Debug/Dev Build (application id suffix .dev, no keystore needed)
./gradlew app:assembleDebug
```
*   **The release build refuses two things**: signing with the debug key (all four `KEYSTORE_*` variables are required; keystore `tailsocks.jks` at the repo root), and a stale Go bridge — `verifyGoBridgeFresh` fails the build if `appctr/tmp/appctr.aar` is older than any `appctr/*.go` file or any patch. Run `appctr/build.sh` after *every* Go or patch change; without it an APK does not contain the change.
*   **R8 is on** for release (`isMinifyEnabled`/`isShrinkResources`). Do not introduce reflection-based JSON (use `kotlinx.serialization` via `core/AppJson.kt`), `getIdentifier`, or dynamically named resources — they break silently in a shrunk build.
*   **JNI keep check**: `verifyReleaseNativeMethods` runs after `minifyReleaseWithR8` and before `assembleRelease`/`bundleRelease`; it fails the build if any `external fun` is missing from R8's `seeds.txt` or a native member shows up in `usage.txt`. New `external fun`s need a plain `-keep` for `native <methods>` in `app/proguard-rules.pro`.
*   **Backups are versioned** (`core/BackupFormat.kt`): bump `CURRENT_FORMAT_VERSION` only when the archive layout changes so that older builds can no longer read it; a restore refuses archives from a newer app version or format.

---

## 📂 Project Layout

* [`app/src/main/java/io/github/bropines/tailscaled/`](app/src/main/java/io/github/bropines/tailscaled/) — Kotlin Android source code.
  * [`admin/`](app/src/main/java/io/github/bropines/tailscaled/admin/) — Device & network management via Tailscale Admin API.
  * [`core/`](app/src/main/java/io/github/bropines/tailscaled/core/) — Background services, VPN tunnel, ByeDPI JNI, account storage, tasker receiver.
  * [`ui/`](app/src/main/java/io/github/bropines/tailscaled/ui/) — Jetpack Compose dashboards, settings, serve UI, and dialogs.
* [`appctr/`](appctr/) — Go Gomobile bridge and core engine.
  * [`patches/`](appctr/patches/) — Atomic Tailscale patch files (`01-*.patch` .. `16-*.patch`).
  * [`tailscale_src/`](appctr/tailscale_src/) — Active patched Tailscale source copy (git-ignored).
  * [`orig/`](appctr/orig/) — Original clean Tailscale sources (git-ignored).

---

## 🎨 UI & UX Standards (Jetpack Compose)

* **Compose Architecture & Material 3**:
  - The UI is built entirely with Jetpack Compose using Material 3 design tokens.
  - Rely on `androidx.compose.material3.*` components (`Card`, `Surface`, `Scaffold`, `ModalBottomSheet`, `AlertDialog`).
  - Colors, shapes, and typography must dynamically adapt via `TailSocksTheme`, which supports multiple presets, AMOLED pure black mode, and dynamic color (`dynamicLightColorScheme` / `dynamicDarkColorScheme`). Use semantic colors (e.g., `MaterialTheme.colorScheme.surfaceContainerHigh`) rather than hardcoded hex values.

* **High-Density 2×4 Grid Dashboard**:
  - The main dashboard ([`MainActivity.kt`](app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt)) utilizes a dense, scroll-free vertical layout encapsulated in a `Column`.
  - Core control is a large `StatusCard` with 28.dp rounded corners serving as the primary connection toggle.
  - Sub-menus are arranged in a 2×4 grid using `Row` components where each `MenuCard` uses `Modifier.weight(1f)` for a precise 2-column distribution.

* **Component & Layout Patterns**:
  - **Cards & Surfaces**: Extensively use `ElevatedCard` or `Surface` with `RoundedCornerShape(16.dp)` for list items and configuration containers.
  - **Pull-To-Refresh**: Use Material 3's `PullToRefreshBox` with `rememberPullToRefreshState` for lists requiring manual sync (e.g., Peers list, Logs).
  - **Horizontal Pager**: Use `HorizontalPager` for tabbed views (e.g., Serve/Funnel/Logs in [`ServeActivity.kt`](app/src/main/java/io/github/bropines/tailscaled/ui/ServeActivity.kt)).
  - **Chip-Based Controls**: For mutually exclusive settings (e.g., Serve Mode [Web/TCP], Transport [HTTP/HTTPS]), use `FilterChip` organized in horizontally scrollable rows: `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))`.

* **Dialog & BottomSheet Patterns**:
  - Favor `ModalBottomSheet` (with `rememberModalBottomSheetState(skipPartiallyExpanded = true)`) for quick selections (Exit Nodes, Account Switcher).
  - Use `AlertDialog` for settings, creation forms, and destructive actions. Forms within dialogs should use `OutlinedTextField` and `FilterChip` stacked with `Arrangement.spacedBy(12.dp)`.

* **State Management & Reactivity**:
  - **Local State**: Use `remember { mutableStateOf(...) }` for ephemeral UI state.
  - **Preference Sync**: Use `DisposableEffect` with `SharedPreferences.OnSharedPreferenceChangeListener` to react to global/account preference changes instantly across the app.
  - **Side Effects**: Use `LaunchedEffect` and `CoroutineScope(Dispatchers.IO)` for backend API calls via `Appctr`, returning state to the main thread via `withContext(Dispatchers.Main)`. Never block the UI thread during JNI calls.

* **KISS & DRY Principles**: Keep code simple and reusable. Reuse common utilities in [`Utils.kt`](app/src/main/java/io/github/bropines/tailscaled/core/Utils.kt) and preferences in [`GlobalSettings.kt`](app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt).

---

## 📝 Versioning & CI/CD Integration

* **CI/CD Checkout depth**: Always use `fetch-depth: 0` in GitHub Actions checkout steps when running Gradle builds, as Gradle uses `git describe` to derive the version code and name.
* **Version Name Sanitation**: Version names follow these formats:
  - Release builds: `v<version>-<6_char_hash>(release)` (e.g., `v3.1.4-081be9(release)`)
  - Debug/Dev builds: `v<version>-<6_char_hash>-dev` (e.g., `v3.1.4-081be9-dev`)  
  Always use `.replace(Regex("[^0-9.]"), "")` when parsing or comparing version name strings in Kotlin (e.g., `isVersionNewer` in `MainActivity.kt`) to strip non-numeric suffixes before splitting.
* **Changelog Rules**: Track changes in [`CHANGELOG.md`](CHANGELOG.md):
  - Always record new changes under a bumped version header (`## [X.Y.Z] - YYYY-MM-DD`). Do not append changes to an already released or tagged version header.
  - Document factual updates only ("What" and "Why"). Do not include discarded experiments or marketing fluff.
  - **User & Releaser Oriented (Minimal Technical Noise)**: `CHANGELOG.md` is created for end-users and the releaser, not for internal code diff tracking. Keep entries simple, concise, and focused on user-facing impact. **Do NOT include low-level technical details**: avoid internal class/struct names (e.g., `PredictiveBackContainer`), source file paths (`Utils.kt`, `appctr/api.go`), function arguments/constants (`scale = 0.88`, `maxLines = 1`), XML resource IDs, or code-level API payloads.

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
   - **Step 4 (Push Approval)**: never push — see *Working agreements* below.
3. **Skill & Local Artifact Isolation**:
   - Do NOT commit files inside `.agents/`, `.skills/`, `skills-lock.json`, or `.gemini/` directories. Verify that these remain untracked using `git status`.
4. **Devices and how to drive them**:
   - Test devices arrive over `adb connect` from the author: a non-rooted daily phone, a rooted phone (APatch), and a Magisk WSA image (x86_64). Root Mode work can only be verified on the rooted ones.
   - On a release build the app's components are not exported, so `adb shell am start`/`am broadcast` cannot reach them. Two ways in: drive the UI with `uiautomator dump` plus `input tap`, or, on a rooted device, go through a root shell — `su -c 'am start-foreground-service -n io.github.bropines.tailscaled/.core.TailscaledService -a START_ACTION'`.
   - **On WSA prefer the root shell.** `uiautomator dump` there returns `null root node` whenever the Windows-side window is unfocused, so the UI route is unreliable on exactly the image most often used for Root Mode work.
5. **CRITICAL ADB & Data Preservation Rules**:
   - **NEVER execute `adb uninstall` without explicit user permission.**
   - If uninstallation is ever required and explicitly approved by the user, **ALWAYS pass the `-k` flag (`adb uninstall -k <package_name>`)** to preserve the user's state, account profiles, keys, and preferences in internal storage.
   - Never run destructive commands that wipe user data or state without prior confirmation.

---

## 🤖 AI Agent Communication Directives

* **User Language Matching**: Always communicate with the user in **whatever language they write in** (e.g. if the user writes in Russian, respond in clear, professional Russian; if in English, respond in English).
* **Code & Commit Language**: Write all code, logs, inline comments, commit messages, and documentation strictly in **English**.
* **Professional & Minimalist Tone**: Avoid fluff, marketing jargon, or unnecessary adjectives in code, commit logs, and documentation. Focus on factual, concise engineering.
* **Documentation is bilingual**: every user- or contributor-facing document has a Russian twin, wherever it lives and whatever the naming shape — `docs/ROOT.md` / `docs/ROOT_RU.md`, `readme.md` / `readme_ru.md`, `CONTRIBUTING.md` / `CONTRIBUTING_RU.md`. Change one, change the other in the same commit; never leave a twin behind. One deliberate exception: [`docs/NATIVE_TUN_PLAN.md`](docs/NATIVE_TUN_PLAN.md) is an internal design document for unwritten code and stays English-only until the work lands — do not translate it, and mark the link as English-only when a Russian document points at it.

---

## 🤝 Working agreements & handoffs between sessions

* **Never run `git push`** — the author publishes, and only after he has tested the build and said so («пушь» / «все хорошо»). Commit locally as much as you like. Never run any other git command that writes to the remote.
* **Every Go or patch change goes through `appctr/build.sh`** before an APK means anything; `verifyGoBridgeFresh` enforces it for releases.
* **Daemon changes are patches**, regenerated from pristine upstream sources with `appctr/patches/recreate_patches.sh`; they must apply with `patch -p1 -F0`. Never hand-edit a `.patch`, never commit `appctr/tailscale_src/`, `appctr/orig/` or compiled `.so` files.
* **Where the work is written down**: `CHANGELOG.md`'s top section is the unreleased work — extend it in its own style whenever you change behaviour. [`docs/ROADMAP.md`](docs/ROADMAP.md) (with its `_RU` twin) carries what is planned and what is left over, including the open Root Mode device verification and the optional-CLI-binary decision. [`docs/NATIVE_TUN_PLAN.md`](docs/NATIVE_TUN_PLAN.md) is the one large design effort still ahead — TUN rebuilt around a tailscaled-owned `VpnService` fd, targeted at 4.1 — with its research appendix in `docs/research/`.
* **When you pick up planned work**: read `CHANGELOG.md` (top section), `docs/BUILDING.md`, the plan document, then `git log --oneline -40`. Do not re-derive decisions the document records as decided; if you disagree, say so in the document instead of silently diverging. When a plan is finished or abandoned, update or delete it — a stale brief is worse than none.
