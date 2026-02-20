# 🧦 TailSocks (Tailscaled Proxy for Android)

**TailSocks** is a lightweight fork of the Android client for [Tailscale](https://tailscale.com/), operating **exclusively in SOCKS5 proxy mode**.

Unlike the official application, TailSocks does not use the Android `VpnService` to capture all system traffic. The core runs in `userspace-networking` mode, exposing a local SOCKS5 port (default `127.0.0.1:1055`). This allows you to route Tailscale network traffic selectively (e.g., via browser plugins or specific proxy clients) while keeping another system-wide VPN active.

## 🚀 Key Features
* **Pure SOCKS5:** Access the Tailscale network without forced full-device routing.
* **Exit Nodes:** Full support for routing traffic through Exit Nodes via proxy.
* **Built-in SSH/SFTP:** Spins up a local SSH server (default port `1056`) for secure device access.
* **Strict Validation:** Prevents idle or zombie daemon startups without a valid Auth Key.
* **Custom DNS & Routes:** Capability to accept routes and MagicDNS.

## 🛠 Build Instructions

To build this project, you will need **Go** and the **Android NDK**.

1. Clone the repository:

```bash
git clone https://github.com/bropines/tailscaled-socks5-android.git
cd tailscaled-socks5-android
```

3. Compile the Go core (`libtailscaled.so`):

```bash
cd appctr
sh build.sh
cd ..

```


3. Build the Android APK:
```bash
./gradlew app:assembleDebug
```

## ⚠️ Known Limitations & Technical Debt

Attempts to expand the functionality revealed architectural limitations of running the official `tailscaled` daemon outside the native app context (via `exec.Command`):

* **Taildrop is disabled (JNI Panic):** File sharing is stripped out at compile time via the `ts_omit_taildrop` tag. If enabled, `tailscaled` attempts to use JNI to locate the system `Downloads` directory. Because it runs as a detached child process without a JVM context (`JNIEnv`), this results in an immediate panic (`exit status 2`).
* **Full VPN Mode disabled (fdsan crash):** Attempts to pass a `VpnService` file descriptor (FD) into Go (directly or via `tun2socks`) crash the Android app (`fdsan: double-close of file descriptor`). Kotlin's Garbage Collector and the external Go process conflict over the FD lifecycle.

*Future implementation of these features requires abandoning `exec.Command` and migrating the core to an in-process execution using `tailscale.com/tsnet` with proper JNIEnv passing.*

## 📜 License

Based on the open-source Tailscale client and the [Asutorufa](https://github.com/Asutorufa/tailscale) fork.
Distributed under the **BSD-3-Clause** License.

**Developer:** Bropines(Pinus)
