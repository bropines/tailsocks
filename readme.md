
# 🧦 TailSocks (Tailscaled Proxy for Android)

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/bropines/tailsocks)](https://github.com/bropines/tailsocks/releases)
[![Stable Core](https://img.shields.io/badge/Tailscale_Core-v1.96.x-green.svg)](https://github.com/tailscale/tailscale/tags)

**TailSocks** is a highly optimized, lightweight fork of the Android client for [Tailscale](https://tailscale.com/), operating **exclusively in SOCKS5 proxy mode**. 

### 🏗 Architecture
*   **Dynamic Stable Core:** The build script automatically fetches and patches the **latest stable Tailscale tag** (e.g., `v1.96.5`) during compilation, injecting Android-specific fixes (`anet` netmon) and SOCKS5 auth directly into the upstream source.
*   **No VpnService:** Unlike the official app, TailSocks avoids the Android `VpnService` to bypass strict system limitations (such as `netlinkrib` access errors). The core runs in a stable `userspace-networking` mode, exposing a local SOCKS5 port.

## 🚀 Key Features

* **Dynamic Core Injection:** The build script automatically downloads the freshest official Tailscale source and injects Android-specific fixes at compile time, eliminating the need to maintain outdated forks.
* **Hyper-Optimized Binary:** The core daemon is compiled with extensive build tags, completely stripping out heavy desktop/enterprise features (D-Bus, Kubernetes, AWS, BGP, Taildrop, built-in SSH) for lightning-fast startup and drastically reduced binary size.
* **Advanced Local DNS Proxy:** A custom Go-based local DNS server resolves Android's UDP routing restrictions. It seamlessly handles MagicDNS and Split DNS by wrapping UDP queries into TCP frames and pushing them through the SOCKS5 tunnel directly to Tailscale's internal coordinator (100.100.100.100). 
* **Ad-Blocker Synergy:** Zero DNS leaks. Internal network queries (e.g., `*.ts.net` or custom domains like `olegdev.com`) are securely routed through the daemon, while all external traffic bypasses the Go daemon entirely, routing natively to filters like AdGuard or custom DoH providers.
* **Native Web UI:** Seamlessly access the official Tailscale Web UI right from the Android app, hosted locally at `127.0.0.1:8080` once the tunnel is up.
* **Rock-Solid Stability:** Implements a strict userspace state-machine reset mechanism to completely eliminate deadlocks (`i/o timeout`) during daemon restarts.

## 🛠 How to Build & Architecture

- 👉 **[See the full Build Instructions in `docs/BUILDING.md`](docs/BUILDING.md)**
- 👉 **[Read more about the architecture, PIE binaries, and DNS wrapping in `docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)**
- 👉 **[Setup TailSocks DNS with AdGuard in `docs/ADGUARD.md`](docs/ADGUARD.md)**

## 🗺️ Roadmap
- 👉 **[Check out the detailed Roadmap and TODOs in `docs/ROADMAP.md`](docs/ROADMAP.md)**

## 📜 License & Credits

Distributed under the **BSD-3-Clause** License.
* **Developer:** [Bropines](https://github.com/bropines)
