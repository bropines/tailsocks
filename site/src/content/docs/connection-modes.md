---
title: Connection modes
description: Proxy, VPN (TUN) or Root — how traffic leaves the device.
---

Settings → **Tunnel mode** picks one of three. You can switch at any time.

| Mode | Needs | What goes through the tailnet |
|---|---|---|
| **Proxy** | nothing | the apps you point at the local proxy |
| **VPN (TUN)** | the VPN permission | tailnet addresses; with an exit node, everything |
| **Root** | root (Magisk, KernelSU, APatch) | the whole device, through the kernel interface `tailscale0` |

## Proxy

No VPN permission, so it lives next to any other VPN or ad blocker. TailSocks serves, on this device:

| Listener | Default | Setting |
|---|---|---|
| SOCKS5 | `127.0.0.1:48115` | Local proxies → **SOCKS5 Address** |
| HTTP | `127.0.0.1:8080`, off until **Enable HTTP Proxy** | Local proxies → **HTTP Proxy Address** |
| DNS for tailnet names | `127.0.0.1:1053` | DNS → **DNS Proxy Address** |

- Point a browser, an app or a proxy front end (Exclave, NekoBox…) at them. **Copy SagerNet Link** gives a `socks5://` link.
- **SOCKS5 Username** and **SOCKS5 Password** work only together; one alone is ignored.
- **Expose proxies to local network** opens all three to your Wi-Fi. Set the SOCKS5 password first — without it anyone on the network can use your tailnet.
- Without an exit node, internet traffic through the proxy leaves through the phone's own network; with one, through the exit node.

### With AdGuard

AdGuard can send tailnet names to TailSocks and keep filtering the rest.

1. In AdGuard → App management → TailSocks, turn off “Route traffic through AdGuard”. Otherwise the two loop.
2. AdGuard → Settings → General → Advanced → Low-level settings:
   - **Fallback upstream servers** (`dns.fallback_upstream`): add `127.0.0.1:1053`.
   - **Fallback domains** (`dns.fallback_domains`): add `*.ts.net` and your split-DNS domains.
3. AdGuard → Settings → Proxy: add a SOCKS5 proxy, `127.0.0.1:48115` (your **SOCKS5 Address**). A resolved `100.x` address is reachable only through it.
4. Check: open a tailnet name, for example `my-laptop.example.ts.net`.

## VPN (TUN)

Routes the device through the Android VPN interface. Android gives the VPN slot to one app at a time, so turning it on asks first (**Take the VPN slot?**). A VPN or ad blocker that takes the slot later disconnects TailSocks the same way.

- **Without an exit node** only the tailnet and accepted subnet routes go through the VPN.
- **With an exit node** all traffic does.

Settings → Tunnel mode → **TUN Mode Settings**:

- **TUN engine** — **Native** (the default): `tailscaled` runs the tunnel itself; MagicDNS, split DNS and exit nodes come straight from it. Turning an exit node on or off relaunches the daemon for a few seconds. **hev-socks5-tunnel** is the compatibility engine: every packet goes through the local SOCKS5 proxy. Use it if the native one misbehaves.
- **Excluded Apps** — apps that stay outside the tunnel.
- **Excluded IP Ranges** — subnets that stay on the real network while an exit node is on (Android 13+).
- **Route all IPv6 through the tunnel** — off by default. Turn it on only if your exit node carries IPv6, or IPv6 sites hang.

## Root (experimental)

On a rooted device the daemon runs through `su` on a real kernel interface, `tailscale0`, with policy routing. Android's VPN slot stays free.

Choose **Root** in Settings → Tunnel mode (on a TV: Settings → Advanced → Root mode). The card **Root Mode & System Service (EXPERIMENTAL)** holds its switches:

- **Native Linux TUN (tailscale0)** — the kernel interface. Off, the daemon runs via `su` in proxy mode.
- **Autostart Magisk/KernelSU Service (EXPERIMENTAL)** — the boot script in `/data/adb/service.d/`.
- **Install Tailscale CLI Utility (EXPERIMENTAL)** — a `tailscale` command for Termux and root shells.
- **Ignore other VPNs** — the daemon marks its own connections so another VPN cannot swallow them. Leave it on.
- **Take the device anyway** — see below.
- **Terminate Root Daemon on Stop** — on by default. Off, Stop only detaches the app and the daemon keeps running.

**System-wide DNS via MagicDNS** (Settings → DNS) sends the whole device's DNS to MagicDNS. **Excluded Apps** are taken off the exit node, but their names still resolve through MagicDNS.

### Next to another VPN

Root Mode yields to a VPN app that holds Android's VPN slot:

- Tailnet addresses, MagicDNS names and the local proxies keep working for every app.
- If that VPN takes every app, the exit node and system-wide DNS are off while it runs. The status card says *Tailnet only — another VPN holds the device*.
- If it leaves some apps outside its tunnel, TailSocks carries exactly those, exit node and MagicDNS included: *Carrying the apps another VPN leaves out*.
- **Take the device anyway** takes the default route and DNS back. That VPN's apps lose their tunnel. Off by default, and it asks first.

To have the tailnet inside the other VPN, point its outbound at the SOCKS5 proxy.

### After a reboot

The boot script starts the daemon and installs tailnet reachability only. The exit node and system-wide MagicDNS come when the app runs. To have them right after boot, turn on **Auto-start on Boot** (Settings → Background & permissions): the app then starts by itself if it was running before the reboot.

**Check Routing** (Settings → Diagnostics & developer → **Daemon Runtime Information (EXPERIMENTAL)**) shows the rules in place and why. The full reference is the [Root guide](https://github.com/bropines/tailsocks/blob/main/docs/ROOT.md).
