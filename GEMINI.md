# 🦔 TailSocks: Gemini CLI Project Mandate

You are an expert architect specializing in the **TailSocks Android** project. This project uses a unique "Hedgehog Bridge" architecture between a Go-based Tailscale core and a Kotlin/Compose UI.

## 🏗 Core Architecture: The Hedgehog Bridge
- **The Core (`libtailscale.so`):** A PIE-compiled binary of official Tailscale, stripped of desktop bloat using specific build tags.
- **The Bridge (`appctr`):** A Go module compiled into an `.aar` via `gomobile bind`. It acts as a "nanny" for the core process.
- **Account Isolation:** Each profile has its own `SharedPreferences` (`appctr_{id}`) and state directory (`files/states/{id}`). The `AccountManager` in Kotlin manages profile IDs.
- **Intelligent Updates (`ApplySettings`):** Intelligently decides whether to perform a full daemon restart (only if SOCKS5 or StatePath changes), a DNS-only restart, or a simple `tailscale up` (ReUp) sync. This minimizes connection drops and prevents 410 Gone errors during login.
- **Hedgehog Debug Tunnel:** A local TCP server (port 4567) in Go for real-time log access.

## 🛠 Build & Environment Mandates
- **Build Script:** Always use `appctr/build.sh`. It handles source downloading, patching, and compilation.
- **NDK:** Requires `ANDROID_NDK_HOME` and uses `aarch64-linux-android21-clang`.
- **Patching:**
    - `appctr/patches/fix_android_netmon.go`: Essential for Android network monitoring (via `anet`).
    - `appctr/patches/tailsocks.patch`: Unified patch applied via `patch -p0`.
- **Dependencies:** Always check `gradle/libs.versions.toml` before adding new Android libraries.

## 🎨 UI & Logic Standards
- **Local Proxying:** SOCKS5/HTTP addresses should default to `127.x.y.z`.
- **IP Randomization:** When generating random proxy addresses, use the full `127.0.0.0/8` range for maximum isolation.
- **DNS Proxy:** A custom Go-based DNS server runs on a configurable port (default `1053`) to bypass Android's UDP restrictions via SOCKS5 wrapping.
- **Auto-Refresh (The Holy Crutch):** A configurable background loop (default 15s) in `TailscaledService` that triggers `ApplySettings` to ensure tags and policies from the admin console are synced.
- **Exit Nodes:** Always parse `tailscale status --json` to populate the Exit Node selection UI.

## 📈 Versioning Strategy
- **Automated:** `versionCode` and `versionName` are derived dynamically from Git in `app/build.gradle.kts`.

## 📜 Historical Context
- **Always read `HISTORY.md`** before suggesting changes.

## 🧹 Maintenance & Safety
- **Cleanliness:** Do not commit `tailscale_src/`, `tmp/` folders, or large `.so` binaries.
- **Logs:** Use the custom `LogManager` in Go to stream logs to the Kotlin UI via JSON.

## 🚀 Final Goal
Keep the binary small, the UI responsive, and the connection rock-solid without using Android's `VpnService`. 

**Status of the "410 Wall":** The `http 410: auth path not found` error remains a persistent quirk when using native Android identities without JNI-based attestation. We have successfully **mitigated** its impact by:
1.  Preventing daemon restarts/resets during the 1-3 minute backoff period.
2.  Ensuring machine key persistence via account-isolated state directories.
3.  Providing UX feedback (Warning Banners) to ensure the user waits for the automated retry to succeed.

TEMP: The user is Russian-speaking, answer him in Russian
