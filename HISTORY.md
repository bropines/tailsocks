# 📜 TailSocks Development History & Archive

This file tracks architectural decisions, failed attempts, and successful implementations to provide context for future AI agents.

## 🟢 [2026-04-16] UI & DNS Overhaul (Current State)

### ✅ Successes:
- **Dynamic Versioning:** Moved `versionCode` and `versionName` to be git-derived in `app/build.gradle.kts` using `providers.exec`. This fixed "Unresolved reference" errors in Kotlin DSL.
- **Exit Node Selection:** Implemented JSON parsing of `tailscale status --json` to populate a dropdown menu in Settings.
- **DNS Proxy Flexibility:** Added UI to configure the Go-based DNS proxy listen address (port 1053 by default).
- **IP Randomization:** Updated the SOCKS5 randomizer to use the entire `127.x.y.z` range (Localhost/8) to avoid port collisions and improve isolation.
- **Refactoring:** Removed `dummy_systray` patch and `appctr_test.go` as they were redundant due to build tags.

### ⚠️ Known Issues / Quarks:
- **Kotlin DSL Scoping:** `project.exec` and standard `java.io` are often "unresolved" inside `defaultConfig`. Always move such logic to the top level of `build.gradle.kts` and use `providers.exec`.
- **Hedgehog Interaction:** Tailscale daemon is a separate process. It requires `killall` on startup to ensure a clean state if the app crashed previously.

### 🛠 Failed Attempts:
- **Inline `exec` in Gradle:** Attempting to use `java.io.ByteArrayOutputStream()` directly inside the `defaultConfig` block failed due to classpath/scope restrictions in newer Gradle versions.

---

## 🚀 Future Roadmap Ideas:
- **tsnet Integration:** Moving from a standalone binary to an embedded Go library (tsnet) to eliminate process management overhead.
- **tun2socks:** Adding `VpnService` support for system-wide routing while keeping the "lightweight" feel.
