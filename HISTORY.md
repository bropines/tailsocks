# 📜 TailSocks Development History & Archive

This file tracks architectural decisions, failed attempts, and successful implementations to provide context for future AI agents.

## 🟢 [2026-04-16] UI & DNS Overhaul (Current State)

### ✅ Successes:
- **Standalone Project:** Detached from the original fork and moved to `bropines/tailsocks`.
- **Signature Change (v1.4.0):** Migrated to a new `.jks` keystore for the standalone release. (Requires one-time reinstallation of the app).
- **Dynamic Versioning:** Moved `versionCode` and `versionName` to be git-derived in `app/build.gradle.kts` using `providers.exec`. This fixed "Unresolved reference" errors in Kotlin DSL.
- **Exit Node Selection:** Implemented JSON parsing of `tailscale status --json` to populate a dropdown menu in Settings.
- **DNS Proxy Flexibility:** Added UI to configure the Go-based DNS proxy listen address (port 1053 by default).
- **IP Randomization:** Updated the SOCKS5 randomizer to use the entire `127.x.y.z` range (Localhost/8) to avoid port collisions and improve isolation.
- **Refactoring:** Removed `dummy_systray` patch and `appctr_test.go` as they were redundant due to build tags.
- **CI/CD Optimization:** Updated `android.yml` to trigger only on code changes (app/ or appctr/) and not on documentation edits.
- **Auto-refresh Config:** Implemented a periodic background loop (every 15s) in `TailscaledService` that runs `tailscale up` (ReUp) to sync admin policies automatically.
- **Startup Update Check:** Moved app version checking to application startup (silent Toast notification) and kept a manual button in Settings.
- **Intelligent Restarts (ApplySettings):** Refactored the Go-bridge and Kotlin service to support partial updates. The system now intelligently decides whether to perform a full daemon restart, a DNS-only restart, or a simple `tailscale up` based on configuration changes. This prevents unnecessary connection drops.
- **Versioning Fix (v1.5.1):** Fixed CI versioning by ensuring full git history fetch (`fetch-depth: 0`) and ignoring the `--dirty` flag in GitHub Actions to avoid mismatching version strings.
- **AdGuard Guide:** Created `docs/ADGUARD.md` detailing how to resolve `.ts.net` domains through AdGuard's low-level settings.
- **Backups:** Added backup/restore system to JSON.

### 🟢 [2026-04-17] Taildrop & Core Monolith

### ✅ Successes:
- **Taildrop Enabled:** Successfully enabled Taildrop on Android by bypassing the problematic JNI implementation. We utilized `TS_TAILDROP_DIR` via environment variables and injected a pure Go filesystem provider (`fileops_fs.go`).
- **Unified Patch System:** Migrated from fragile `sed` commands in `build.sh` to a robust, unified `tailsocks.patch` file applied via `patch -p0`. This ensures stable builds and easy maintenance.
- **Taildrop Manager (Files UI):** Created a dedicated `FilesActivity` in Kotlin to manage received files (Inbox) and view the history of sent files (Sent Log). Features include opening files and saving them to the public `Downloads` directory.
- **Smart Status Polling:** Optimized `MainActivity` to poll `status --json` only when necessary (e.g., waiting for browser login), significantly reducing battery drain and log spam.
- **Netcheck Diagnostics:** Transformed the broken `netcheck` screen into a functional Connection Health dashboard parsing `status --json` to show Relay info and NAT status.
- **SOCKS5 Credentials:** Injected `Username` and `Password` support for the SOCKS5 server directly into the Tailscale proxy configuration.

### ⚠️ Known Issues / Quarks:
- **Signature Incompatibility:** Builds from `v1.4.0` onwards are NOT compatible with older "fork" versions (v1.3.1 and below). Users must uninstall the old app first.
- **Kotlin DSL Scoping:** `project.exec` and standard `java.io` are often "unresolved" inside `defaultConfig`. Always move such logic to the top level of `build.gradle.kts` and use `providers.exec`.
- **Hedgehog Interaction:** Tailscale daemon is a separate process. It requires `killall` on startup to ensure a clean state if the app crashed previously.

### 🛠 Failed Attempts:
- **Inline `exec` in Gradle:** Attempting to use `java.io.ByteArrayOutputStream()` directly inside the `defaultConfig` block failed due to classpath/scope restrictions in newer Gradle versions.

---

## 🚀 Future Roadmap Ideas:
- **tsnet Integration:** Moving from a standalone binary to an embedded Go library (tsnet) to eliminate process management overhead.
- **tun2socks:** Adding `VpnService` support for system-wide routing while keeping the "lightweight" feel.

## 🔴 [2026-04-18] The "Hedgehog Evolution" & Revert

### ✅ Successes:
- **Account Manager:** Implemented a robust multi-account system in Kotlin. Each Tailscale account now operates in its own isolated state directory (`/files/accounts/UUID`).
- **UI Switcher:** Added a TopAppBar account switcher that triggers a graceful service restart with the new state directory.

### 🛠 Failed Attempts (Lessons Learned):
- **Spoofing "OS: linux" to bypass 410 Wall:** 
    - *Hypothesis:* Tricking the control plane into thinking the Android binary is a Linux CLI would bypass the JNI-auth requirement and fix the "410 Gone: auth path not found" error.
    - *Result:* Reverted. While it successfully generated interactive login URLs, it caused severe Netmap synchronization issues (stuck at "netmap not yet valid") because the server expects different protocol behaviors for Linux vs Android.
    - *Conclusion:* We must find a way to stabilize the login flow while maintaining the "Android" identity, or better handle the transition between states.

### 🧹 Maintenance:
- **Project Revert:** All experimental Go-bridge patches from this session were removed. The repository has been returned to the clean state of commit `ec7142f`.

### 🧪 Insights from the "Neural Network Roast":
- **HTTP 410 Explained:** Confirmed as "Gone". Server drops the polling path if it thinks the session is consumed or expired.
- **Potential Fixes to try:**
    - Use `tailscale up --force-reauth` to clear internal daemon "sticky" sessions.
    - Set environment variable `TS_AUTH_ONCE=true` to prevent re-using invalid tokens.
    - Investigate if providing `file:/dev/null` as an auth key forces a cleaner interactive flow.
