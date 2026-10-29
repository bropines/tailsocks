---
title: Root Mode
description: On a rooted device — the kernel interface tailscale0, a boot script, a tailscale command, Tailscale SSH, and living next to another VPN.
---

:::caution
Experimental: the author is still testing and debugging it.
:::

On a rooted device the daemon runs as root, through `su`, on a real kernel interface, `tailscale0`, with policy routing around it. Android's VPN slot stays free for another app. The daemon can start at boot without the app, and a `tailscale` command works in Termux and root shells.

## What it needs

- Root from **Magisk**, **KernelSU** or **APatch**, granted to TailSocks.
- Nothing else to prepare. The app writes its own routing rules, and on a device with SELinux enforcing it adds the one policy rule it needs, when it needs it.

## Turn it on

1. Settings → **Tunnel mode** → **Root**. On a TV: Settings → Advanced → **Root mode**.
2. **Warning: Experimental Root Mode** — tap **Got it, Enable**.
3. Your root manager asks to grant TailSocks root. Without it the app says *Root access (su) not granted or unavailable* and stays where it was.

Coming from VPN (TUN) mode, the VPN goes off and the kernel interface takes over.

The card **Root Mode & System Service (EXPERIMENTAL)** holds the rest:

- **Native Linux TUN (tailscale0)** — on by default: the kernel interface. Off, the daemon runs through `su` as a proxy only, on the **Local proxies** settings.
- **Excluded Apps** — apps kept off the exit node. The same list as in VPN mode.
- **Excluded IP Ranges** — while **Native Linux TUN (tailscale0)** is on: subnets that stay on the real network while an exit node is on. The same list as in VPN mode.
- **Autostart Magisk/KernelSU Service (EXPERIMENTAL)** — the [boot script](#the-boot-script).
- **Install Tailscale CLI Utility (EXPERIMENTAL)** — the [`tailscale` command](#the-tailscale-command).
- **Ignore other VPNs** — on by default. The daemon marks its own connections so another VPN cannot swallow them. Turn it off only to run this tunnel inside that other VPN.
- **Take the device anyway** — off by default. See [next to another VPN](#next-to-another-vpn).
- **Terminate Root Daemon on Stop** — on by default. See [stopping](#stopping).

**System-wide DNS via MagicDNS** (Settings → DNS, on by default) sends the DNS of the whole device to MagicDNS. It works only while **Use MagicDNS** is on.

## What goes through the tailnet

- **Tailnet addresses**, from every app, always.
- **With an exit node**, everything the device sends — except **Excluded Apps**, **Excluded IP Ranges**, and the local network when **Allow LAN access** (Settings → Account & connection) is on.
- **Name lookups** of every app, through MagicDNS, with **System-wide DNS via MagicDNS** on.

Root Mode is a client only: in it, this device cannot be an exit node or a subnet router for others.

### Excluded apps and DNS

An excluded app leaves the exit node and still reaches tailnet addresses. Its names, though, are still resolved by MagicDNS: Android looks names up for an ordinary app under its own identity, so a per-app rule cannot see those lookups. Only an app that resolves names itself — Chrome's built-in resolver, a DoH or DoT client, `curl` in Termux — gets the system resolver back.

If an app must keep the system resolver — an ad blocker, a private DNS app — turn **System-wide DNS via MagicDNS** off.

## Next to another VPN

Root Mode does not fight a VPN app for the device. When another app holds Android's VPN slot, TailSocks yields to it:

- Tailnet addresses, MagicDNS names and the local proxies keep working for every app.
- If that VPN takes every app, the exit node and system-wide DNS are off while it runs. The status card reads *Tailnet only — another VPN holds the device*, and the exit node shows as not in use.
- If it leaves some apps outside its tunnel, TailSocks carries exactly those, exit node and MagicDNS included. The status card reads *Carrying the apps another VPN leaves out*, and the exit node *Exit node: partial*.
- **Take the device anyway** takes the default route and DNS back for every app. That VPN's apps lose their tunnel and its filtering, and it is never told. Off by default; turning it on asks first.

Nothing needs a restart: TailSocks notices the other VPN starting, stopping or changing its apps, and adjusts.

### What to set in the other VPN

None of this is required, but it makes the two live together well:

1. **Exclude TailSocks from its tunnel** (its per-app proxy in bypass mode, or its excluded apps). Otherwise everything TailSocks does besides the tunnel itself — the control connection, updates, the admin API — rides the other tunnel, and a failure there looks like a failure here.
2. **Keep its own addresses out of the tailnet ranges**, `100.64.0.0/10` and `fd7a:115c:a1e0::/48`: its server, its DNS server, the subnet of its own interface. TailSocks routes those ranges into the tailnet for every app.
3. **For the tailnet inside the other VPN**, point its outbound at the SOCKS5 proxy (`127.0.0.1:48115` by default, with the password from **Local proxies** if you set one) and its DNS at `127.0.0.1:1053`. Whatever goes through the proxy follows the tailnet and the exit node, whatever the other VPN holds.

The third point means a proxy front end and Root Mode work together: the front end keeps the device and sends the apps you choose to TailSocks.

## The boot script

**Autostart Magisk/KernelSU Service** installs `/data/adb/service.d/tailscaled.sh`. Your root manager runs it at every boot, as root, before any app starts. It starts the daemon the way the app would — the same account, proxies, control-plane proxy and daemon options, which the app hands it at every Root Mode start.

- An app update refreshes the script by itself.
- A script written by another version is marked *Outdated script* under the switch. Tap **Reinstall Script**.

## After a reboot

The boot script starts the daemon and makes the tailnet reachable — nothing more. The exit node, subnet routes for other apps, and system-wide MagicDNS come when the app runs: at boot nothing yet knows which apps are excluded, what the local network is, or whether another VPN is about to start, and a device-wide claim without those exceptions would take the local network and the excluded apps with it.

To have them right after boot, turn on **Auto-start on Boot** (Settings → Background & permissions). The app then starts by itself if it was running before the reboot, and the gap is a few seconds.

## The tailscale command

**Install Tailscale CLI Utility** puts a `tailscale` command where root shells find it — `/system/bin/tailscale` through a Magisk module, with copies in `/data/adb/service.d/` and `/product/bin/`. It runs the app's own binary and talks to the app's daemon, so there is no socket to name:

```sh
su -c tailscale status
su -c tailscale ip
su -c tailscale ping my-laptop
su -c tailscale netcheck
su -c tailscale version
```

An app update refreshes it too.

## Tailscale SSH

Other devices of the tailnet can open a shell on this one: `ssh root@phone` or `ssh shell@phone` — a terminal, a single command, `scp`, SFTP. Root Mode only: in Proxy and VPN (TUN) modes the daemon starts with its SSH server off.

1. Settings → Account & connection → **Tailscale SSH**. Off by default; outside Root Mode it is greyed out, *Only in Root Mode*.
2. The `ssh` rules of the tailnet policy decide who gets in, as which user, and whether they confirm in a browser first (`check`). A new tailnet's default policy already lets members into their own devices after that check, so nothing needs editing. A policy without an `ssh` section lets nobody in.

A rule for your own devices:

```json
"ssh": [
  {"action": "check", "src": ["autogroup:member"], "dst": ["autogroup:self"], "users": ["root", "shell"]}
]
```

The access rules must let them reach port 22 too; the default policy does.

- **root** — a root shell. Its `PATH` has the root manager's tools (KernelSU, APatch, Magisk); `su`, `pm` and `am` work.
- **shell** — uid 2000, the user adb runs as, with adb's groups.
- No other users.
- A session starts in `/data/local/tmp`. It gets none of the daemon's own environment, such as the SOCKS5 password.

Settings → Advanced → **What this device exposes** has a **Tailscale SSH** row, port 22, in Root Mode.

## Stopping

**Terminate Root Daemon on Stop**, on by default, stops the root daemon when you press Stop. Only the daemon started for this app is stopped — never a second profile, a `tailscaled` in Termux, or another app's.

Off, Stop only detaches the app: the daemon keeps running, and the next Start attaches to it. A manual Stop is final either way: nothing starts the service again until you do.

A daemon that is still starting when the app comes up is waited for, up to 15 seconds, not killed.

## When something is wrong

- **Check Routing** — Settings → Diagnostics & developer → **Daemon Runtime Information (EXPERIMENTAL)**. Every rule in place, whether another VPN holds the device and what it leaves out, and why the exit node is or is not routed. The same card shows the daemon's status, the socket, the log file and the boot script.
- **Logs** → **ROOT**: every root command the app ran, its failures, and after every change which rules went in and why any did not. **TAILSCALE** is the daemon's own log.
- *Exit node unavailable* in the ROOT log: the app could not confirm the daemon marks its own connections, and leaves the exit node off rather than loop the device. Tailnet addresses still work.
- The app cannot reach a daemon that is running: on a device with SELinux enforcing the app adds the missing rule through `magiskpolicy`, `ksud` or `supolicy`. The ROOT log says whether it worked.
- An excluded app still resolves tailnet names: expected — see [excluded apps and DNS](#excluded-apps-and-dns).

From a root shell:

```sh
su -c tail -f /data/data/io.github.bropines.tailscaled/logs/tailscaled.log   # the daemon's log
su -c ps -ef | grep tailscaled                                                # is it running?
su -c ip rule show                                                            # TailSocks' rules: 100, 190, 200
su -c ip route get 8.8.8.8                                                    # tailscale0 with an exit node
```

For a bug report, run [`tools/root-debug.sh`](https://github.com/bropines/tailsocks/blob/main/tools/root-debug.sh) on the device as root, or [`tools/root-debug-session.sh`](https://github.com/bropines/tailsocks/blob/main/tools/root-debug-session.sh) from a computer over adb, and attach what it writes to a [Root Mode issue](https://github.com/bropines/tailsocks/issues).

How all of this works, with every rule and table: [Root Mode internals](/tailsocks/root-mode-internals/).
