# 🛠 Building TailSocks

TailSocks employs a highly automated and modular build pipeline that avoids the technical debt of maintaining a massive git fork of Tailscale.

## The Dynamic Injection Pipeline

Instead of resolving endless merge conflicts, our build script uses a dynamic code injection strategy:
1. **Fetch Fresh Core:** Downloads a lightweight archive of the absolute latest official Tailscale source code.
2. **Surgical Injection:** Injects a single required Go file (an `anet` interface fix) directly into the downloaded source.
3. **Aggressive Trimming:** Uses a massive array of Go build tags (`ts_omit_systray`, `ts_omit_kube`, `ts_omit_aws`, `ts_omit_bgp`, `ts_omit_ssh`, `ts_omit_taildrop`, etc.) to strip out desktop Linux and enterprise features. 
4. **Result:** A drastically smaller `libtailscaled.so` binary that compiles quickly and operates efficiently in the Android sandbox.

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
