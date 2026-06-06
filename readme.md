<p align="center">
  <img src="docs/logo.svg" alt="TailSocks Icon" width="128" height="128" />
</p>

<h1 align="center">TailSocks</h1>

<p align="center">
  <strong>Advanced Tailscale Client for Android with Userspace Networking & Transparent TUN VPN</strong>
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
<summary><strong>Click to expand screenshots</strong></summary>

<table width="100%">
  <tr>
    <td width="33%" align="center">
      <strong>Main Dashboard</strong><br/>
      <img width="414" height="898" alt="Main Dashboard" src="https://github.com/user-attachments/assets/1d948d16-b6bf-4652-9bce-6e862bdfd90d" />
    </td>
    <td width="33%" align="center">
      <strong>Account Switch</strong><br/>
      <img width="414" height="898" alt="Account Switch" src="https://github.com/user-attachments/assets/4aa9e88d-c02b-472a-96ad-267314233e70" />
    </td>
    <td width="33%" align="center">
      <strong>Peers</strong><br/>
      <img width="414" height="898" alt="Peers" src="https://github.com/user-attachments/assets/26b374e9-a25e-4395-9b84-27cf756e59f1" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>Logs</strong><br/>
      <img width="414" height="898" alt="Logs" src="https://github.com/user-attachments/assets/57813442-7158-49ea-95dc-f2b6377f0030" />
    </td>
    <td width="33%" align="center">
      <strong>Taildrop™</strong><br/>
      <img width="414" height="898" alt="Taildrop" src="https://github.com/user-attachments/assets/41d23cbc-4d67-4ec4-9477-2f5e95c1ec8d" />
    </td>
    <td width="33%" align="center">
      <strong>Taildrive™ Shares</strong><br/>
      <img width="414" height="898" alt="Taildrive Shares" src="https://github.com/user-attachments/assets/d02c7e01-0454-42ce-8b0e-83faca773b66" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>DNS Management</strong><br/>
      <img width="414" height="898" alt="DNS" src="https://github.com/user-attachments/assets/faea8000-94ae-4c55-b4be-8d577d5a5fa9" />
    </td>
    <td width="33%" align="center">
      <strong>Settings (App)</strong><br/>
      <img width="414" height="898" alt="Settings App" src="https://github.com/user-attachments/assets/3cc0d9c6-77a9-4444-a020-b8a606e54952" />
    </td>
    <td width="33%" align="center">
      <strong>Settings (Profile)</strong><br/>
      <img width="414" height="898" alt="Settings Profile" src="https://github.com/user-attachments/assets/c3421fb3-cf46-4029-bb2a-f8f895114921" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>Network Diagnostics</strong><br/>
      <img width="414" height="898" alt="Netcheck" src="https://github.com/user-attachments/assets/1f711954-7e2e-430a-9d57-5a7130105cae" />
    </td>
    <td width="33%" align="center">
      <strong>Serve & Funnel</strong><br/>
      <img width="414" height="898" alt="Serve and Funnel" src="https://github.com/user-attachments/assets/a4538bf6-edc6-45d2-a648-5a377797c0a8" />
    </td>
    <td width="33%" align="center">
      <strong>Send to… (Taildrop™)</strong><br/>
      <img width="414" height="898" alt="Send to Taildrop" src="https://github.com/user-attachments/assets/fe051494-745c-431e-ad77-05bdd05857cb" />
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
│   SOCKS5 Proxy Mode   │       TUN VPN Mode (optional)          │
│  Per-app proxying via  │  System-wide routing via native        │
│  local SOCKS5 server   │  hev-socks5-tunnel (C library)        │
│  (no VpnService)       │  Full/Split tunnel + app exclusions   │
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
| [Roadmap](docs/ROADMAP.md) | Planned features and short-term goals |
| [Changelog](CHANGELOG.md) | Full version history |

---

## 🤝 Credits & Acknowledgements

| | |
|-|-|
| **App Developer** | [Bropines](https://github.com/bropines) |
| **Upstream Patches** | [Asutorufa](https://github.com/Asutorufa) — Android [tailscale patches](https://github.com/Asutorufa/tailscale) |
| **TUN Engine** | [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — native SOCKS5-to-TUN implementation |
| **Core Engine** | [Tailscale Inc.](https://github.com/tailscale/tailscale) — userspace networking engine (`tsnet`) |
| **AI Assistant** | [Google Gemini](https://gemini.google.com/) — interface development and LocalAPI research |

---

## 📜 License

Distributed under the **BSD-3-Clause** License. See [`LICENSE`](LICENSE) for details.

*Tailscale, Taildrop, Taildrive, MagicDNS, and Funnel are trademarks of Tailscale Inc. This project is an independent open-source contribution and is not affiliated with Tailscale Inc.*
