# TailSocks: Advanced Tailscale Proxy for Android

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/bropines/tailsocks)](https://github.com/bropines/tailsocks/releases)
[![Core](https://img.shields.io/badge/Tailscale_Core-v1.96.x-green.svg)](https://github.com/tailscale/tailscale/tags)

TailSocks is a high-performance, lightweight Android client for [Tailscale](https://tailscale.com/) that operates exclusively in **userspace-networking mode**. It provides a complete Tailscale environment—including Taildrop and Exit Nodes—without utilizing Android's `VpnService`, enabling seamless coexistence with other VPN and firewall applications.

## ✨ Key Features

*   **Native LocalAPI v2:** 100% CLI-less management via a high-performance Go-HTTP bridge. Near-instant status updates and configuration changes.
*   **Multi-Account System:** Create and switch between multiple independent Tailscale profiles, each with its own isolated state and machine keys.
*   **Tailscale Serve & Funnel:** Expose local services to your Tailnet or the public internet with a native UI for managing HTTPS proxies, TCP forwarding, and virtual services (`svc:`).
*   **Taildrop Hub:** Comprehensive file sharing support. Receive, preview, and save files to any folder using Android's Storage Access Framework.
*   **Exit Node Support:** Full integration with Tailnet Exit Nodes, featuring automated self-healing during account or network transitions.
*   **Deep Diagnostics:** Real-time visibility into the userspace engine, including WireGuard handshake telemetry and NAT traversal status (`InMagicSock`).
*   **MagicDNS & Split DNS:** Low-latency DNS resolution with in-memory caching and corporate domain routing.

## 🏗 System Architecture

TailSocks operates as a hybrid application:
1.  **Tailscale Core:** A pure Go environment (based on `tsnet`) compiled for Android and patched for mobile environments.
2.  **Go-Kotlin Bridge (`appctr`):** A high-speed bridge using `gomobile` that exposes the Tailscale LocalAPI and status bus to the Android frontend.
3.  **Modern UI:** A 100% Jetpack Compose frontend optimized for efficiency, density, and ease of use.

The app uses **userspace-networking** exclusively. This allows TailSocks to run without requiring Android's `VpnService` permission, enabling it to work alongside other VPNs (like AdGuard or WireGuard).

## 📚 Documentation

*   [**Architecture Deep Dive**](docs/ARCHITECTURE.md) — Technical details on DNS wrapping and account isolation.
*   [**Build Instructions**](docs/BUILDING.md) — Setting up the NDK environment and compiling the Go core.
*   [**Project Evolution**](docs/RETROSPECTIVE.md) — History of the project from PoC to the current stable architecture.
*   [**AdGuard Setup**](docs/ADGUARD.md) — Instructions for using TailSocks alongside system-wide ad-blockers.
*   [**Serve & Funnel Guide**](docs/SERVE_FUNNEL_GUIDE.md) — How to expose local ports and virtual services.
*   [**Roadmap**](docs/ROADMAP.md) — Planned features and short-term goals.

## 📜 License

Distributed under the BSD-3-Clause License. See `LICENSE` for details.
