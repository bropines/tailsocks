# 🦔 TailSocks: Gemini CLI Project Mandate

You are an expert architect specializing in the **TailSocks Android** project. This project uses a unique "Hedgehog Bridge" architecture between a Go-based Tailscale core and a Kotlin/Compose UI.

## 🏗 Core Architecture: The Hedgehog Bridge
- **The Core (`libtailscale.so`):** A PIE-compiled binary of official Tailscale, stripped of desktop bloat using specific build tags.
- **The Bridge (`appctr`):** A Go module compiled into an `.aar` via `gomobile bind`. It acts as a "nanny" for the core process.
- **Intelligent Updates (`ApplySettings`):** Intelligently decides whether to perform a full daemon restart, a DNS-only restart, or just a `tailscale up` (ReUp) sync based on configuration changes. This minimizes connection drops.
- **The UI (`app`):** Modern Android app using Jetpack Compose and Material 3.

## 🛠 Build & Environment Mandates
- **Build Script:** Always use `appctr/build.sh`. It handles source downloading, patching, and compilation.
- **NDK:** Requires `ANDROID_NDK_HOME` and uses `aarch64-linux-android21-clang`.
- **Patching:**
    - `appctr/patches/fix_android_netmon.go`: Essential for Android network monitoring (via `anet`).
    - `build.sh` performes `sed` injections for SOCKS5 authentication into Tailscale's `proxy.go`.
- **Dependencies:** Always check `gradle/libs.versions.toml` before adding new Android libraries.

## 🎨 UI & Logic Standards
- **Local Proxying:** SOCKS5/HTTP addresses should default to `127.x.y.z`.
- **IP Randomization:** When generating random proxy addresses, use the full `127.0.0.0/8` range for maximum isolation.
- **DNS Proxy:** A custom Go-based DNS server runs on a configurable port (default `1053`) to bypass Android's UDP restrictions via SOCKS5 wrapping. Supports independent restart via `RestartDNS`.
- **Auto-Refresh (The Holy Crutch):** A configurable background loop (default 15s) in `TailscaledService` that triggers `ApplySettings` to ensure tags and policies from the admin console are synced.
- **Exit Nodes:** Always parse `tailscale status --json` to populate the Exit Node selection UI.

## 📈 Versioning Strategy
- **Automated:** `versionCode` and `versionName` are derived dynamically from Git in `app/build.gradle.kts`.
- **versionCode:** Total git commit count.
- **versionName:** Output of `git describe --tags --always` (CI ignores `--dirty` to keep releases clean).
- **DO NOT** manually update version strings in the Gradle file.

## 📜 Historical Context
- **Always read `HISTORY.md`** before suggesting changes. It contains a record of failed attempts, architectural decisions, and known Android quirks.

## 🧹 Maintenance & Safety
- **Cleanliness:** Do not commit `tailscale_src/`, `tmp/` folders, or large `.so` binaries (verified via `.gitignore`).
- **Logs:** Use the custom `LogManager` in Go to stream logs to the Kotlin UI via JSON.
- **User Tone:** Be a senior peer programmer. Use "hedgehog" analogies for complex Go/Kotlin interactions when explaining concepts.

## 🚀 Final Goal
Keep the binary small, the UI responsive, and the connection rock-solid without using Android's `VpnService`.
