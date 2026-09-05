# 🛠 Building TailSocks

TailSocks employs a highly automated and modular build pipeline that avoids the technical debt of maintaining a massive git fork of Tailscale.

## The Dynamic Injection Pipeline

Instead of resolving endless merge conflicts, our build script uses a dynamic code patch pipeline:
1. **Fetch Fresh Core:** Downloads a lightweight archive of the official Tailscale source code at the version pinned in `appctr/TAILSCALE_VERSION` (currently `v1.102.1`).
2. **Atomic Patch Injection:** Applies a series of modular, atomic `.patch` files (located under `appctr/patches/`) to adapt the Tailscale source code for mobile features (SOCKS5 proxy support, custom file systems for Taildrop, LocalAPI certificate generation, and Android-specific network monitoring).
3. **Aggressive Trimming:** Uses a massive array of Go build tags (`ts_omit_systray`, `ts_omit_kube`, `ts_omit_aws`, `ts_omit_bird`, `ts_omit_drive`, etc.) to strip out desktop Linux and enterprise features. 
4. **Result:** Highly optimized `libtailscale.so` and `libtailscale_cli.so` binaries that compile quickly and operate efficiently in the Android sandbox.

## Build Steps

**1. Clone the repository:**
```bash
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks
```

**2. Compile the Go core (`libtailscale.so`):**
Because Tailscale removed CLI commands from the main daemon, we compile two separate Position Independent Executables (PIE): `tailscaled` (the core) and `tailscale` (the CLI console). This allows Android to execute them as independent child processes via `fork/exec`.
```bash
cd appctr
bash build.sh
cd ..
```

**3. Build the Android APK:**

*Debug build* — installs alongside the release app (application id suffix `.dev`), needs no keystore:
```bash
./gradlew app:assembleDebug
```

*Release build* — **requires your own signing keystore**. Since 4.0.0 the build refuses to fall back to the debug key: a release APK signed with a throwaway key installs once and can then never be updated by a properly signed build (Android rejects any update whose certificate differs, so the only way out is uninstalling and losing the app state). Provide all four variables, otherwise `packageRelease` fails with an explanatory error:
```bash
KEYSTORE_FILE="$PWD/tailsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease
```
Create a keystore with `keytool -genkeypair -v -keystore tailsocks.jks -alias tailsocks -keyalg RSA -keysize 4096 -validity 10000` if you do not have one.

## Release build internals

* **R8 minification and resource shrinking are on** (`isMinifyEnabled = true`, `isShrinkResources = true`). The project deliberately contains no reflection-based JSON: models are serialised with `kotlinx.serialization` (`core/AppJson.kt`), resources are never looked up by dynamic name, and the AppFunctions service constructs the KSP-generated `$Aggregated…_Impl` classes directly instead of locating them by reflection, so a shrunk build does not depend on keep rules for those paths.
* **JNI keep verification (`verifyReleaseNativeMethods`).** The TUN library registers its Java methods by name inside `JNI_OnLoad`; if R8 ever removes an `external fun`, `System.loadLibrary` throws at runtime (this happened once and crashed every Stop). The task runs automatically after `minifyReleaseWithR8` and before `assembleRelease` / `bundleRelease`: it collects every `external fun` in `app/src/main/java`, checks that each one is matched by a keep rule in R8's `seeds.txt` and that no native member appears in `usage.txt`, and fails the build otherwise. You can run it on its own with `./gradlew :app:verifyReleaseNativeMethods` after a release build. If it fails, fix `app/proguard-rules.pro` with a plain `-keep` for `native <methods>` (not `-keepclasseswithmembernames`).
* **Version naming:** `versionCode` is the git commit count plus 500 and `versionName` is `v<latest tag>-<6-char-hash>` with `.release` / `-dev` suffixes; use `fetch-depth: 0` in CI.

## Installing via ADB

ABI splits are enabled, so each build produces a universal APK plus one per ABI (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) under `app/build/outputs/apk/<debug|release>/`.

```bash
# Debug build
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk

# Release build
adb install -r app/build/outputs/apk/release/app-universal-release.apk
```
