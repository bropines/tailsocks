---
title: Root Mode internals
description: How Root Mode works — the boot script, the CLI wrapper, the routing tiers, living next to another VPN client, SELinux, and the commands to check it all.
---

The reference behind [Root Mode](/tailsocks/root-mode/): every file, rule, chain and table the app and its boot script write. The reasoning and the measurements are folded.

## The boot script

`/data/adb/service.d/tailscaled.sh`, installed by **Autostart Magisk/KernelSU Service (EXPERIMENTAL)**, starts the daemon at boot the way the app would — and installs [T1](#t1--tailnet-reachability) only ([why](#at-boot)).

| | |
|---|---|
| Runs | at `late_start`, as `root` (uid 0), by Magisk, KernelSU or APatch |
| Reads | `/data/adb/tailsocks/control_proxy.env`, written by the app through `su` on every Root Mode start |
| State | the profile recorded there as `TAILSOCKS_STATE_DIR` |
| Modes | socket `0666`, state `0700`, log `0644` (rotated past 2 MB) |
| Version | line 2, `# tailsocks-boot-script: <version> <build>`. A mismatch, or no such line (before 5.0.0), shows as outdated by **Reinstall Script** |

<details>
<summary>The env file, the fallbacks, the version hash</summary>

**The env file** is root-owned. The app rewrites it on every Root Mode start — its own launch and an attach to a daemon this script started — so a boot-started daemon is the same daemon the app would start:

| Group | Contents |
|---|---|
| Control proxy | `ALL_PROXY`, `HTTP_PROXY`, `HTTPS_PROXY`, pre-resolved `TS_STATIC_HOSTS` |
| Daemon variables | `TS_DNS_FALLBACK`, `TS_VPN_BYPASS`, `TS_SOCKS5_USER`/`TS_SOCKS5_PASS`, `TS_TAILDROP_DIR`, the Hostinfo switch, `TS_USE_CACHED_NETMAP=false` when the network map cache is off |
| Command line | the state directory, the SOCKS5/HTTP listen addresses, the tunnel mode (`tailscale0` or `userspace-networking`) |

The script accepts only `export NAME='value'` lines and drops an unquoted one. It never executes anything from the app's own data directory, which the app (or a restored backup) could write to. A comment line names the package and build that wrote the file; there is no format version.

**State directory fallbacks**, on an install that never started Root Mode from the app: `files/states/default`, then the first `tailscaled.state` found, then `states/root`. With several profiles only the recorded one is unambiguous.

**Modes.** The socket, `/data/data/io.github.bropines.tailscaled/files/tailscaled.sock`, is `0666` through TailSocks' safesocket patch, and the script sets the same — the modes the app sets when it starts the daemon itself.

**Routing.** Once `tailscale0` appears the script installs T1 for IPv4 and IPv6 — the masked fwmark `0x1000000/0x1000000` → table `53`, the destination rules for the tailnet ranges, the `TAILSOCKS_MARK` chain and the `tailscale0` FORWARD pair — and removes what 3.5.x left behind ([older versions](#older-versions)).

**The version** is the first 12 hex digits of SHA-256 over the script as that build writes it (template, package, daemon path and env file), so it moves with every change of the template and every move of the install directory. Settings reads it through `su`.

</details>

## The CLI wrapper

**Install Tailscale CLI Utility (EXPERIMENTAL)** adds a `tailscale` command for Termux, `su` and adb shells and scripts. It runs the app's own `libtailscale.so` and appends `--socket=/data/data/io.github.bropines.tailscaled/files/tailscaled.sock` to every command.

| Path | What it is |
|---|---|
| `/data/adb/service.d/tailscale` | the fallback executable |
| `/data/adb/modules/tailscaled/system/bin/tailscale` | a Magisk module that overlays `/system/bin` |
| `/product/bin/tailscale` | a live overlay of the product partition |
| `/system/bin/tailscale` | a direct link, where the partition is writable |

<details>
<summary>One binary; the debug build's own files</summary>

- **One binary.** The daemon has the CLI linked in (`ts_include_cli`). The wrapper sets `TS_BE_CLI=1`, without which the same file would start a second daemon.
- **The debug build** (`io.github.bropines.tailscaled.dev`) installs its root files under `-dev` names — `tailscale-dev` in the four places above (module `tailscaled-dev`), `service.d/tailscaled-dev.sh`, `/data/adb/tailsocks-dev/` — and never reads, overwrites or removes the release app's. The two share the kernel ruleset (table `53`, the chains, `tailscale0`, the saved `rp_filter` value), since only one root daemon can hold `tailscale0` at a time.

</details>

## Updates

`BootReceiver` listens for `ACTION_BOOT_COMPLETED`, `QUICKBOOT_POWERON` and `ACTION_MY_PACKAGE_REPLACED`. When the APK is replaced, it reinstalls the `service.d` script and the CLI wrapper from the app's assets, so the path to `libtailscale.so` in `/data/app/…` follows the new version.

## Routing around tailscale0

With **Native Linux TUN (tailscale0)** on, the daemon runs a real kernel interface, `tailscale0`, and Android's VPN slot stays free. Once it is `Running`, the app installs three tiers of policy routing:

| Tier | Made of | Claims |
|---|---|---|
| **T1 — tailnet reachability** | table `53` with the CGNAT guard, two priority-100 rules, `TAILSOCKS_MARK`, the `tailscale0` FORWARD pair, the `/etc/hosts` publication, the loopback SOCKS5/HTTP proxies | packets to tailnet addresses |
| **T2 — default-route capture** | the priority-200 catch-all into the daemon's table `52`, the `throw` routes for **Excluded IP Ranges**, the `rp_filter` loosening, the priority-190 per-app exclusions | everything, while an exit node is selected |
| **T3 — device-wide DNS** | `TAILSOCKS_DNS` and its two `nat OUTPUT` hooks | every port-53 packet |

T1 coexists with anything. T2 and T3 claim the whole device, so they yield to another VPN client that holds the slot ([the three states](#the-three-states)). Root Mode is client-only: in it, this device cannot be an exit node or a subnet router.

<details>
<summary>How the rules are applied</summary>

Everything lives in named chains, so the rules are idempotent, are replaced as a unit on every apply and are removed in one shot when the service stops. The apply verifies its own result (table populated, DNS chain present when it should be) instead of trusting the shell's exit code, and after three failed attempts gives up and says so in the log.

</details>

### T1 — tailnet reachability

Packets this device sends to a tailnet address go to `tailscale0` through table `53`, whatever else is installed.

| Piece | What it is |
|---|---|
| table `53` | `100.64.0.0/10` and `fd7a:115c:a1e0::/48` via `tailscale0` |
| priority 100 | `ip rule … fwmark 0x1000000/0x1000000 table 53` — marked packets |
| priority 100 | `ip rule … to 100.64.0.0/10 iif lo table 53` — by destination |
| `TAILSOCKS_MARK` | mangle, hooked from `OUTPUT` for the tailnet ranges: `--set-xmark 0x1000000/0x1000000` |
| CGNAT guard | a `throw` in table `53` for each prefix inside `100.64.0.0/10` held by another interface (a carrier, another VPN's `tun`) |

<details>
<summary>Why a destination rule, why <code>iif lo</code>, why a masked mark</summary>

- **The destination rule** is for the daemon's own sockets. The mark is applied in `mangle OUTPUT`, *after* the kernel has chosen a source address from the Wi-Fi table, so without this rule the daemon's queries to a split-DNS resolver on a peer left `tailscale0` with the Wi-Fi address as source and never got an answer.
- **`iif lo`** restricts it to packets that originate on this device. A packet merely passing through — from a tethered client, or through a subnet router — is left to Android's own rules instead of being claimed and answered with our source address. The mark rule needs no such guard: the mark is set only in `mangle OUTPUT`, which forwarded packets never traverse.
- **The mask.** Only one high bit is touched. Android packs its own routing decision into fwmark — the netId in the low 16 bits, then the explicit/protect/permission flags — and overwriting it, as the bare mark of [older versions](#older-versions) did, breaks routing whenever another VPN owns the default network.

</details>

<details>
<summary>The CGNAT guard: what triggers it, and how to see it</summary>

`100.64.0.0/10` is also real carrier-grade NAT space. A carrier can hand out a WAN address inside it, and another VPN client can put its `tun` there. The priority-100 rule sits above every per-app exclusion, so without the guard nothing on the device could take that traffic back out of the tunnel. Upstream Tailscale guards the same case with netmon's `HasCGNATInterface()`; this is the same walk, done by the app.

- **Trigger.** An interface that is up, is not named `tailscale*`, and holds an IPv4 address inside `100.64.0.0/10` with a prefix from `/11` to `/32`. IPv4 only.
- **Effect.** `throw <prefix>` in table `53`, one per prefix. Longest-prefix match picks the throw over the `/10`, and the lookup falls through to the rules below ours: the priority-200 catch-all where it is installed — so a tailnet peer inside that prefix is still reached through its own `/32` in table 52 — and netd's rules otherwise, which route it as they did before Root Mode started.
- **Refused.** A prefix of `/10` or shorter would cover the tailnet itself. It is not thrown; the ROOT log names the interface and says it is left captured.
- **When.** On every apply, in every state: the guard is part of T1. The colliding prefixes are among the inputs the app compares before an apply, so an address that appears (a carrier, on the way out of the house) or goes away re-applies the rules without a restart. New throws go in before stale ones are swept, so the `/10` never covers that traffic in between. If the interface list cannot be read, the guard is skipped and the ROOT log says so (`CGNAT guard skipped`).
- **Removed** on the next apply once stale, and together with every other `throw` in table `53` when Root Mode stops. The boot script does not write the guard; it arrives with the app's first apply.
- **Seen** in the ROOT log as `CGNAT guard: <prefix> on <interface> sits inside 100.64.0.0/10 without being the tailnet; …`, in the table-53 part of **Check Routing**, and with `su -c ip route show table 53`.

</details>

<details>
<summary>Why T1 never conflicts with another VPN</summary>

`ip rule` is evaluated in ascending priority, so priority 100 is consulted before every rule netd writes for a VPN app. That is harmless because these rules match nothing but tailnet destinations, and a sane tunnel client does not route the tailnet ranges for itself. Measured with a real one up (Exclave): it covers the IPv4 space in `/8`-sized pieces but installs `100.0.0.0/10` and `100.128.0.0/9` and deliberately not `100.64.0.0/10`, so the tailnet stays reachable from inside its tunnel with no configuration on either side.

</details>

### T2 — default-route capture

Exit nodes and subnet routes live in the daemon's own table `52`. The app adds one rule, so unmarked local packets consult table 52 first:

```
200: from all fwmark 0x0/0x2020000 iif lo lookup 52
```

With it, every **Excluded IP Ranges** prefix gets a `throw` in table 52 and stays on the real network while an exit node is on.

:::caution[Priority 200 waits until the daemon proves it marks its sockets]
Its sockets carry `SO_MARK 0x2000000` and skip the catch-all; unmarked, they would loop the device. So priority 200 goes in only after the current run has logged `netns: SO_MARK 0x2000000 set on tailscaled sockets`, or, with a silent log, the running binary is the app's own. If not, the ROOT log says `exit node unavailable: daemon does not mark sockets (<reason>)`.
:::

<details>
<summary>Excluded IP Ranges in Root Mode</summary>

- **Where it is set.** Settings → Tunnel mode → **VPN (TUN)** → **Excluded IP Ranges**. That card is shown only while VPN mode is selected; Root Mode uses the value saved there — by default `192.168.0.0/16,10.0.0.0/8,172.16.0.0/12`. Link-local, `169.254.0.0/16` and `fe80::/10`, is always added.
- **What is written.** `ip route replace throw <prefix> table 52` for every plain CIDR in the list: IPv4 always, IPv6 only where IPv6 is enabled on the device. A throw ends the lookup in table 52 and hands it to netd's rules, so traffic to that prefix leaves on Wi-Fi/cellular instead of the exit node. Entries may be separated by commas, semicolons, spaces or new lines; one that is not a plain CIDR is dropped and named in the ROOT log (`exit-node exclusions ignored, not plain CIDRs: …`).
- **No Android 13 limit here.** The "Needs Android 13+" note under the setting is about VPN mode, which excludes routes through `VpnService`; Root Mode writes kernel routes.
- **When.** Only together with the priority-200 catch-all, device-wide or scoped to a partial yield. The throws survive the daemon's own reconfiguration, so they are written once per apply, not on every network-map change.
- **Removed** when T2 is yielded — exactly the prefixes from the list, never a throw the daemon wrote for itself (its LAN throws for **Allow LAN access** live in the same table). When Root Mode stops, every IPv4 throw in table 52 except the daemon's `127.0.0.0/8`, and every IPv6 throw, is removed.
- **Seen** with `su -c ip route show table 52`, and in **Check Routing**, whose route check for the first excluded prefix answers `tailscale0` if the LAN is going through the exit node.

</details>

<details>
<summary>The safeguards around the catch-all</summary>

| Safeguard | What it does |
|---|---|
| `SO_MARK 0x2000000` | Every socket tailscaled opens itself (WireGuard/disco UDP, DERP, control plane, STUN, DNS fallbacks) carries bit 25 (patch 16 — outside Android's fwmark bits 0–20, apart from the app's bit 24). These packets skip priority 200 and reach the physical network through netd's rules. |
| mask bit `0x20000` | Android's `protectedFromVpn` bit, the idiom netd uses for its own VPN rules: sockets Android keeps off VPNs — network validation probes, MMS/IMS, another VPN client's *own* protected sockets — bypass the exit node as well. |
| `iif lo` | output lookups only; tethered clients stay on netd's rules |
| loose `rp_filter` | Replies to the daemon's marked sockets arrive on Wi-Fi/cellular and are reverse-path checked with mark 0, which resolves to `tailscale0` through table 52, so a strict `rp_filter` (1) would drop them. If any interface is strict, `all` is set to loose (2); the old value is kept in `/data/adb/tailsocks/rp_filter.orig` and restored on cleanup — or as soon as T2 is yielded, since it has no purpose without the catch-all. |
| read-back | The rule is read back after installing: an `ip` binary that dropped the mask would leave a match-everything rule, which is removed again and reported. |

</details>

<details>
<summary>How the proof is read from the daemon log</summary>

- **The line** is one contiguous string, preceded by Go's `YYYY/MM/DD HH:MM:SS` prefix and a component prefix such as `magicsock:`.
- **"The current run."** The daemon appends to one log file across runs and prints no start banner in this build (`ts_omit_logtail`). A run starts at the later of two lines: `TailSocks: daemon start`, which both launchers — the boot script and the app — write right before starting the daemon, and the daemon's own `wgengine.NewUserspaceEngine(tun "tailscale0") ...`, which every core logs on every start just before the engine (and with it the probe) is created. The log is scanned backwards to that point however long the daemon has run, and the probe line counts only if a run start lies below it — so neither an earlier run's line nor a daemon restarted by hand can borrow a verdict.
- **A silent log is not a "no".** The log rotates, and a daemon up for days has long since scrolled its start away, which on a real device withheld the exit node for a reason unrelated to routing. With no run start, or no probe line under it, TailSocks asks the running process instead and accepts it when `/proc/<pid>/exe` resolves into the app's own `nativeLibraryDir` — the binary we ship marks its sockets unconditionally.
- **An explicit negative logged by the daemon** stands and is never overridden this way. When neither witness can be had, priority 200 stays out and nothing is installed there; tailnet routing through table 53 is unaffected.
- Lines tagged `TailSocks:` are the app's and the boot script's and never count as the daemon's. The boot script does not read this gate at all: it installs no priority-200 rule.

</details>

<details>
<summary>Why no mark can keep another VPN's apps out of the exit node</summary>

netd installs a VPN app's rules at priority 13000 and 16000–17000. Once table 52 holds a default route the very first lookup matches, so every app follows the exit node — another client's tunnel members included — and those rules are never reached. That is why T2 is [yielded](#next-to-another-vpn-client) when someone else owns the slot.

The protect bit covers the other client's tunnel sockets, **not** the apps it routes: an ordinary app's packets carry no protect bit, and netId 0 in the mark. Widening the mask was measured not to help — `ip route get 1.1.1.1 mark 0x84 uid <member app>` still answers `dev tailscale0 table 52`. No mark can outrank a rule that is consulted first, so the tier has to be yielded, or scoped by uid as in [the partial yield](#the-partial-yield).

</details>

<details>
<summary>What table 52 holds, and what the daemon's router does on Android</summary>

Table 52 holds everything only the daemon knows: peer `/32`s, accepted subnet routes, `100.100.100.100/32`, `default dev tailscale0` while an exit node is selected, and `throw` routes for the LAN prefixes when **Allow LAN access** is on. The app adds its own throws for **Excluded IP Ranges** there and otherwise leaves the table to the daemon.

The daemon's router does only what only it can do: link up, addresses, routes into table 52. Switching an exit node on or off is handled entirely by its `router.Set()`, as on desktop Linux — no exit-node state in the app, nothing to redo on a Wi-Fi/cellular handover. It installs no `52xx` ip rules — Android's `main`/`default` tables are empty, so the desktop rules either loop or blackhole — and no netfilter at all: Tailscale's `0x40000`/`0x80000` marks are netd's permission bits, and the nft `nat` chain type is missing on some kernels, which is where the old `router config failed` health warning came from.

</details>

### T3 — device-wide DNS

`TAILSOCKS_DNS` (nat, hooked from `OUTPUT` for port 53 UDP/TCP, at position 1) redirects the device's DNS to MagicDNS, `100.100.100.100`. It needs **Use MagicDNS** (`accept-dns`) and **System-wide DNS via MagicDNS** (Settings → DNS), and is dropped in the [full yield](#the-three-states).

| # | Leaves the chain before the redirect |
|---|---|
| 1 | another tunnel's netId, in the [partial yield](#the-partial-yield) |
| 2 | the excluded apps, by uid — [less than it sounds](#per-app-exclusions) |
| 3 | `100.64.0.0/10`: split-DNS resolvers on peers |
| 4 | the daemon's upstreams, `TS_DNS_FALLBACK`: **DNS Fallbacks**, or the built-in defaults when it is empty |

<details>
<summary>Why these conditions and exits</summary>

- **`accept-dns`.** Redirecting the whole device to a resolver that is not answering would break DNS entirely.
- **The tailnet range**, so split-DNS resolvers hosted on peers are reached directly.
- **The upstream resolvers.** Without that exit the resolver's own queries are redirected back into itself, and no external name ever resolves.
- **Another tunnel's queries leave before the DNAT.** The hook sits at position 1 of `nat OUTPUT`, so the destination is rewritten *after* the kernel has chosen a source address. While another tunnel owned the device, its members' queries entered ours carrying **that tunnel's** address as source — measured as a stream of `netstack inject ts-service: src=100.100.100.100 dst=<the other tunnel's address>`, fast enough to trip the daemon's own log rate limiter. The `RETURN` that keeps them out is matched on the netId in the fwmark, not on a uid.

</details>

### Per-app exclusions

**Excluded Apps** (Settings → Tunnel mode, the same list as in VPN mode) get two rules per uid, only while the catch-all they are a hole in is installed:

```
ip rule add uidrange 10234-10234 goto <netd's lowest priority> priority 190
iptables -t nat -A TAILSOCKS_DNS -m owner --uid-owner 10234 -j RETURN
```

The policy rule jumps over our catch-all onto netd's rules: the app goes into another VPN's tunnel, or to the physical network. Tailnet addresses still reach `tailscale0`.

:::caution[Exclusions do not cover DNS for ordinary apps]
An ordinary app calls `getaddrinfo`, and the platform resolver sends the query under a system uid, so its lookups still go to MagicDNS. To keep the system resolver for certain — an ad blocker, a private-DNS app — turn **System-wide DNS via MagicDNS** off.
:::

<details>
<summary>What an exclusion buys, app by app</summary>

| Excluded app | Its traffic | Its name lookups |
|---|---|---|
| ordinary | outside the tunnel | still MagicDNS, so MagicDNS names keep resolving |
| resolves for itself — Chrome's built-in asynchronous resolver, DoH/DoT clients, `curl` and anything else in Termux, an app shipping its own resolver library | outside the tunnel | the system resolver; MagicDNS names only through the `/etc/hosts` publication, split-DNS domains not at all |

**System-wide DNS via MagicDNS** is the only switch that covers platform-resolver traffic.

</details>

<details>
<summary>Edge cases: the jump target, the full yield, detached rules, a missing <code>xt_owner</code></summary>

- **The list** is resolved to uids on every apply. It is the one VPN mode hands to `addDisallowedApplication`, so one list governs both tunnel modes. `--uid-owner` matches the uid of the process that *sends* the packet.
- **The jump target** is read off the device: the lowest priority at or above 10000 that this phone actually has a rule at.
- **Why jump rather than pin.** Pinning the app to a physical table took it out of another VPN as well: Chrome went dark with a "bad config" DNS error while its tunnel client saw no requests at all.
- **The priority-100 rules sit above** all of this, so only the app's default route goes back to the system.
- **The partial yield** gets exclusions too: whatever the catch-all captures, an exclusion can take back out. **The full yield** gets none: nothing is captured, and an exclusion would jump the app over the other client's rules as well, out of the tunnel it is supposed to be in.
- **Detached rules.** The kernel accepts a `goto` to a priority that holds no rule, prints it as `[detached]` and silently skips it at lookup time. So every rule is read back: a detached one is deleted and replaced by a rule pinning the uid to the physical table, and the Logs screen says the app will not ride another VPN either. Where even that is impossible, the app is **not** excluded — it stays in the tunnel rather than half-routed.
- **A package no longer installed** is skipped and named in the ROOT log. An empty list emits nothing.
- **No `xt_owner`.** `-m owner` is an optional kernel module. Without it the DNS `RETURN` rules are refused and the ROOT log says so; the routing exclusion — a policy rule, not a firewall match — is unaffected. The app is then outside the tunnel but resolving inside it, which the same log line names.

</details>

## Next to another VPN client

Android gives its VPN slot to one app, but our priority-100 and -200 rules come before netd's VPN rules (13000 and 16000–17000 on the author's phone). So T2 and T3 are yielded to the client that holds the slot — whole, or down to the apps it leaves out.

<details>
<summary>What claiming too much cost (measured), and where the raw data is</summary>

Every number in this section was read off a device. The raw capture — netd's own rules, uids, netIds, interfaces and DNS servers, with the commands that produced them — is [`docs/research/foreign-vpn-measurements.md`](https://github.com/bropines/tailsocks/blob/main/docs/research/foreign-vpn-measurements.md); the design decisions taken from it, and the open items still on the roadmap, are in [`docs/research/root-exit-node-design.json`](https://github.com/bropines/tailsocks/blob/main/docs/research/root-exit-node-design.json).

Android does not arbitrate what a rooted device does below its VPN slot: whatever we claim, we claim first, including traffic that belongs to somebody else's tunnel. With a real client up — Exclave, `com.github.dyhkwong.sagernet` — and an exit node selected on our side:

- Chrome, a member of the foreign tunnel, resolved to `dev tailscale0 table 52`. Its packets went into **our** exit node, its own client never saw them, and the browser went dark.
- Every port-53 packet on the device was rewritten into MagicDNS, including the platform resolver's queries for that tunnel's apps — carrying the other tunnel's source address ([T3](#t3--device-wide-dns)).

</details>

### The three states

| Situation | Installed | What works |
|---|---|---|
| **Alone** — no other VPN | T1 + T2 + T3 | everything |
| **Full yield** — another VPN takes every app | T1 | tailnet addresses, MagicDNS names via `/etc/hosts`, the loopback proxies. *Not*: system-wide DNS, the exit node and subnet routes outside the proxies |
| **Partial yield** — it bypasses some apps | T1 + T2 for those uids + T3 minus its network | exit node and subnet routes for the bypassed apps; MagicDNS except in its tunnel |
| **Take the device anyway** on | T1 + T2 + T3 | ours complete; its apps lose traffic and DNS |

| The app shows | Full yield | Partial yield |
|---|---|---|
| Main screen | **Tailnet only** | **Shared with another VPN** |
| Exit node | **not in use** | **Exit node: partial** |
| A healthy priority 200 | empty | one rule per bypassed uid range |

Neither yield is a fault, and both are re-decided as soon as the other VPN or a relevant setting changes.

<details>
<summary>The states in more detail</summary>

- **Alone** means every feature: exit nodes, accepted subnet routes, system-wide MagicDNS, per-app exclusions.
- **Full yield.** The node stays connected, and whatever is pointed at the loopback SOCKS5/HTTP proxies gets exit-node egress; the exit node and accepted subnet routes are missing only for apps that are not pointed at those proxies. The other client keeps its apps and its resolver, unharmed. The main screen shows **Tailnet only** instead of the usual Root line, a selected exit node is shown as **not in use** rather than pretending to carry traffic, and **Tunnel mode** and **DNS** in Settings say what is switched off and until when.
- **Partial yield.** The apps the other client leaves to the physical network follow our exit node and subnet routes instead, and MagicDNS answers for the device — except the queries that client's own tunnel makes. Its members are untouched: their traffic stays in its tunnel and their names are resolved by whoever it points them at. Nothing is switched off, so nothing claims it is: the exit node stays an active row, and Settings says which apps the tiers went in for.
- **The override** is not a state but the way out of both: the other client's apps lose their traffic and their name resolution for as long as TailSocks runs ([the override](#the-override)).
- **Both** are recorded in the Logs screen's ROOT tab (which tiers went in, and why), and **Check Routing** shows the same verdict.
- **What triggers a new decision:** another client starting or stopping, that client changing which apps it carries, or a setting that feeds the choice — so neither yielding, nor narrowing the scope, nor taking the device back waits for a restart.

</details>

### The partial yield

A client that tunnels only some apps hands the rest to the physical network. TailSocks carries those; that client loses nothing.

| Step | How |
|---|---|
| Members | netd's `uidrange` rules whose table is the foreign tunnel's own interface |
| Carried | the gaps between them: *installed* apps, minus VPN clients, minus TailSocks |
| Routing | one priority-200 rule per carried range, instead of the device-wide one |
| DNS | one `RETURN` per foreign netId at the head of `TAILSOCKS_DNS`; the rest of the device keeps MagicDNS |
| Nothing carried | the full yield |

<details>
<summary>The rules, as a root shell shows them</summary>

netd's membership rules — the ranges are the members, so the gaps here are uid 10354 (the client itself) and 10375 (TailSocks):

```
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 0-10353 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10355-10374 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10376-99999 lookup tun0
```

What TailSocks installs for a carried uid — the device-wide selector, restricted to uids the other tunnel does not want — and for that tunnel's netId `0x84`:

```
200: from all fwmark 0x0/0x2020000 iif lo uidrange 10380-10380 lookup 52
iptables -t nat -A TAILSOCKS_DNS -m mark --mark 0x84/0xffff -j RETURN
```

`ip rule` has no "not these uids", so the scope is one rule per range — exactly as many as the other client itself installs.

</details>

<details>
<summary>What a yield removes</summary>

What is not installed is actively removed: an earlier apply's catch-all (device-wide or scoped), the **Excluded IP Ranges** `throw` routes we wrote into table 52, the `rp_filter` change, the per-app exclusion rules and both DNS hooks. The scoped rules are likewise replaced as a unit whenever the other tunnel changes which apps it bypasses. Skipping an install would remove nothing, and these rules can be there from an earlier apply of the same run.

</details>

<details>
<summary>Why only the tunnel's own table counts</summary>

A physical network's *local* table (`wlan0_local`) carries a `uidrange` spanning every uid on the device. Folding it in leaves no gaps at all and silently turns the partial yield back into a full one.

</details>

<details>
<summary>Uids never carried: VPN clients and SDK sandboxes</summary>

- **A VPN client's own uid** is always a gap: a VpnService's owner is outside its own tunnel, or it would loop its relay traffic through itself. Carrying it would send that client's connection to its own relay out through our exit node, and the gap list would never be empty, so the full yield could never be reached. Android lists these apps without root or a permission — `PackageManager.queryIntentServices(Intent("android.net.VpnService"))` — so TailSocks asks it rather than guess.
- **Uids no application owns.** Android reserves an SDK sandbox uid next to every app (`appId + 10000`), so netd's list has a hole there too. Measured: uid 20354 sat in a gap with no package behind it, and carrying it wrote a rule that could never match. Hence the carried set is built from *installed* applications rather than by subtracting what looks wrong.
- **Large gaps.** A gap of up to 64 uids — the ordinary case of a handful of bypassed apps — is resolved app by app. A larger one (a client in "tunnel only these apps" mode leaves most of the device outside) is kept whole with the known VPN uids cut out: hundreds of one-uid rules cost more than a few rules that occasionally match nothing.

</details>

<details>
<summary>Why DNS is split by netId, not by uid (measured)</summary>

A uid match cannot keep the other client's resolution its own: the platform resolver sends an app's queries under *its* uid ([per-app exclusions](#per-app-exclusions)). What survives is netd's network id, which it stamps into the low 16 bits of the fwmark on the resolver's packet. Measured over 18 s of browsing inside a foreign tunnel, on `mangle OUTPUT`, `udp --dport 53`:

| mark (mask `0x1ffff`) | packets | meaning |
|---|---|---|
| `0x10084` | 6 | the resolver working on behalf of an app **inside** the other tunnel |
| `0x73` | 18 | physical-network resolution |

The netIds are read from the same `ip rule` probe as the uid ranges (`fwmark 0x10084/0x1ffff` → netId `0x84`) and compared between ticks, so a foreign tunnel that restarts with a new netId is noticed and the carve-out rewritten. Widening the catch-all's mask instead does not work: [no mark can outrank a rule consulted first](#t2--default-route-capture).

</details>

### How another client is recognised

Either signal is enough to yield: the safe direction is "someone else owns the device".

| Signal | What it reads |
|---|---|
| Android | `ConnectivityManager` reports a network with the VPN transport — only for a tunnel TailSocks is a *member* of |
| Root-side probe | a tunnel interface that is not ours (`tun*`, `ppp*`, `wg*`, `ipsec*`) **and** per-uid rules in netd's priority band pointing at a table neither ours nor physical; it also gives the member ranges and netIds |

<details>
<summary>Why two signals, and why the shape is compared every tick</summary>

- **Android's answer is blind to the client that matters.** A client that excludes TailSocks from its tunnel — the [correct configuration](#what-to-configure-in-the-other-client) — is invisible to it, and that is precisely the client we would break.
- **Both halves of the probe are required.** The pair is how netd expresses "these apps are members of a VPN", and nothing else on the device writes it; a stopped client leaves its interface behind, and a hand-routed WireGuard interface holds no VPN slot.
- **One read, two answers.** The probe answers both "is anyone here" and "what did they leave" — everything [the partial yield](#the-partial-yield) is built from. A malformed range is dropped rather than guessed at: a wrong one would hand that tunnel's own apps to us.
- **Every refresh tick.** Membership can change without the tunnel going down, and Android's capability callback cannot report it (membership is redacted for a non-member, and a client that excludes us is exactly the one that matters), so the app keeps the shape and compares it on every tick.
- **The ROOT log** gets the verdict, the inputs behind it — marks, foreign, override, ranges, gaps, netIds — and the reason, on every apply.

</details>

### The override

**Take the device anyway** (Settings → Tunnel mode) installs all three tiers device-wide regardless — no scoping, no DNS carve-out. It is off by default and sticky, and the confirmation dialog names its cost. Exit-node egress, and MagicDNS for an app that can be pointed at a resolver, need no override: use the [loopback proxies](#what-to-configure-in-the-other-client).

<details>
<summary>Why it is not in profile backups</summary>

It is consent about *this* phone's other client, not a preference worth restoring elsewhere, so a profile backup deliberately does not carry it. The ROOT log repeats the cost on every apply that uses it. Turn it on when the other tunnel is one you do not mind interrupting.

</details>

### At boot

The boot script installs T1 and stops; the app decides the rest — the yield, its scope, the exemptions — on its first `Running` tick.

| **Auto-start on Boot** | After a reboot |
|---|---|
| off | tailnet reachability only — no exit node, no accepted subnet routes for other apps, no system-wide MagicDNS — until you open TailSocks |
| on | the same, for the few seconds between the daemon reaching `Running` and the app's first apply |

<details>
<summary>Why the boot script stops at T1</summary>

- **No `PackageManager` at `late_start`.** What makes T2 and T3 safe — the LAN `throw` routes, the per-app exclusions, the per-uid DNS carve-outs — needs packages resolved to uids.
- **No other VPN client yet.** The script runs long before any other client's service has started, so a coexistence check there would answer "the device is free" almost every time.
- **A claim without its exemptions is worse than none.** That is how a reboot with an exit node selected used to take the local network and the excluded apps with it ([older versions](#older-versions)).

</details>

### What to configure in the other client

None of this is required, but it decides how well the two live together:

1. **Exclude TailSocks from its tunnel** (per-app proxy in bypass mode, or its excluded-applications list).
2. **Keep its own addresses out of** `100.64.0.0/10` and `fd7a:115c:a1e0::/48`: its relay or server, its DNS server, its `tun` subnet.
3. **For the tailnet inside its tunnel**, point its outbound at our SOCKS5 proxy (`127.0.0.1:48115` by default) or HTTP proxy, and its DNS at `127.0.0.1:1053`.

<details>
<summary>Why, and what was measured with Exclave</summary>

A real client, Exclave, got the first two right with no manual configuration.

- **Point 1.** The daemon's own sockets carry Android's protect bit and are routed by the physical network in any case, so the tunnel comes up either way. But everything else — the control plane through an HTTP proxy, updates, the admin API — would ride a second tunnel for no reason, and a failure in that client would look like a TailSocks failure. Measured: Exclave's uid ranges already skip both its own uid and ours.
- **Point 2.** Our priority-100 rules send those ranges into `tailscale0` for every app on the device. `100.64.0.0/10` is real CGNAT space, and some clients do pick addresses inside it. Measured: Exclave routes the IPv4 space in `/8`-sized pieces but installs `100.0.0.0/10` and `100.128.0.0/9`, deliberately not `100.64.0.0/10`, and its own tunnel sits on `172.19.0.0/30`. The [CGNAT guard](#t1--tailnet-reachability) covers an interface address inside the range, such as another client's own `tun` subnet; an address outside every such prefix — a remote relay, say — is still routed into the tailnet.
- **Point 3.** The SOCKS5 proxy (Settings → Local proxies, with the password from that page if you set one) and the HTTP proxy are served by the daemon itself, so what goes through them follows the tailnet and the selected exit node whatever tiers are installed. Measured with an exit node selected: connections through the SOCKS5 proxy left at the exit node, and a tailnet peer answered over it. This is why "Root Mode or a proxy front end" is a false choice: a front end that picks an upstream per app can send the apps you choose to TailSocks, with exit-node egress, while it keeps the device.

</details>

## Check Routing and the ROOT log

**Check Routing** (Settings → Diagnostics & developer → **Daemon Runtime Information (EXPERIMENTAL)**, native TUN on) dumps the live state of everything above — read it first.

| If it shows | It means |
|---|---|
| `tailscale0` for `8.8.8.8` under an excluded uid | that app is not excluded after all |
| `tailscale0` for the first excluded local prefix | the LAN goes through the exit node |
| a rule that is never evaluated | a `goto` the kernel could not resolve |
| another `--set-xmark` in `mangle` | a second app fights over the fwmark |
| a DNS-chain rule with zero packets | the rule is there but never hit |
| the run marker, `SO_MARK` line and verdict | why priority 200 is or is not installed |
| the **coexistence verdict** | who owns the device, and which uid ranges and netId are carried |

**Logs** → **ROOT**: every root command, its failures, and a summary of every apply. **TAILSCALE**: the daemon's own log.

<details>
<summary>Everything Check Routing prints, and the log tabs</summary>

- the complete `ip rule` / `ip -6 rule` lists: our priority-100 rules, the priority-200 catch-all when it is installed, the priority-190 exclusions, and any stale `52xx` rules;
- other tunnel interfaces present on the device;
- table 53 (the tailnet prefix and a `throw` per foreign CGNAT prefix), table 1099, table 52 for IPv4 and IPv6 (with the **Excluded IP Ranges** throws), and every `tailscale0` route;
- the number of default routes in table 53 — expected 0; one means the device was captured;
- the route decision for `8.8.8.8` with and without the daemon's bypass mark, per excluded uid and for the first excluded local prefix;
- any rule that is installed but never evaluated;
- every `--set-xmark` in `mangle`, ours and anyone else's;
- `rp_filter` and the saved original;
- the daemon log's last run marker and `SO_MARK` line, with the verdict the app drew from them;
- the coexistence verdict — whether another tunnel owns this device, what it was recognised by, and, when it bypasses apps, which uid ranges and which netId are being carried;
- the chains, with packet counters on the DNS chain, which tells "the rule is missing" apart from "the rule is there and never hit";
- the `tailscale0` address and the `ip` version.

The **ROOT** tab records routing apply and cleanup, daemon stop and script install; failures land there instead of being silently discarded, and each apply's summary says which tiers went in and why any did not. **TAILSCALE** shows the `logs/tailscaled.log` file, the same tab the daemon fills in proxy mode. The Clear button asks what to empty: the app log, the daemon log or everything.

</details>

## SELinux: the connectto rule

Stock policy gives the app no `connectto` on the root daemon's `unix_stream_socket`, so on an enforcing device the connect fails with `EACCES` — which looks exactly like a dead daemon. `RootUtils.allowSocketConnect` adds the rule on demand; nothing is patched at boot.

| | |
|---|---|
| When | the app attaches to (or waits for) the socket, and the connect is refused with a permission error |
| Applies | `allow <app domain> <daemon domain> unix_stream_socket connectto` via `magiskpolicy`, `ksud sepolicy patch` or `supolicy` — live-only, gone after a reboot |
| Logs | success or failure, in **ROOT** |

<details>
<summary>Where the domains come from, and what to send in a report</summary>

The daemon is launched from a root shell, so it runs in the root solution's domain (`magisk`, `su`, whatever the installed manager uses), while the app connects to `tailscaled.sock` from its own app domain. Both are read, not assumed: the app's from `/proc/self/attr/current`, the daemon's from `/proc/<pid>/attr/current` of the process matched by `--socket=`. If no policy tool is available, Root Mode fails to reach the daemon exactly as it would have without the rule.

[`tools/root-debug.sh`](https://github.com/bropines/tailsocks/blob/main/tools/root-debug.sh) prints `getenforce`, the shell and daemon contexts and the last AVC denials — what a bug report about this needs.

</details>

## Stopping and adopting the daemon

**Terminate Root Daemon on Stop** (Settings → Tunnel mode) decides what Stop does. Only this app's daemon is matched, by its `--socket=` argument, so a second profile, a Termux `tailscaled` or another app shipping the same library is never killed.

| The switch | Stop |
|---|---|
| on (default) | SIGTERM, then SIGKILL, to the root `libtailscale.so` process |
| off | the app only detaches; the next Start re-attaches to the running daemon |

A manual Stop is final either way. On Start, a daemon that is still coming up is waited for, not killed.

<details>
<summary>How a start adopts a daemon it finds</summary>

| On Start | What happens |
|---|---|
| a daemon answers on the socket | it is adopted at once |
| no answer, but root finds a process with this `--socket=` | the start waits, probing every 400 ms: it adopts the daemon when it answers, and replaces it only if it dies or has not answered within 15 s |
| no such process | the start does not wait at all |

The `--socket=` argument that identifies the daemon points at the app's private data path. With the switch off, a settings change while you want the connection also re-attaches instead of starting a new daemon. Nothing revives the service after a manual Stop until you start it again.

</details>

## Files and commands

| File | What it is |
|---|---|
| `/data/data/io.github.bropines.tailscaled/logs/tailscaled.log` | the daemon's log |
| `/data/data/io.github.bropines.tailscaled/files/tailscaled.sock` | the daemon's socket |
| `/data/adb/service.d/tailscaled.sh` | the boot script |
| `/data/adb/tailsocks/control_proxy.env` | what the boot script starts the daemon with |
| `/data/adb/tailsocks/rp_filter.orig` | the `rp_filter` value to restore |
| `/data/adb/modules/tailscaled/module.prop` | the CLI wrapper's Magisk module |

```sh
su -c tail -f /data/data/io.github.bropines.tailscaled/logs/tailscaled.log    # the daemon's log, live
su -c ps -ef | grep tailscaled                                                 # is it running as root?
su -c ls -la /data/data/io.github.bropines.tailscaled/files/tailscaled.sock    # socket permissions

# Our rules: 100 tailnet, 190 excluded apps, 200 exit-node catch-all; no 52xx expected
su -c ip rule show
su -c ip route show table 53                  # the tailnet /10, plus a throw per foreign CGNAT prefix
su -c iptables -t mangle -S TAILSOCKS_MARK
su -c iptables -t nat -S TAILSOCKS_DNS
```

<details>
<summary>Commands for the exit node and the <code>SO_MARK</code> proof</summary>

Priority 200 is device-wide when nothing else holds the VPN slot, one rule per carried uid range in a partial yield, and absent in a full one.

```sh
su -c ip route show table 52                 # peers, subnet routes, 'default dev tailscale0' with an exit node, throws for excluded ranges
su -c ip route get 8.8.8.8                   # tailscale0 with an exit node and the device ours, Wi-Fi/cellular otherwise
su -c ip rule show | grep '^200:'            # empty is correct while another VPN holds the device
su -c ip route get 8.8.8.8 mark 0x2000000    # the daemon's own path: always the physical network
su -c grep -n 'TailSocks: daemon start' /data/data/io.github.bropines.tailscaled/logs/tailscaled.log | tail -n 1   # where the current run begins
su -c grep 'netns: SO_MARK' /data/data/io.github.bropines.tailscaled/logs/tailscaled.log   # expected after that line: '... set on tailscaled sockets (root bypass)'
```

</details>

## Diagnostic scripts

Two scripts in the repository collect all of the above. Attach what they write to a Root Mode issue on [GitHub](https://github.com/bropines/tailsocks/issues).

- **[`tools/root-debug.sh`](https://github.com/bropines/tailsocks/blob/main/tools/root-debug.sh)** — on the device, as root: a read-only snapshot.
- **[`tools/root-debug-session.sh`](https://github.com/bropines/tailsocks/blob/main/tools/root-debug-session.sh) `<adb-serial> [package]`** — from a computer, over adb: a full start/stop cycle with a snapshot at every phase, in `./root-debug-<timestamp>/`.

```sh
adb push tools/root-debug.sh /data/local/tmp/
adb shell su -c sh /data/local/tmp/root-debug.sh          # optional argument: package name

tools/root-debug-session.sh 192.168.1.83:44895
```

<details>
<summary>What each script captures</summary>

- **`root-debug.sh`** prints the app version, the relevant global settings, the daemon process and socket, the `tailscale0` interface and other tunnels, policy rules, tables 53 and 1099, route decisions with and without the mark, both chains **with packet counters** ("the rule is missing" versus "the rule is there but never hit"), leftover legacy rules, the `/system/etc/hosts` bind mount, a DNS resolution probe and the last 40 daemon log lines. Nothing is modified.
- **`root-debug-session.sh`** writes `00-before.txt`, `01-t{5,15,30}s.txt` while the daemon comes up, `02-after.txt`, `03-after-stop.txt` (this one shows whether any rules were left behind), plus `logcat.txt` and the in-app `applog.txt`. It starts and stops the service via `am start-foreground-service … -a START_ACTION` / `-a STOP_ACTION`.

</details>

## Older versions

What earlier builds did differently, for a device that ran one. Current builds remove the old rules and tables by themselves.

<details>
<summary>Leftovers you may find, and behaviour that changed</summary>

**Leftovers on an old install:**

- **Table `1099`** instead of `53`, in every build before 4.0.0. netd names its per-network tables after the interface index plus 1000, so on a phone whose interface index reached 99 that table belongs to a live network — and the old cleanup flushed it. Nothing is written there any more; every apply and every stop removes what an older install left, by content, never with a flush. On a device that has not run 4.0.0 yet, look in `1099`.
- **The bare mark `1099`** (`--set-mark 1099`) and direct `OUTPUT` entries without chains, from 3.5.x. The bare mark overwrote Android's whole fwmark and broke routing whenever another VPN owned the default network. Both are removed automatically on the first start after an upgrade, and by the boot script.
- **`52xx` rules** — `5210 lookup main`, `5230 lookup default`, `5250 unreachable`, `5270 lookup 52` — the desktop-Linux rules a pre-4.0 core left behind. Purged by content on every apply and on cleanup.
- **The `TAILSOCKS_BYPASS` mangle chain**, from an earlier iteration of per-app exclusions that gave excluded uids the daemon's bypass mark. A mark set in `mangle OUTPUT` arrives after the kernel has chosen the route and the source address, so it could not do the policy rules' job. Detached and deleted on every apply.
- **Exclusions jumping to `16000`**, hardcoded before 4.0.0 and not universal; the target is now read off the device.
- **`libtailscale_cli.so`**, the separate CLI up to 4.9. The wrapper is reinstalled on update, so an old one never points at the vanished file for long.
- **A boot script without a version line**, like every copy before 5.0.0: shown as outdated, next to **Reinstall Script**.

**Behaviour that changed:**

- **Before 4.0.0** the boot script installed the device-wide tiers itself. The window after a reboot was smaller, but what it installed had no LAN exemptions and no app exclusions — a reboot with an exit node selected took the router, the NAS and adb over Wi-Fi with it, and the excluded apps were not excluded — and it could not see another VPN client at all. T2 and T3 are yielded to another client from 4.0.0 on.
- **Up to 4.1.0** the boot script left the socket, state and log at `777`/`777`/`666` until the app next started the daemon, and a boot-started daemon had no proxy listeners at all, so Taildrive in the Files app and LAN clients were dead until a manual restart.
- An unquoted line in the env file is dropped — which is how **Ignore other VPNs** used to revert after every reboot.
- **Up to 3.6** the app and the boot script ran a fixed `magiskpolicy --live "allow untrusted_app magisk unix_stream_socket connectto"` on every start and every boot. It granted the access to every untrusted app on the device and named domains that are wrong on most of them (`untrusted_app` is not what an app with a modern `targetSdk` runs as; `magisk` is not KernelSU's or APatch's domain), so it often did nothing but widen the policy.
- **Up to 4.10** a start probed the socket once: a daemon that had not opened it yet (the old socket file refuses connections until it listens) was killed as stale, one without a socket file got a second daemon beside it, and one that answered `NoState` was killed as unconfigured.

</details>
