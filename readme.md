<p align="center">
  <img src="docs/logo.svg" alt="TailSocks Icon" width="128" height="128" />
</p>

<h1 align="center">TailSocks</h1>

<p align="center">
  <strong>Advanced Tailscale Client for Android with Userspace Networking & Transparent TUN VPN</strong>
</p>

<p align="center">
  <strong>English</strong> | <a href="readme_ru.md">Русский</a>
</p>

<p align="center">
  <a href="https://github.com/bropines/tailsocks/releases/latest"><img src="https://img.shields.io/github/v/release/bropines/tailsocks?style=for-the-badge&logo=github&logoColor=white&label=Latest%20Release&color=2ea44f" alt="Latest Release" /></a>
  <a href="https://github.com/bropines/tailsocks/releases"><img src="https://img.shields.io/github/downloads/bropines/tailsocks/total?style=for-the-badge&logo=android&logoColor=white&label=Downloads&color=3ddc84" alt="Downloads" /></a>
  <a href="https://github.com/tailscale/tailscale/releases/tag/v1.98.3"><img src="https://img.shields.io/badge/Tailscale_Core-v1.98.3-blue?style=for-the-badge&logo=tailscale&logoColor=white" alt="Tailscale Core" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-BSD_3--Clause-orange?style=for-the-badge" alt="License" /></a>
</p>

<p align="center">
  <a href="https://github.com/bropines/tailsocks/releases/latest/download/app-release.apk">
    <img src="https://img.shields.io/badge/⬇_Download_APK-Release-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download Release APK" />
  </a>
  &nbsp;
  <a href="https://github.com/bropines/tailsocks/releases">
    <img src="https://img.shields.io/badge/⬇_All_Releases-GitHub-24292e?style=for-the-badge&logo=github&logoColor=white" alt="All Releases" />
  </a>
</p>

---

TailSocks is a high-performance Android client for [Tailscale](https://tailscale.com/) that operates in **userspace-networking mode** via `tsnet`. It provides a complete Tailscale environment — including [Taildrop™](https://tailscale.com/kb/1106/taildrop), [Exit Nodes](https://tailscale.com/kb/1103/exit-nodes), [Serve & Funnel](https://tailscale.com/kb/1242/tailscale-serve), and [Taildrive™](https://tailscale.com/kb/1369/taildrive) — without requiring Android's `VpnService` permission, enabling seamless coexistence with other VPN and firewall applications.

Optionally, TailSocks supports a **transparent TUN VPN mode** powered by the native [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) engine, providing full or split tunnel routing for system-wide connectivity.

---

## ✨ Features

### Networking & Connectivity

| Feature | Description |
|---------|-------------|
| **Native LocalAPI** | 100% CLI-less daemon management via Unix socket (`tailscaled.sock`) using LocalAPI v0. No shell commands. |
| **SOCKS5 Proxy** | Built-in local SOCKS5 proxy server with optional authentication for per-app routing. |
| **Control Plane Proxy** | Route coordination server traffic through a custom SOCKS5/HTTP proxy for restricted regions. |
| **TUN VPN Mode** | Transparent system-wide VPN via native `hev-socks5-tunnel` — full tunnel & split tunnel, per-app exclusions, custom gateway IP. |
| **[Exit Nodes](https://tailscale.com/kb/1103/exit-nodes) ©** | Route all internet traffic through any authorized Tailscale peer with auto-healing and LAN access. |
| **[MagicDNS](https://tailscale.com/kb/1081/magicdns) ©** | In-memory peer resolution (0ms), Split DNS over SOCKS5 TCP, smart upstream fallback with DoH support. |
| **NAT Traversal** | Real-time `InMagicSock` connectivity monitoring. STUN/DERP diagnostics via native netcheck. |

### Services & File Sharing

| Feature | Description |
|---------|-------------|
| **[Tailscale Serve & Funnel](https://tailscale.com/kb/1242/tailscale-serve) ©** | Expose local ports to your Tailnet or the public internet. TCP & HTTPS modes, TLS certificate export. |
| **[Tailscale Services (`svc:`)](https://tailscale.com/kb/1438/virtual-ip) ©** | Create named virtual services with dedicated VIPs and DNS names, managed from native UI. |
| **[Taildrop™](https://tailscale.com/kb/1106/taildrop) ©** | Send & receive files between Tailnet devices. Inbox hub, system Share Sheet integration, DocumentsProvider. |
| **[Taildrive™](https://tailscale.com/kb/1369/taildrive) ©** | Share local folders over WebDAV. SAF integration, remote share mounting, SOCKS5-proxied access. Cross-platform path case-insensitivity fixes. |

### Management & Administration

| Feature | Description |
|---------|-------------|
| **Multi-Account Isolation** | Strict per-profile data separation — independent state dirs, preferences, keypairs, and Taildrop folders. |
| **Tailscale Admin API** | Full `api.tailscale.com/v2` integration — manage devices, DNS, users, services, webhooks, ACLs, and audit logs. |
| **Biometric Lock** | Admin Console protected by fingerprint/face authentication. |
| **Auth Keys** | Generate, view, and revoke authentication keys from inside the app. |
| **Data Portability** | Full encrypted app state backups (ZIP) and individual account exports (JSON). |

### User Experience

| Feature | Description |
|---------|-------------|
| **Compact Dashboard** | High-density 2×4 grid — Console, Peers, Logs, Files, DNS, Netcheck, Settings, Serve. |
| **Material 3 Theming** | System, Light, Dark, AMOLED Black modes. 7 color presets + Material You dynamic colors. |
| **Localization** | Crowdin-compatible i18n system. Russian language included. |
| **Home Screen Widgets** | Jetpack Glance widgets — Service Toggle, Exit Node, Stats Dashboard, Serve status. |
| **Quick Settings Tile** | System Quick Settings tile with active profile display and account switching. |
| **Network Diagnostics** | Native netcheck with DERP latency visualization, NAT type detection, and public IP reporting. |

---

## 📸 Screenshots

<details>
<summary><strong>Interface Screenshots</strong></summary>

<table width="100%">
  <tr>
    <td width="33%" align="center">
      <strong>Main Dashboard</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/9366761f-f7de-4802-96ea-269d49bfffd3" />
    </td>
    <td width="33%" align="center">
      <strong>Account Switcher</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/b91dfc72-774c-4ad1-8eb0-77bd076ce1e9" />
    </td>
    <td width="33%" align="center">
      <strong>Peers List</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/576774f6-8371-437b-b610-1555e1af12c0" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>System Logs</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/2056b039-201e-4f4a-b11f-5fdaaad38006" />
    </td>
    <td width="33%" align="center">
      <strong>Taildrop™ (Incoming)</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/7f92c77b-da1d-44d7-b082-5bca6c7f86ef" />
    </td>
    <td width="33%" align="center">
      <strong>Taildrive™ Shares</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/0985d06b-288f-4f08-b9ce-1919cbf91d59" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>DNS Management</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/faea8000-94ae-4c55-b4be-8d577d5a5fa9" />
    </td>
    <td width="33%" align="center">
      <strong>App Settings</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/c4e59ea7-47e1-40c3-9d71-c35b0aa1d86a" />
    </td>
    <td width="33%" align="center">
      <strong>Profile Settings</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/38180ff1-fb2e-4aa4-8490-424696982f87" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>Network Diagnostics</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/7b7c64d9-2a6f-4693-8b60-756159b7e96f" />
    </td>
    <td width="33%" align="center">
      <strong>Serve & Funnel</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/940bb4fe-da87-4d5c-a1df-4342b8d9ca03" />
    </td>
    <td width="33%" align="center">
      <strong>Send via Taildrop™</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/209669fb-803f-4e63-b0f2-3a13ac8d8840" />
    </td>
  </tr>
</table>

</details>

---

## 🏗️ Architecture

TailSocks is built as a hybrid multi-layer system:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI (Kotlin)                  │
│  Dashboard · Peers · Logs · DNS · Netcheck · Serve · Settings   │
│  Admin API Console · Taildrive · Taildrop · TUN Config          │
├─────────────────────────────────────────────────────────────────┤
│                   JNI / Gomobile Bridge (appctr)                │
│  LocalAPI Client · DNS Proxy · IPN Bus · Netcheck · Taildrop    │
├─────────────────────────────────────────────────────────────────┤
│              Tailscale Daemon (libtailscale.so)                 │
│  tsnet · WireGuard · magicsock · DERP · Serve/Funnel · Drive    │
├───────────────────────┬─────────────────────────────────────────┤
│   SOCKS5 Proxy Mode   │       TUN VPN Mode (optional)           │
│  Per-app proxying via │   System-wide routing via native        │
│  local SOCKS5 server  │   hev-socks5-tunnel (C library)         │
│  (no VpnService)      │   Full/Split tunnel + app exclusions    │
└───────────────────────┴─────────────────────────────────────────┘
```

### Core Components

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Daemon** | Go → `libtailscale.so` (PIE) | Patched Tailscale core compiled with aggressive build tags to strip desktop/enterprise features. Targets `arm64`, `arm`, `x86`, `x86_64`. |
| **Bridge** | Go → `appctr.aar` (Gomobile) | High-speed JNI bridge handling LocalAPI calls, DNS proxying, IPN bus monitoring, netcheck, Taildrop, and Taildrive WebDAV. |
| **App** | Kotlin + Jetpack Compose | Material 3 UI, foreground service lifecycle, Android system integrations (SAF, Widgets, Quick Settings, Share Sheet). |
| **TUN Engine** | C → `hev-socks5-tunnel` | Optional transparent VPN interface. Routes traffic through the SOCKS5 proxy at kernel level. Per-app and per-IP exclusions. |

### Key Design Patterns

- **Stateless Configuration:** Every config update is explicit. Serve/Funnel uses a "Reset-then-Apply" pattern (POST `{}` → POST new config) to prevent stale daemon state.
- **Passive Daemon Management:** No aggressive polling loops. The daemon manages its own lifecycle, policy sync, and reconnection.
- **Account Isolation:** State in `files/states/{id}/`, preferences in `appctr_{id}`. Full daemon restart on profile switch.
- **DNS Wrapping:** MagicDNS resolved from in-memory node cache. Split DNS wrapped as TCP-over-SOCKS5. Fallback chain: SOCKS5 UDP → Direct UDP → DoH.
- **410 Wall Mitigation:** Configuration updates are blocked while a Login URL is active to protect authentication sessions.

### Upstream Patches

TailSocks maintains 11 minimal atomic patches in [`appctr/patches/`](appctr/patches/) to inject capabilities not exposed via LocalAPI:

| Patch | Purpose |
|-------|---------|
| `01-enable-socks-android` | Enable SOCKS5 support in userspace-networking on Android |
| `02-socks5-auth` | Add username/password fields to the outbound SOCKS5 listener |
| `03-taildrop-monolithic-fs` | Pure-Go `fsFileOps` to avoid JNI panics in Taildrop |
| `04-vip-services` | Append VIP services to `HostInfo` for coordination server visibility |
| `05-localapi-cert` | Enable `/cert` endpoint compilation on Android |
| `06-android-netmon` | Custom `netmon.InterfaceGetter` for Android 10+ `netlink` restrictions |
| `07-taildrive-android` | Android-specific Taildrive adaptations |
| `08-netstack-cgnat` | CGNAT routing fix for netstack |
| `09-netstack-loopback` | Loopback routing for self-addressed packets in netstack |
| `10-taildrive-userspace-dial` | Route remote peer WebDAV via `tsdial.Dialer` |
| `11-noop-dns-fallback` | DNS fallback env var injection for SERVFAIL prevention |

---

## 🚀 Getting Started

### Download

Grab the latest APK from the [Releases](https://github.com/bropines/tailsocks/releases/latest) page, or use the download buttons at the top of this README.

> **Supported architectures:** `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`  
> **Minimum Android version:** 5.0 (API 21)

### Build from Source

<details>
<summary><strong>Build instructions</strong></summary>

**Prerequisites:**
- Android NDK (set `ANDROID_NDK_HOME`)
- Go 1.23+
- `gomobile` (`go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`)
- Android SDK with Gradle

**1. Clone:**
```bash
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks
```

**2. Compile Go core** (downloads Tailscale v1.98.3 source, patches, and cross-compiles):
```bash
cd appctr
bash build.sh
cd ..
```

**3. Build APK:**
```bash
./gradlew app:assembleRelease
```

> The build script automatically downloads the correct Tailscale version, applies all patches, and compiles PIE binaries for 4 architectures. No fork maintenance required.

</details>

---

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [Architecture Deep Dive](docs/ARCHITECTURE.md) | DNS wrapping, account isolation, netcheck, and patch analysis |
| [Build Instructions](docs/BUILDING.md) | NDK setup, Go core compilation, dynamic patch pipeline |
| [Project Retrospective](docs/RETROSPECTIVE.md) | Evolution from PoC to the current architecture |
| [AdGuard Setup](docs/ADGUARD.md) | Coexistence with system-wide ad blockers |
| [Serve & Funnel Guide](docs/SERVE_FUNNEL_GUIDE.md) | Exposing local ports and virtual services |
| [Root Integration & Service Guide](docs/ROOT.md) | System-wide root autostart daemon, service.d, and CLI wrapper |
| [Tasker & Automation Guide](docs/AUTOMATION.md) | Intent automation setup for Tasker, MacroDroid, Automate, and ADB |
| [Roadmap](docs/ROADMAP.md) | Planned features and short-term goals |
| [Changelog](CHANGELOG.md) | Full version history |

## 🌐 Restricted Regions & DPI Bypass

For users in restricted regions (e.g., where `controlplane.tailscale.com` is blocked/dropped), TailSocks offers an in-app bypass mechanism for the control plane:

### 1. Control Plane DPI Bypass (ByeDPI JNI)
TailSocks bundles a native JNI implementation of [ByeDPI](https://github.com/hufyhang/byedpi) directly inside the app process. This allows bypassing SNI-based deep packet inspection (DPI) without spawning external binary processes.
* **Security:** ByeDPI binds strictly to a randomized loopback IP (e.g., `127.182.201.43`) and a randomized port in the `127.0.0.0/8` subnet upon every startup. This prevents other applications on the device from discovering or connecting to the proxy via simple port scanning.
* **Usage:** Enable **DPI Bypass (ByeDPI)** in Settings -> Network Tab -> Control Proxy settings and configure custom ByeDPI flags (default: `-s 1 -d split -r`).

---

## ⚡ Tasker & Automation Integration

TailSocks supports background control via **Android Broadcast Intents**. You can automate connections using Tasker, MacroDroid, Automate, or `adb`.

* **Target Receiver:** `io.github.bropines.tailscaled/.core.TaskerReceiver` (or package `io.github.bropines.tailscaled`)
* **Supported Actions:**
  * `io.github.bropines.tailscaled.action.CONNECT` (or `io.github.bropines.tailscaled.START`) — Start connection
  * `io.github.bropines.tailscaled.action.DISCONNECT` (or `io.github.bropines.tailscaled.STOP`) — Stop connection
  * `io.github.bropines.tailscaled.action.TOGGLE` (or `io.github.bropines.tailscaled.TOGGLE`) — Toggle connection state
  * `io.github.bropines.tailscaled.action.RESTART` (or `io.github.bropines.tailscaled.RESTART`) — Restart connection

#### Tasker Configuration Example:
1. Action: **System** → **Send Intent**
2. Action: `io.github.bropines.tailscaled.action.CONNECT`
3. Target: **Broadcast Receiver**
4. Package: `io.github.bropines.tailscaled`



---

## 🤝 Credits & Acknowledgements

| | |
|-|-|
| **App & Patches** | [Bropines](https://github.com/bropines) — app development, architecture, and the majority of upstream patches |
| **Initial Android Patches** | [Asutorufa](https://github.com/Asutorufa) — original Android networking (`anet`) and network monitor (`netmon`) [patches](https://github.com/Asutorufa/tailscale) that served as a starting point |
| **DPI Bypass** | [hufyhang/byedpi](https://github.com/hufyhang/byedpi) — local HTTP/SOCKS5 DPI bypass utility |
| **TUN Engine** | [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — native SOCKS5-to-TUN implementation |
| **Core Engine** | [Tailscale Inc.](https://github.com/tailscale/tailscale) — userspace networking engine (`tsnet`) |
| **AI Assistant** | [Google Gemini](https://gemini.google.com/) — interface development, LocalAPI research, and patch engineering |

---

## 📜 License

Distributed under the **BSD-3-Clause** License. See [`LICENSE`](LICENSE) for details.

*Tailscale, Taildrop, Taildrive, MagicDNS, and Funnel are trademarks of Tailscale Inc. This project is an independent open-source contribution and is not affiliated with Tailscale Inc.*
