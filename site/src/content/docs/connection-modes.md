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

### Reachable from the tailnet

In proxy mode, with the hev-socks5-tunnel engine, and in Root Mode without its interface, the daemon runs in userspace. A connection from another tailnet device to this device's tailnet address, on a port the daemon itself does not serve, goes to the same port on `127.0.0.1` — that is how Tailscale works there. So devices the tailnet policy lets reach this one could use TailSocks's proxies, its Web UI and TailCat's forwards as if they ran on them.

Since 5.1.0 TailSocks's own listeners are closed to the tailnet. Settings → Local proxies → **Reachable from the tailnet** opens the proxies one by one: **SOCKS5 from the tailnet**, **HTTP proxy from the tailnet**, **DNS proxy from the tailnet**, **Taildrive proxy from the tailnet**. The Web UI and TailCat's forwards have no switch and stay closed.

- Used this phone's proxy from another tailnet device before? Switch it on here. Set a SOCKS5 password first; the HTTP proxy never asks for one.
- Other apps' services — adb over the network on 5555, an SSH server, Syncthing — stay reachable, as on any Tailscale node in userspace. **Strict: close everything else too** closes them, except the ports in **Also open these ports** under it: `5555, 8022, udp:1234`.
- Remote management's port, 28115, follows only **Allow remote management**. Taildrop, Taildrive folders and Serve need none of these switches.
- The daemon follows a change within about a second, without a restart.
- With a real interface — the native TUN engine, Root Mode's `tailscale0` — these switches change nothing: a listener on `127.0.0.1` is never reached from the tailnet there. Under Root Mode's interface, proxies opened to the local network are reached from the tailnet too.

Settings → Advanced → **What this device exposes** lists everything reachable from the internet, the tailnet or the local network, each with the switch that closes it. The diagnostics report has the same list.

### With AdGuard

AdGuard can send tailnet names to TailSocks and keep filtering the rest.

1. In AdGuard → App management → TailSocks, turn off “Route traffic through AdGuard”. Otherwise the two loop.
2. AdGuard → Settings → General → Advanced → Low-level settings:
   - **Fallback upstream servers** (`dns.fallback_upstream`): add `127.0.0.1:1053`.
   - **Fallback domains** (`dns.fallback_domains`): add `*.ts.net` and your split-DNS domains.
3. AdGuard → Settings → Proxy: add a SOCKS5 proxy, `127.0.0.1:48115` (your **SOCKS5 Address**). A resolved `100.x` address is reachable only through it.
4. Check: open a tailnet name, for example `my-laptop.example.ts.net`.

## VPN (TUN)

Routes the device through the Android VPN interface. Android gives the VPN slot to one app at a time, so turning it on asks first (**Take the VPN slot?**).

- **Without an exit node** only the tailnet and accepted subnet routes go through the VPN — or all traffic, in [transparent mode](#transparent-mode).
- **With an exit node** all traffic does.

Settings → Tunnel mode → **TUN Mode Settings**:

- **TUN engine** — **Native** (the default): `tailscaled` runs the tunnel itself; MagicDNS, split DNS and exit nodes come straight from it. **hev-socks5-tunnel** is the compatibility engine: every packet goes through the local SOCKS5 proxy. Use it if the native one misbehaves.
- **Rebuild the tunnel in place** — native engine only, on by default. See [the tunnel rebuilt in place](#the-tunnel-rebuilt-in-place).
- **Route all IPv6 through the tunnel** — off by default. Turn it on only if your exit node carries IPv6, or IPv6 sites hang.
- **Transparent mode** — **Under Android lockdown** by default. See [transparent mode](#transparent-mode).
- **Excluded IP Ranges** — subnets that stay on the real network while an exit node is on (Android 13+).
- **Excluded Apps** — apps that stay outside the tunnel.

### The tunnel rebuilt in place

Android fixes a VPN's routes when the VPN is established. So turning an exit node on or off, or a change in accepted subnet routes, needs a new VPN. Switching from one exit node to another needs none.

On the native engine TailSocks establishes the new VPN beside the old one, and the running daemon moves onto it in a few milliseconds, without a restart. If that fails, the daemon is relaunched on the new VPN, a few seconds without the tailnet. With **Rebuild the tunnel in place** off, it is always relaunched.

### Transparent mode

Without an exit node the VPN carries only the tailnet. Android's **Block connections without VPN** (Android's VPN settings, next to **Always-on VPN**) cuts every connection that is not routed into the VPN — without an exit node, the whole internet.

In transparent mode the VPN takes all traffic. What is for the tailnet goes there; the rest goes out directly — on the native engine the daemon dials it, on hev-socks5-tunnel the SOCKS5 proxy does. Accepted subnet routes still go to the devices that offer them. With an exit node selected, the exit node carries everything and transparent mode stays out of it.

| Choice | When it is on |
|---|---|
| **Under Android lockdown** (default) | only while **Block connections without VPN** is on. TailSocks follows that switch by itself, within about 15 seconds. |
| **Always** | whenever no exit node is selected. |
| **Off** | never. Without an exit node, the lockdown then leaves no internet. |

What it costs:

- Slower than the direct path in principle. Measured on a phone on Wi-Fi: about 4.6 MB/s with it and without it.
- Ping and other ICMP to the internet do not go through.
- The world's IPv6 goes through only with **Route all IPv6 through the tunnel** on. Without it, under the lockdown, apps fall back to IPv4.

<details>
<summary>How it follows the lockdown</summary>

Android tells only the running VPN service whether **Block connections without VPN** is on, and tells nobody when it changes. So TailSocks asks on every status refresh, every 15 seconds, and rebuilds the tunnel when the answer changes — in place, on the native engine.

The rebuilt VPN takes the default route, `0.0.0.0/0`, and `::/0` only with **Route all IPv6 through the tunnel**. On the native engine the daemon ends the phone's TCP and UDP to addresses outside the tailnet that no peer routes and dials them again itself; the daemon stays outside the VPN, so the lockdown lets it through. On hev-socks5-tunnel every connection already goes to the SOCKS5 proxy, which dials what is not for the tailnet as it does for any app.

</details>

### Another VPN app takes the slot

When another VPN app — AdGuard, Exclave… — takes Android's VPN slot, TailSocks keeps running as a proxy: the tailnet stays reachable through its [local proxies](#proxy). The main card says *Another VPN app took the VPN slot. TailSocks runs on as a proxy; reconnect to take the slot back.*

TailSocks takes the slot back only when you reconnect it. A settings change does not take it.

## Root (experimental)

On a rooted device (Magisk, KernelSU, APatch) the daemon runs through `su` on a real kernel interface, `tailscale0`, with policy routing. Android's VPN slot stays free.

Choose **Root** in Settings → Tunnel mode (on a TV: Settings → Advanced → Root mode). Its switches are on the card **Root Mode & System Service (EXPERIMENTAL)**: the kernel interface, a boot script, a `tailscale` command for root shells.

- With an exit node the whole device goes through it, except **Excluded Apps**; **System-wide DNS via MagicDNS** (Settings → DNS) sends every app's DNS to MagicDNS.
- Next to another VPN app it yields: tailnet addresses, MagicDNS names and the local proxies keep working, and the apps that VPN leaves out ride the exit node.
- After a reboot the boot script brings the tailnet back; the exit node and system-wide DNS return when the app runs.

Everything about it — setting it up, living next to another VPN, the boot script, troubleshooting — is on [Root Mode](/tailsocks/root-mode/).
