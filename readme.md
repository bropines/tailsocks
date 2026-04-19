# TailSocks: Advanced Tailscale Proxy for Android

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/bropines/tailsocks)](https://github.com/bropines/tailsocks/releases)
[![Core](https://img.shields.io/badge/Tailscale_Core-v1.96.x-green.svg)](https://github.com/tailscale/tailscale/tags)

TailSocks is a high-performance, lightweight Android client for [Tailscale](https://tailscale.com/) that operates exclusively in **userspace-networking mode**. It provides a complete Tailscale environment—including Taildrop and Exit Nodes—without utilizing Android's `VpnService`, enabling seamless coexistence with other VPN and firewall applications.

## 🏗 System Architecture

TailSocks utilizes a hybrid bridge architecture to integrate the official Go-based Tailscale core with a modern Android management interface:

*   **Optimized Core:** The `tailscaled` daemon is compiled as a Position Independent Executable (PIE) and patched specifically for Android's network monitoring restrictions.
*   **Management Bridge (`appctr`):** A Go-based controller compiled via `gomobile` that manages the daemon's lifecycle, implements account isolation, and handles low-level networking logic.
*   **Passive State Model:** Starting from v1.8.1, the system utilizes a passive management approach, trusting the daemon's internal state machine for recovery and policy synchronization, which significantly improves connection stability and battery efficiency.

## 🚀 Key Features

*   **Taildrop Hub:** A custom implementation of Tailscale's file-sharing protocol with background reception support and integration with the Android Storage Access Framework (SAF).
*   **Multi-Account Management:** Robust profile isolation. Each account maintains its own state directory (`files/states/{id}`) and independent machine keys.
*   **Advanced DNS Proxy:** A tri-tier DNS resolver that handles MagicDNS (`*.ts.net`) and Split DNS by wrapping UDP queries into TCP frames, bypassing standard Android routing limitations.
*   **Exit Node Support:** Full integration with Tailnet Exit Nodes, featuring an automated "Self-Healing" mechanism that clears invalid configurations during account or network transitions.
*   **Deep Diagnostics:** Real-time visibility into the userspace engine, including NAT traversal status (`InMagicSock`), byte counters (RX/TX), and WireGuard handshake telemetry.
*   **SagerNet Integration:** Seamless export of SOCKS5/UDP credentials to third-party proxy clients via standardized URI schemes.

## 📚 Documentation

*   [**Architecture Deep Dive**](docs/ARCHITECTURE.md) — Technical details on DNS wrapping and account isolation.
*   [**Build Instructions**](docs/BUILDING.md) — Setting up the NDK environment and compiling the Go core.
*   [**Project Evolution**](docs/RETROSPECTIVE.md) — History of the project from PoC to the current stable architecture.
*   [**AdGuard Setup**](docs/ADGUARD.md) — Instructions for using TailSocks alongside system-wide ad-blockers.

## 📜 License

Distributed under the BSD-3-Clause License. See `LICENSE` for details.
