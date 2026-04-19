
# 🦔 TailSocks (Tailscaled Proxy for Android)

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/bropines/tailsocks)](https://github.com/bropines/tailsocks/releases)
[![Stable Core](https://img.shields.io/badge/Tailscale_Core-v1.96.x-green.svg)](https://github.com/tailscale/tailscale/tags)

**TailSocks** is a highly optimized, lightweight Android client for [Tailscale](https://tailscale.com/), operating **exclusively in SOCKS5/HTTP proxy mode**. It provides a full-featured Tailscale experience (including Taildrop and Exit Nodes) without requiring Android's `VpnService`.

### 🏗 Architecture: The Hedgehog Bridge
TailSocks uses a unique bridge architecture to connect the Go-based Tailscale core with the Kotlin/Compose UI:
*   **The Core (`libtailscale.so`):** A PIE-compiled binary of the official Tailscale daemon, stripped of desktop bloat but patched for Android network monitoring.
*   **The Bridge (`appctr`):** A Go module compiled into an `.aar` via `gomobile`. It manages the daemon's lifecycle, handles account isolation, and streams logs to the UI.
*   **No VpnService:** TailSocks operates in `userspace-networking` mode. This bypasses system-level restrictions and allows TailSocks to run alongside other VPN apps (like AdGuard or SagerNet).

## 🚀 Key Features

*   **Taildrop Hub:** A robust, custom-built implementation of Tailscale's file-sharing protocol. Includes an **Incoming Inbox**, **Sent History**, and **Automatic Save** to Android storage via the Storage Access Framework.
*   **Multi-Account Manager:** Support for multiple Tailscale profiles with complete isolation. Each account has its own state directory, machine keys, and settings. Switch between accounts with a single tap.
*   **Advanced Exit Node Selector:** Integrated UI to discover and select Exit Nodes from your Tailnet. Includes a "Self-Healing" mechanism that automatically clears invalid Exit Node configurations when switching networks or accounts.
*   **Deep Network Diagnostics:** Real-time technical insights for every node in your map:
    *   **InMagicSock:** See if you have a direct P2P connection (NAT traversal status).
    *   **Traffic Counters:** Live RX/TX byte tracking.
    *   **Handshake Info:** View the exact time of the last successful WireGuard handshake.
*   **SagerNet & Nekobox Integration:** Generate and copy SOCKS5/UDP links (with authentication) to instantly import your Tailscale connection into third-party proxy clients.
*   **Advanced DNS Proxy:** A custom Go-based local DNS server (port 1053) that resolves MagicDNS (`*.ts.net`) and Split DNS by wrapping UDP queries into TCP frames and pushing them through the SOCKS5 tunnel.
*   **Native Web UI:** Access the official Tailscale Web administrator interface locally at `127.0.0.1:8080`.
*   **Dynamic Core Injection:** The build system automatically fetches, patches, and compiles the **latest stable Tailscale version** (currently `v1.96.x`) at build time.

## 🛠 How to Build & Architecture

- 👉 **[See the full Build Instructions in `docs/BUILDING.md`](docs/BUILDING.md)**
- 👉 **[Read more about the architecture, PIE binaries, and DNS wrapping in `docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)**
- 👉 **[Setup TailSocks DNS with AdGuard in `docs/ADGUARD.md`](docs/ADGUARD.md)**

## 🗺️ Roadmap
- 👉 **[Check out the detailed Roadmap and TODOs in `docs/ROADMAP.md`](docs/ROADMAP.md)**

## 📜 License & Credits

Distributed under the **BSD-3-Clause** License.
* **Developer:** [Bropines](https://github.com/bropines)
* **Core:** [Tailscale](https://github.com/tailscale/tailscale)
