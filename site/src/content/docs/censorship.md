---
title: Blocked networks
description: Reaching the coordination server where Tailscale is blocked or broken.
---

To sign in and to learn about your devices, a node talks to the coordination server — Tailscale's, or your Headscale. Where that connection is blocked, Settings → **Censorship bypass** has four tools. When the server is unreachable, the main screen's banner has a **Bypass** button that opens this page.

Only the control connection goes through them. Traffic between your devices does not.

## Port 443 only

Card **Control connection**, on by default since 4.10.1.

Tailscale opens its control connection on plain port 80. Some networks let the handshake through and then silently drop the stream: the device signs in but never gets the network map, and stays *Connecting*. Over 443 the whole exchange stays inside TLS.

Turn it off only if port 443 to the server does not work on your network.

## DPI bypass

**Enable ByeByeDPI Bypass** runs [ByeDPI](https://github.com/hufrea/byedpi) inside the app and sends the control connection through it. It listens on a random loopback address and port, new at every start.

- **ByeByeDPI Flags** — the desync options. Default: `-o1 -a1 -r-5+se`. Flags it refuses are replaced by the defaults, and the card says what really runs.
- **Force IPv4 (Disable IPv6)** — when handshakes fail over IPv6.

While it is on, it replaces the control plane proxy.

## Control plane proxy

**Control Plane Proxy** sends the control connection through a proxy of your own: SOCKS5, HTTP or HTTPS, with an optional username and password.

## Start without the server

**Start from the saved network map**: the daemon keeps the last network map and connects to your devices from it at start, before the server answers. The tailnet comes up even when the server is slow or blocked; new devices and changed rules arrive once it answers.

The server allows this per device; the card says whether it does. **Clear the network map cache** deletes the saved maps.

## DNS fallback

When the tailnet's DNS does not answer, Settings → DNS → **Fallback DNS Servers** are asked instead:

- **DNS Fallbacks** — plain DNS, for example `8.8.8.8:53,1.1.1.1:53`.
- **DoH Fallback URL** — DNS over HTTPS, tried last, for example `https://1.1.1.1/dns-query`.

The main screen then says *Tailscale DNS not answering*, and whether the fallbacks answer meanwhile.
