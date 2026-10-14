---
title: Building
description: The patch pipeline, the build steps, release builds, and installing over ADB.
---

The build pipeline is automated and modular. It avoids the technical debt of maintaining a massive git fork of Tailscale.

## The patch pipeline

Instead of resolving endless merge conflicts, the build script patches the upstream source on the fly:

1. **Fetch fresh core.** Downloads a lightweight archive of the official Tailscale source at the version pinned in `appctr/TAILSCALE_VERSION` (currently `v1.104.1`), checked against `appctr/TAILSCALE_SHA256`.
2. **Atomic patch injection.** Applies a series of modular, atomic `.patch` files (under `appctr/patches/`) that adapt the Tailscale source for mobile features: SOCKS5 proxy support, custom file systems for Taildrop, LocalAPI certificate generation, and Android-specific network monitoring.
3. **Aggressive trimming.** Uses a large array of Go build tags (`ts_omit_systray`, `ts_omit_kube`, `ts_omit_aws`, `ts_omit_bird`, `ts_omit_drive`, etc.) to strip out desktop Linux and enterprise features.
4. **Result.** One `libtailscale.so` per ABI — the daemon with the CLI linked in (`ts_include_cli`) — that compiles quickly and runs efficiently in the Android sandbox.

## Requirements

- **Operating system:** Linux or macOS.
- **Go:** no particular version. The build uses `GOTOOLCHAIN=auto` and downloads the toolchain it needs.
- **Android SDK:** compileSdk 37, targetSdk 35, minSdk 24.
- **Android NDK:** 27.x (`ANDROID_NDK_HOME`).
- **gomobile:** `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`.
- **Gradle:** 8.x or newer, through the `./gradlew` wrapper.
- **Release keystore:** your own `.jks` and the variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (see below).

## Build steps

### Clone the repository

```bash
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks
```

### Compile the Go core

```bash
cd appctr
bash build.sh
cd ..
```

This builds `libtailscale.so`, the daemon. It is a Position Independent Executable (PIE) that Android runs as a child process via `fork/exec`.

It is built with upstream's `ts_include_cli` tag, so the same file is also the CLI. It runs as `tailscale` when its argv[0] is `tailscale` (the app's link in its data directory) or when `TS_BE_CLI=1` is set (the Root Mode wrapper).

:::note
Up to 4.9 the CLI was a second binary, `libtailscale_cli.so`. One file instead of two saves about 12 MB per ABI on the device. `build.sh` deletes a stale `libtailscale_cli.so` it finds in `jniLibs`.
:::

### Build the Android APK

**Debug build** — installs alongside the release app (application id suffix `.dev`) and needs no keystore:

```bash
./gradlew app:assembleDebug
```

**Release build** — requires your own signing keystore.

:::caution
Since 4.0.0 the build refuses to fall back to the debug key. A release APK signed with a throwaway key installs once and can then never be updated by a properly signed build: Android rejects any update whose certificate differs, so the only way out is uninstalling and losing the app state.
:::

Provide all four variables, otherwise `packageRelease` fails with an explanatory error:

```bash
KEYSTORE_FILE="$PWD/tailsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease
```

Create a keystore with `keytool -genkeypair -v -keystore tailsocks.jks -alias tailsocks -keyalg RSA -keysize 4096 -validity 10000` if you do not have one.

## Release build internals

- **R8 minification and resource shrinking are on** (`isMinifyEnabled = true`, `isShrinkResources = true`). The project deliberately contains no reflection-based JSON: models are serialised with `kotlinx.serialization` (`core/AppJson.kt`), resources are never looked up by dynamic name, and the AppFunctions service constructs the KSP-generated `$Aggregated…_Impl` classes directly instead of locating them by reflection. A shrunk build does not depend on keep rules for those paths.
- **JNI keep verification (`verifyReleaseNativeMethods`).** The TUN library registers its Java methods by name inside `JNI_OnLoad`. If R8 ever removes an `external fun`, `System.loadLibrary` throws at runtime (this happened once and crashed every Stop).
  - The task runs automatically after `minifyReleaseWithR8` and before `assembleRelease` / `bundleRelease`. It collects every `external fun` in `app/src/main/java`, checks that each one is matched by a keep rule in R8's `seeds.txt` and that no native member appears in `usage.txt`, and fails the build otherwise.
  - Run it on its own with `./gradlew :app:verifyReleaseNativeMethods` after a release build.
  - If it fails, fix `app/proguard-rules.pro` with a plain `-keep` for `native <methods>` (not `-keepclasseswithmembernames`).
- **Third-party licenses (`app/src/main/assets/licenses.json`).** The Open-source licenses screen reads a committed list. `python3 scripts/licenses/generate.py` writes it from what the build links: the Go module lists recorded in `libgojni.so` and `libtailscale.so`, the artifacts of `releaseRuntimeClasspath` (through `scripts/licenses/shipped-artifacts.init.gradle.kts`), and the C libraries ndk-build compiles. The license texts come from the Go module cache, the POMs and the artifacts.
  - Run it after `appctr/build.sh` and a Gradle build whenever a dependency changes. CI runs `--check` and warns when the list no longer matches.
  - Texts that no artifact carries are in `scripts/licenses/texts/`.
- **Version naming.** `versionName` and `versionCode` come from `version.properties` (`4.5.2` / `4050200`; a debug build adds `-dev`). The commit hash is `BuildConfig.GIT_HASH`. A release builder that updates the app itself adds `-PselfUpdate=false`. The code must follow the name — a release's, or a beta's (`5.1.0-beta.1` / `5009910`) — or Gradle refuses to configure the build. The rule, the two channels and how a release is cut are in [Releasing](/tailsocks/developers/releasing/).

## Installing via ADB

ABI splits are enabled, so each build produces a universal APK plus one per ABI (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) under `app/build/outputs/apk/<debug|release>/`.

```bash
# Debug build
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk

# Release build
adb install -r app/build/outputs/apk/release/app-universal-release.apk

# Open the main screen
adb shell am start -n io.github.bropines.tailscaled/io.github.bropines.tailscaled.ui.MainActivity
```
