# 🛠 Building TailSocks

TailSocks employs a highly automated and modular build pipeline that avoids the technical debt of maintaining a massive git fork of Tailscale.

## The Dynamic Injection Pipeline

Instead of resolving endless merge conflicts, our build script uses a dynamic code patch pipeline:
1. **Fetch Fresh Core:** Downloads a lightweight archive of the stable official Tailscale source code (defaults to the latest release, e.g., v1.98.x).
2. **Atomic Patch Injection:** Applies a series of modular, atomic `.patch` files (located under `appctr/patches/`) to adapt the Tailscale source code for mobile features (SOCKS5 proxy support, custom file systems for Taildrop, LocalAPI certificate generation, and Android-specific network monitoring).
3. **Aggressive Trimming:** Uses a massive array of Go build tags (`ts_omit_systray`, `ts_omit_kube`, `ts_omit_aws`, `ts_omit_bird`, `ts_omit_drive`, etc.) to strip out desktop Linux and enterprise features. 
4. **Result:** Highly optimized `libtailscale.so` and `libtailscale_cli.so` binaries that compile quickly and operate efficiently in the Android sandbox.

## Build Steps

**1. Clone the repository:**
```bash
git clone https://github.com/bropines/tailscaled-socks5-android.git
cd tailscaled-socks5-android
```

**2. Compile the Go core (`libtailscaled.so`):**
Because Tailscale removed CLI commands from the main daemon, we compile two separate Position Independent Executables (PIE): `tailscaled` (the core) and `tailscale` (the CLI console). This allows Android to execute them as independent child processes via `fork/exec`.
```bash
cd appctr
sh build.sh
cd ..
```

**3. Build the Android APK:**
```bash
./gradlew app:assembleRelease
```
*Note: R8 minification is fully supported. We utilize `@Keep` annotations on Kotlin data classes to ensure `Gson` can correctly parse JSON outputs from the Tailscale CLI in release builds.*
