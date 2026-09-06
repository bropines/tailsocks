# TailSocks Root Integration & System Service Guide

TailSocks supports advanced **Root Mode** for Android devices running root solution (e.g. **Magisk**, **KernelSU**, or **APatch**). Root mode allows the `tailscaled` core daemon to run as a native system daemon independently of the Android UI lifecycle, auto-start on device boot, and provide a full system-wide `tailscale` Command Line Interface (CLI) in terminal environments.

---

## ⚡ Key Architecture & Features

### 1. System Autostart via `service.d` (`tailscaled.sh`)
* **Path:** `/data/adb/service.d/tailscaled.sh`
* **Execution:** Executed automatically by Magisk / KernelSU / APatch during early boot (`late_start` service phase) under `root` (UID 0).
* **State Directory Auto-Resolution:** Automatically detects existing account state directories inside `/data/data/io.github.bropines.tailscaled/files/states/` (defaults to `default` or `root`).
* **Socket & Log Management:** Uses `/data/data/io.github.bropines.tailscaled/files/tailscaled.sock` with native `0666` socket permissions (via TailSocks atomic safesocket patches) and handles log rotation (`tailscaled.log`) automatically when size exceeds 2 MB.
* **Proxy & Control Plane Propagation:** Reads control proxy environment settings from the root-owned file `/data/adb/tailsocks/control_proxy.env` (including `ALL_PROXY`, `HTTP_PROXY`, `HTTPS_PROXY`, and pre-resolved `TS_STATIC_HOSTS` overrides). The app writes it through `su` whenever the root daemon starts; the script only accepts `export NAME='value'` lines and never executes anything from the app's own data directory, which the app (or a restored backup) could write to.
* **Boot-time routing:** Once `tailscale0` appears, the script installs tier T1 and nothing else (section 4): the tailnet rules — masked fwmark `0x1000000/0x1000000` → table `53`, the destination rules for the tailnet ranges, the `TAILSOCKS_MARK` chain and the `tailscale0` FORWARD pair, IPv4 and IPv6 — plus the removal of leftovers from 3.5.x (bare mark `1099`, direct `OUTPUT` entries). It installs **no** exit-node catch-all and **no** system-wide DNS redirect, because it cannot install what makes those two safe: the LAN `throw` routes and the per-app exclusions need packages resolved to uids, there is no `PackageManager` at `late_start`, and no other VPN client has started yet either, so a coexistence check made here would answer "the device is free" every time. A claim without its exemptions is worse than no claim — that is how a reboot with an exit node selected used to take the local network and the user's excluded apps with it. The app installs both device-wide tiers, with their exemptions and after the coexistence check, on its first `Running` tick (sections 4 and 5). **The cost:** with **Keep running in background** off, the app is not started at boot at all, so after a reboot there is no exit node and no system-wide MagicDNS until you open TailSocks — the node is up and the tailnet is reachable, but nothing else on the device is routed through it. The `service.d` copy of the script is refreshed automatically on app update (section 3).

### 2. Tailscale CLI Integration (`tailscale_cli.sh`)
* **Installed Wrapper Paths:**
  * `/data/adb/service.d/tailscale` (Fallback executable)
  * `/data/adb/modules/tailscaled/system/bin/tailscale` (Magisk system overlay module)
  * `/product/bin/tailscale` (Live product partition overlay)
  * `/system/bin/tailscale` (Direct system link if partition is writable)
* **Functionality:** Wraps the native `libtailscale_cli.so` binary extracted from the app package, automatically appending `--socket=/data/data/io.github.bropines.tailscaled/files/tailscaled.sock` to any command.
* **Usage:** Allows running standard `tailscale` CLI commands in Termux, `su` shell, ADB shell, or scripts.

### 3. Automatic Update & Boot Sync (`BootReceiver`)
* **Events Listened:** `ACTION_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, and `ACTION_MY_PACKAGE_REPLACED`.
* **Auto-Refresh Logic:** When TailSocks is updated (APK replaced), `BootReceiver` automatically re-installs the `service.d` daemon script and CLI wrapper script from assets. This guarantees that paths to `libtailscale.so` and `libtailscale_cli.so` inside `/data/app/...` stay perfectly aligned with the newly installed version without manual intervention.

### 4. Native TUN routing (`tailscale0`)

When **Native Linux TUN** is enabled, the daemon creates a real kernel interface
`tailscale0`, leaving Android's VPN slot free. TailSocks installs the policy
routing around it once the daemon reaches the `Running` state.

The rules come in three tiers, and what separates them is how much of the device
each one claims:

| Tier | What it is | What it claims |
|---|---|---|
| **T1 — tailnet reachability** | table `53`, the two priority-100 rules, the `TAILSOCKS_MARK` chain, the `tailscale0` FORWARD pair, the `/etc/hosts` publication and the loopback SOCKS5/HTTP proxies | only packets this device sends to a tailnet address |
| **T2 — default-route capture** | the priority-200 catch-all into the daemon's table `52`, the LAN `throw` routes, the `rp_filter` loosening, and the priority-190 per-app exclusions that punch holes in it | everything this device sends, whenever an exit node is selected |
| **T3 — device-wide DNS** | the `TAILSOCKS_DNS` chain and its two `nat OUTPUT` hooks | every port-53 packet on the device |

T1 coexists with anything, by construction. T2 and T3 claim the whole device and
therefore have exactly one owner: when another VPN client already holds
Android's VPN slot, they are yielded to it — entirely, or down to the apps that
client bypasses. Section 5 has the three states and how the line between them is
drawn.

All three tiers are installed by the app, on its first `Running` tick. The boot
script installs T1 only: the softeners that make T2 and T3 safe — the LAN
`throw` routes, the per-app exclusions, the per-uid DNS carve-outs — all need
packages resolved to uids, and there is no `PackageManager` at `late_start`
(section 1).

#### T1 — tailnet reachability

* **Table `53`** carries `100.64.0.0/10` (and `fd7a:115c:a1e0::/48`) via
  `tailscale0`. Traffic is steered into it by two rules at priority 100:
  `ip rule … fwmark 0x1000000/0x1000000 table 53` for marked packets, and
  `ip rule … to 100.64.0.0/10 iif lo table 53` by destination. The second one
  matters for the daemon's own sockets: the mark is applied in `mangle OUTPUT`,
  *after* the kernel has already chosen a source address from the Wi-Fi table,
  so without it the daemon's queries to a split-DNS resolver on a peer left
  `tailscale0` with the Wi-Fi address as source and never got an answer. That
  destination rule carries `iif lo`, which restricts it to packets originating
  on this device: a packet merely passing through the phone — from a tethered
  client, or through a subnet router — is left to Android's own rules instead of
  being claimed by us and answered with our source address. The mark rule needs
  no such guard, because the mark is only ever set in `mangle OUTPUT`, which
  forwarded packets never traverse.

  Every build up to 4.0.0 used table `1099` instead. netd names its per-network
  tables after the interface index plus 1000, so on a phone that has cycled
  enough interfaces for an index to reach 99 that table belongs to a live
  network — and the cleanup used to flush it. Nothing is written there any more;
  every apply and every stop still removes what an older install left in it, by
  content and never with a flush. If you are looking at a device that has not
  run 4.0.0 yet, look in `1099`.
* **`TAILSOCKS_MARK`** (mangle, hooked from `OUTPUT` for the tailnet ranges) sets
  that mark with `--set-xmark 0x1000000/0x1000000`. Only a single high bit is
  touched through a mask: Android packs its own routing decision into fwmark (the
  netId in the low 16 bits, then the explicit/protect/permission flags), and the
  bare `--set-mark 1099` used before 4.0.0 overwrote all of it, which broke
  routing whenever another VPN owned the default network. The old mark and the
  old un-chained rules are removed automatically on the first start after an
  upgrade.
* **Why this tier never conflicts.** `ip rule` is evaluated in ascending
  priority, so priority 100 is consulted before every rule netd writes for a VPN
  app. That is fine here precisely because these rules match nothing but tailnet
  destinations, and a sane tunnel client does not route the tailnet ranges for
  itself. Measured with a real one up (Exclave): it covers the IPv4 space in
  `/8`-sized pieces but installs `100.0.0.0/10` and `100.128.0.0/9` and
  deliberately not `100.64.0.0/10` — so the tailnet stays reachable from inside
  its tunnel with no configuration on either side.

#### T2 — default-route capture (exit nodes, subnet routes, table `52`)

Everything only the daemon knows goes into its own table `52`: peer `/32`s,
accepted subnet routes, `100.100.100.100/32`, `default dev tailscale0` while an
exit node is selected, and `throw` routes for the LAN prefixes when *allow LAN
access* is on. The app never writes to, flushes or checks that table; it adds
one rule next to the priority-100 ones:

```
200: from all fwmark 0x0/0x2020000 iif lo lookup 52
```

Every unmarked packet this device sends consults table 52 first, so switching an
exit node on or off is handled entirely by the daemon's `router.Set()`, as on
desktop Linux — no exit-node state in the app, nothing to redo on a
Wi-Fi/cellular handover.

What this rule does **not** do is fall through to Android's own rules once an
exit node is selected. Table 52 then holds a default route, so the very first
lookup matches and every app on the device follows the exit node — including the
apps that belong to another VPN client's tunnel, whose rules netd installs at
priority 13000 and 16000-17000 and which are therefore never reached. That is
the whole reason this tier is yielded when someone else owns the slot (section
5). Two things make the rule safe when the device *is* ours:

  * **The daemon marks its own sockets.** Every socket tailscaled opens itself
    (WireGuard/disco UDP, DERP, control plane, STUN, DNS fallbacks) carries
    `SO_MARK 0x2000000` (bit 25, patch 16 — outside Android's own fwmark bits
    0-20 and apart from the app's bit 24). Those packets skip priority 200 and
    reach the physical network through netd's rules. Without the mark, table 52's
    default route would swallow the tunnel's own packets and loop the whole
    device — so TailSocks installs priority 200 **only after** the current daemon
    run has logged `netns: SO_MARK 0x2000000 set on tailscaled sockets` (as one
    contiguous string; the line carries Go's `YYYY/MM/DD HH:MM:SS` prefix and a
    component prefix such as `magicsock:` in front). The daemon appends to one
    log file across runs and prints no start banner of its own in this build
    (`ts_omit_logtail`), so "current run" means everything after the later of
    two lines: `TailSocks: daemon start`, which both launchers — the boot script
    and the app — write right before starting the daemon, and the daemon's own
    `wgengine.NewUserspaceEngine(tun "tailscale0") ...`, which every core logs
    on every start just before the engine (and with it the probe) is created.
    The log is scanned backwards to that run start, however long the daemon
    has been running, and the probe line counts only if a run start lies below
    it — so neither an earlier run's line nor a daemon restarted by hand can
    borrow a verdict. A log that is merely *silent* — no run start at all, or a
    run start with no probe line under it — is not a "no": the log rotates, and
    a daemon that has been up for days has long since scrolled its start away,
    which on a real device withheld the exit node for a reason that had nothing
    to do with routing. In that case TailSocks asks the running process instead,
    and accepts it when `/proc/<pid>/exe` resolves into the app's own
    `nativeLibraryDir` — the binary we ship marks its sockets unconditionally.
    An explicit negative logged *by the daemon* is left standing and is never
    overridden this way, and when neither witness can be had, priority 200 stays
    out. In every negative case the ROOT log says
    `exit node unavailable: daemon does not mark sockets (<reason>)` and nothing
    is installed at priority 200; tailnet routing through table 53 is
    unaffected. Lines tagged `TailSocks:` are the app's and the boot script's own
    and never count as the daemon's. The boot script does not read this gate at
    all — it installs no priority-200 rule, so it has nothing to gate.
  * **The mask honours Android's `protectedFromVpn` bit** (`0x20000`), the idiom
    netd itself uses for its VPN rules: sockets Android deliberately keeps off
    VPNs — network validation probes, MMS/IMS, and another VPN client's *own*
    protected sockets — bypass the exit node as well. That covers the other
    client's tunnel sockets, **not** the apps it routes: an ordinary app's
    packets carry no protect bit, which is exactly how they ended up in our exit
    node. Widening the mask does not fix that and was measured not to
    (`ip route get 1.1.1.1 mark 0x84 uid <member app>` still answers
    `tailscale0`) — no mark can outrank a rule that is consulted first, so
    yielding the tier is the only fix. `iif lo` limits the rule to output
    lookups, so tethered clients stay on netd's rules.

Replies to the daemon's marked sockets arrive on Wi-Fi/cellular and are
reverse-path checked with mark 0, which now resolves to `tailscale0` through
table 52, so a strict `rp_filter` (1) would drop them: if any interface is
strict, `all` is set to loose (2), the previous value is kept in
`/data/adb/tailsocks/rp_filter.orig` and restored on cleanup — or as soon as
this tier is yielded, since loosening it has no purpose without the catch-all.
The rule is read back after installing (an `ip` binary that dropped the mask
would leave a match-everything rule, which is removed again and reported), and
the desktop-Linux rules a pre-4.0 core left behind (`5210 lookup main`, `5230
lookup default`, `5250 unreachable`, `5270 lookup 52`) are purged by content on
every apply and on cleanup.

On Android the daemon's router does only what only it can do: link up,
addresses, routes into table 52. It installs no `52xx` ip rules (Android's
`main`/`default` tables are empty, so the desktop rules either loop or
blackhole) and no netfilter at all — Tailscale's `0x40000`/`0x80000` marks
are netd's permission bits, and the nft `nat` chain type is missing on some
kernels, which is where the old `router config failed` health warning came
from. Root Mode is client-only: this device cannot act as an exit node or
subnet router while in Root Mode.

#### T3 — device-wide DNS (`TAILSOCKS_DNS`)

`TAILSOCKS_DNS` (nat, hooked from `OUTPUT` for port 53 UDP/TCP) redirects
system-wide DNS to MagicDNS (`100.100.100.100`). Three classes of traffic leave
the chain before the redirect, in this order:

* the apps on the excluded list — as far as that is possible at all, which is
  less far than it sounds (see below);
* the tailnet range `100.64.0.0/10`, so Split DNS resolvers hosted on peers
  are reached directly;
* the daemon's own upstream resolvers (`TS_DNS_FALLBACK`, mirrored from the
  DNS fallback setting, including the built-in defaults the daemon is given
  when that setting is empty). Without this, the resolver's own queries are
  redirected back into itself and no external name ever resolves.

The redirect is installed **only** when `accept-dns` is on — redirecting the
whole device to a resolver that is not answering would break DNS entirely — and
can be turned off by hand with **System-wide DNS via MagicDNS** (Settings →
DNS). It is also given up automatically when another VPN client holds the
device: entirely in the full yield, and in the partial one only for that
client's own network, which leaves the chain with a `RETURN` at its head matched
on the other tunnel's netId (section 5). Either way that switch is no longer the
only way out of the conflict.

The hook sits at position 1 of `nat OUTPUT`, which means the destination is
rewritten *after* the kernel has already chosen a source address. That is why
another tunnel's queries must leave this chain before the DNAT rather than be
redirected and sorted out later: while another tunnel owned the device, its
members' queries entered ours carrying **that tunnel's** address as source —
measured as a stream of `netstack inject ts-service: src=100.100.100.100
dst=<the other tunnel's address>` fast enough to trip the daemon's own log rate
limiter. The `RETURN` that keeps them out is matched on the netId in the fwmark,
not on a uid, for a reason section 5 spells out.

#### Per-app exclusions, and what they actually cover

With T2 and T3 installed, Root Mode claims the whole device: the DNS redirect
catches every app's port 53, and the catch-all sends every unmarked local packet
into the exit node. The apps listed under **Settings → Tunnel mode → Excluded
apps** (the same list TUN mode hands to `addDisallowedApplication`: one list
governs both tunnel modes) are resolved to uids on every apply and carved out
with two rules per uid:

```
ip rule add uidrange 10234-10234 goto <netd's lowest priority> priority 190
iptables -t nat -A TAILSOCKS_DNS -m owner --uid-owner 10234 -j RETURN
```

**Routing — this part works.** The policy rule jumps over our own catch-all and
lands on netd's rules, so the app ends up wherever the platform says it belongs:
inside another VPN client's tunnel when one is up, on the physical network when
none is. Pinning it to a physical table instead took the app out of that VPN as
well, which is why Chrome went dark with a "bad config" DNS error while its
tunnel client saw no requests at all. The jump target is read off the device
rather than assumed — the lowest priority at or above 10000 that this phone
actually has a rule at; `16000` was hardcoded before 4.0.0 and is not universal.
A `goto` to a priority that holds no rule is accepted by the kernel, printed as
`[detached]`, and then silently skipped at lookup time, so every rule is read
back: a detached one is deleted and replaced by a rule pinning the uid to the
physical table, and the Logs screen says the app will not ride another VPN
either. Where even that is impossible, the app is simply **not** excluded — it
stays in the tunnel like everything else, instead of being left half-routed.

The priority-100 rules sit above all of this, so an excluded app still reaches
tailnet addresses through `tailscale0`. Only its default route goes back to the
system.

These rules go in **only** together with the catch-all they are a hole in, which
includes the partial yield: whatever the catch-all captures, an exclusion can
take back out. In the full yield nothing is captured in the first place, so no
exclusion rule is installed either — one would jump the app over the other
client's rules as well and take it out of the tunnel it is supposed to be in.

**DNS — read this before relying on it.** `-m owner --uid-owner` matches the uid
of the process that *sends* the packet, and on Android an ordinary app does not
send its own DNS queries: it calls `getaddrinfo`, and the platform resolver
emits the query under a system uid. The `RETURN` therefore only covers apps that
resolve for themselves — Chrome's built-in asynchronous resolver, DoH/DoT
clients, `curl` and anything else in Termux, an app shipping its own resolver
library. For every other app the exclusion has no effect on DNS whatsoever: its
lookups are redirected into MagicDNS exactly like an unexcluded app's.

So what an exclusion actually buys depends on the app:

* an **ordinary** excluded app keeps resolving MagicDNS names, because its
  lookups still go to MagicDNS, while its traffic leaves outside the tunnel;
* an app that **resolves for itself** does get the system resolver back, and
  MagicDNS names then resolve for it only through the `/etc/hosts` publication —
  split-DNS domains not at all.

If an app must keep the system resolver for certain, turn **System-wide DNS via
MagicDNS** off. That switch is the only one that covers platform-resolver
traffic, and it is what an ad blocker or a private-DNS app on the same device
needs.

A package that is no longer installed is skipped and named in the ROOT log.
`-m owner` is an optional kernel module (`xt_owner`): where it is missing the
DNS `RETURN` rules are refused and the ROOT log says so, while the routing
exclusion — a policy rule, not a firewall match — is unaffected. On such a
kernel an excluded app is outside the tunnel and resolving inside it, which the
same log line names. An earlier iteration of this feature also gave excluded
uids the daemon's bypass mark in a mangle chain (`TAILSOCKS_BYPASS`); that chain
is gone, because a mark set in `mangle OUTPUT` arrives after the kernel has
already chosen the route and the source address for the connection and so could
not do the job the policy rules do. It is still detached and deleted on every
apply, so an upgrade leaves nothing behind.

With an empty exclusion list nothing at all is emitted for this feature.

Everything else lives in named chains, so the rules are idempotent, can be
inspected with `iptables -t nat -S TAILSOCKS_DNS` / `iptables -t mangle -S
TAILSOCKS_MARK`, and are replaced as a unit on every apply and removed in one
shot when the service stops. The script verifies its own result (table
populated, DNS chain present when it should be) instead of trusting the shell's
exit code, and after three failed attempts it gives up and says so in the log.

### 5. Living next to another VPN client

Every number in this section was read off a device, not inferred. The raw
capture — netd's own rules, uids, netIds, interfaces and DNS servers, with the
commands that produced them — is
[`docs/research/foreign-vpn-measurements.md`](research/foreign-vpn-measurements.md);
the design decisions taken from it, and the open items still on the roadmap, are
in `docs/research/root-exit-node-design.json`.

Android hands its VPN slot to one app at a time, and it does not arbitrate what
a rooted device does below that. `ip rule` is evaluated in ascending priority,
and our rules at 100 and 200 are consulted before every rule netd writes for a
VPN app — measured at priority 13000 and 16000-17000 on the author's phone.
Whatever we claim, we claim first, including traffic that belongs to somebody
else's tunnel.

What claiming too much costs, measured with a real client up (Exclave,
`com.github.dyhkwong.sagernet`, with an exit node selected on our side):

* Chrome, a member of the foreign tunnel, resolved to `dev tailscale0 table 52`.
  Its packets went into **our** exit node and its own client never saw them. The
  browser went dark.
* Every port-53 packet on the device was rewritten into MagicDNS, including the
  queries the platform resolver made on behalf of that tunnel's apps — and, per
  T3 above, carrying the other tunnel's source address when they arrived.

So T2 and T3 have one owner per device, and from 4.0.0 TailSocks gives them up —
either whole, or down to the part of the device the other client did not take.

#### The three states, and the override

| Situation | Installed | What works |
|---|---|---|
| **TailSocks alone** — nothing else holds the VPN slot | T1 + T2 + T3, device-wide | Everything, exactly as before: exit nodes, accepted subnet routes, system-wide MagicDNS, per-app exclusions. |
| **Another client holds the device, and claims every app** | T1 only | The node stays connected. Tailnet addresses are reachable from every app, MagicDNS names resolve through the `/etc/hosts` publication, and the loopback SOCKS5/HTTP proxies keep working — with exit-node egress for whatever is pointed at them. *Not*: the exit node or accepted subnet routes for apps that are not pointed at those proxies, and not system-wide DNS. The other client keeps its apps and its resolver, unharmed. |
| **Another client holds the device, but bypasses some apps** | T1 + T2 scoped to the bypassed uids + T3 minus that client's own network | The apps that client leaves to the physical network follow our exit node and our subnet routes instead, and MagicDNS answers for the device — except for the queries that client's own tunnel makes, which are left alone. Its members are untouched: their traffic stays in its tunnel and their names are resolved by whoever it points them at. |
| *(not a state, but the way out of both)* **the override on** | T1 + T2 + T3, device-wide | Our side is complete; the other client's apps lose their traffic and their name resolution for as long as TailSocks runs. |

The middle two rows are what happens by default, and neither is a failure state,
so the app says as much rather than reporting a fault — and it says which of the
two it is, because they are not the same offer. In the **full yield** the
dashboard reads **Tailnet only** instead of the usual Root line, a selected exit
node is shown as **not in use** rather than pretending to carry traffic, and both
**Tunnel mode** and **DNS** in Settings say what is switched off and until when.
In the **partial yield** nothing is switched off, so nothing claims it is: the
dashboard reads **Shared with another VPN**, the exit node stays an active row
labelled **Exit node: partial**, and Settings says which apps the tiers went in
for rather than what was given up. The Logs screen (ROOT tab) records which tiers
went in and why, and **Check Routing** shows the same verdict. An empty priority
200 is healthy in the full yield; a priority 200 that carries one rule per
bypassed uid range, rather than one rule for the whole device, is healthy in the
partial one.

#### The partial yield: carrying what the other tunnel bypasses

A VpnService that tunnels only some apps hands the rest to the physical network.
Those apps are nobody's: the other client asked not to have them, and Android
routes them exactly as it would with no VPN at all. Taking them costs that
client nothing, and it is the difference between a phone where one tunnel works
and a phone where both do.

**The gaps are read from netd's own rules, not guessed.** Android expresses VPN
membership as uid ranges, in the clear, and a root shell can read them:

```
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 0-10353 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10355-10374 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10376-99999 lookup tun0
```

The ranges are the members; the **gaps between them** are the apps that client
bypasses — above, uid 10354 (the client itself) and 10375 (TailSocks). Only
rules whose table is the foreign tunnel's own interface are counted. That last
part is load-bearing: a physical network's *local* table (`wlan0_local`) carries
a `uidrange` spanning every uid on the device, and folding it in leaves no gaps
at all, which silently turns the partial yield back into a full one.

For every gap that survives, one catch-all rule is installed instead of the
device-wide one:

```
200: from all fwmark 0x0/0x2020000 iif lo uidrange 10380-10380 lookup 52
```

Same selector as the device-wide form, restricted to uids the other tunnel does
not want. `ip rule` has no "not these uids", so this is one rule per range —
which is exactly as many as the other client itself installs.

**Two kinds of uid are never carried**, and both had to be excluded by hand:

* **A VPN client's own uid.** A VpnService's owner is always outside its own
  tunnel — it would loop its relay traffic through itself otherwise — so its uid
  is *always* a gap, whether or not its owner bypassed anything. Carrying it
  would send that client's connection to its own relay out through our exit
  node, which nobody asked for, and it would also mean the gap list is never
  empty, so the full yield could never be reached at all. Android knows which
  apps these are and answers without root and without a permission
  (`PackageManager.queryIntentServices(Intent("android.net.VpnService"))`), so
  we ask it rather than guess.
* **uids no application owns.** Android reserves an SDK sandbox uid next to
  every app (`appId + 10000`), so netd's membership list has a hole there too.
  Measured on the device: uid 20354 sat in a gap with no package behind it, and
  carrying it wrote a rule that could never match anything. The carried set is
  therefore built positively — *installed* applications, minus VPN clients,
  minus ourselves — rather than by subtracting what looks wrong. A gap small
  enough to walk (up to 64 uids, the ordinary case of a handful of bypassed
  apps) is resolved app by app; a large one — a client running in "tunnel only
  these apps" mode leaves most of the device outside — is kept whole with the
  known VPN uids cut out of it, because hundreds of one-uid rules cost more than
  a few rules that occasionally match nothing.

If nothing survives this, there is no partial yield: the gap list is empty and
the full yield is reached honestly, which is the second row of the table.

**DNS is split by netId, not by uid.** The other client's own resolution has to
stay its own, and a `-m owner --uid-owner` match provably cannot do that: on
Android an ordinary app does not send its own DNS queries — it calls
`getaddrinfo` and the platform resolver emits the query under *its* uid, not the
app's. What does survive is netd's network id, which it stamps into the low 16
bits of the fwmark on the resolver's packet. Measured over 18 s of browsing
inside a foreign tunnel, on `mangle OUTPUT`, `udp --dport 53`:

| mark (mask `0x1ffff`) | packets | meaning |
|---|---|---|
| `0x10084` | 6 | the resolver working on behalf of an app **inside** the other tunnel |
| `0x73` | 18 | physical-network resolution |

So the head of `TAILSOCKS_DNS` gets one rule per foreign netId, before
everything else in the chain:

```
iptables -t nat -A TAILSOCKS_DNS -m mark --mark 0x84/0xffff -j RETURN
```

and the rest of the device keeps MagicDNS. The netIds are read from the same
`ip rule` probe as the uid ranges (`fwmark 0x10084/0x1ffff` → netId `0x84`), and
they are part of what the app compares between ticks, so a foreign tunnel that
restarts with a new netId is noticed and the carve-out is rewritten.

**What the partial yield does not attempt.** Nothing tries to widen the
catch-all's mask so that a foreign tunnel's mark would escape it: ordinary app
traffic carries netId 0 in the mark, and it was measured directly —
`ip route get 1.1.1.1 mark 0x84 uid <member app>` still answers
`dev tailscale0 table 52`. No mark can outrank a rule that is consulted first,
which is why the scope has to be expressed as uids.

What is *not* installed is also actively **removed**: an earlier apply's
catch-all — device-wide or scoped — the LAN `throw` routes we wrote into table
52, the `rp_filter` change, the per-app exclusion rules and both DNS hooks are
taken down when the tier is yielded, and the scoped rules are likewise replaced
as a unit whenever the other tunnel changes which apps it bypasses. Skipping an
install would remove nothing, and these rules can be there from an earlier apply
of this run.

The decision is re-made whenever the situation changes — another client starting
or stopping, that client changing which apps it carries, or a setting that feeds
the choice — not once per daemon run, so neither yielding, nor narrowing the
scope, nor taking the device back waits for a restart.

#### How another client is recognised

Two independent signals, because neither is sufficient alone:

* **Android's own answer.** `ConnectivityManager` reports a network carrying the
  VPN transport. It only ever reports a tunnel this app is a *member* of, so a
  client that excludes TailSocks from its tunnel — the correct configuration,
  see below — is invisible to it. That is precisely the client we would break.
* **A root-side probe.** A tunnel interface that is not ours (`tun*`, `ppp*`,
  `wg*`, `ipsec*`) **and** per-uid rules inside netd's own priority band
  pointing at a table that is neither ours nor a physical one. That pair is how
  netd expresses "these apps are members of a VPN", and nothing else on the
  device writes it. Both halves are required: a stopped client leaves its
  interface behind, and a hand-routed WireGuard interface holds no VPN slot.

Either signal is enough to yield; the safe direction is "someone else owns the
device", never the reverse. The root-side probe is also where the shape of the
other tunnel comes from — its member uid ranges and its netIds, i.e. everything
the partial yield is built out of — so the same read answers both "is anyone
here" and "what did they leave". A malformed range is dropped rather than
guessed at: a wrong one would hand that tunnel's own apps to us. The app keeps
that shape and compares it on every refresh tick, because membership can change
without the tunnel going down and Android's capability callback cannot report it
(membership is redacted for a non-member, and a client that excludes us is
exactly the one that matters). The verdict, the inputs behind it — marks,
foreign, override, ranges, gaps, netIds — and the reason are written to the ROOT
log on every apply.

#### The override

**Take the device anyway** (Settings → Tunnel mode, with the rest of the Root
Mode switches) installs all three tiers device-wide regardless — no scoping, no
DNS carve-out for anyone else. It is off by default,
sticky, and deliberately not carried by a profile backup — it is consent about
*this* phone's other client, not a preference worth restoring elsewhere. The
confirmation dialog names the cost, and the ROOT log repeats it on every apply
that uses it.

Turn it on when the other tunnel is one you do not mind interrupting. Before
you do, note that most of what the yield gives up — exit-node egress, and
MagicDNS for an app that can be pointed at a resolver — is available through the
loopback proxies without taking the device from anybody; see below.

#### At boot

The `service.d` script runs at `late_start`, long before any other VPN client's
service has started, so a check made there would answer "the device is free"
almost every time — and it has no `PackageManager`, so it could not install the
LAN `throw` routes, the per-app exclusions or the per-uid DNS carve-outs even if
the answer were right. It therefore installs T1 and stops there (section 1). The
app makes the whole decision on its first `Running` tick, where the yield, the
scope and the softeners are all decided in the same breath.

**The cost of that, plainly.** With **Keep running in background** off the app is
not started at boot, so after a reboot the device has tailnet reachability and
nothing else: no exit node, no accepted subnet routes for other apps, and no
system-wide MagicDNS, until you open TailSocks. With it on, the window is the
few seconds between the daemon reaching `Running` and the app's first apply.
Before 4.0.0 the boot script installed the device-wide tiers itself and the
window was smaller, but what it installed had no LAN exemptions and no app
exclusions — a reboot with an exit node selected took the router, the NAS and
adb-over-Wi-Fi with it, and the excluded apps were not excluded — and it could
not see another VPN client at all.

#### What to configure in the other client

The yield needs no cooperation from the other side. But three settings in *its*
configuration decide how well the two live together, and a real client — Exclave
— was measured getting the first two right with no manual configuration at all.

1. **Exclude TailSocks from its tunnel** (per-app proxy in bypass mode, or its
   "excluded applications" list). The daemon's own sockets carry Android's
   protect bit and are routed by the physical network in any case, so the tunnel
   comes up either way; but everything else — the control plane through an HTTP
   proxy, updates, the admin API — would otherwise ride a second tunnel for no
   reason, and a failure in that client would then look like a TailSocks
   failure. Measured: Exclave's uid ranges already skip both its own uid and
   ours.
2. **Keep its own addresses out of the tailnet ranges.** Our priority-100 rules
   claim `100.64.0.0/10` and `fd7a:115c:a1e0::/48` for every app on the device
   and send them into `tailscale0`. Its relay or server address, its DNS server
   and the subnet of its own `tun` interface must all lie outside both.
   `100.64.0.0/10` is real CGNAT space and some clients do pick addresses inside
   it. Measured: Exclave routes the IPv4 space in `/8`-sized pieces but installs
   `100.0.0.0/10` and `100.128.0.0/9` and deliberately not `100.64.0.0/10`, and
   its own tunnel sits on `172.19.0.0/30`.
3. **Point its outbound at our loopback proxy** if it wants the tailnet, or an
   exit node, inside its own tunnel. The SOCKS5 proxy (Settings → Local proxies;
   `127.0.0.1:48115` by default, with the password from that screen if you set
   one) and the HTTP proxy are served by the daemon itself, so what goes through
   them follows the tailnet and the selected exit node no matter which tiers are
   installed on the device. For MagicDNS names inside that client, point its DNS
   at our local DNS server, `127.0.0.1:1053`. Measured with an exit node
   selected: connections made through the SOCKS5 proxy left at the exit node,
   and a tailnet peer answered over it.

Point 3 is why "Root Mode or a proxy front end" is a false choice. A front end
that picks an upstream per app can send the apps you choose to TailSocks — with
exit-node egress — while it keeps the device and everything else keeps working.

### 6. Diagnostics: Check Routing and the ROOT log tab

* **Settings → Diagnostics & developer → Check Routing** dumps the live state of
  everything above, and is the first thing to read when Root Mode behaves in a
  way this document does not describe. It prints: the complete `ip rule` /
  `ip -6 rule` lists (our priority-100 rules, the priority-200 catch-all when it
  is installed, the priority-190 exclusions, and any stale `52xx` rules); other
  tunnel interfaces present on the device; tables 53, 1099 and 52 and every
  `tailscale0` route; the route decision for `8.8.8.8` with and without the
  daemon's bypass mark, **per excluded uid** (a `tailscale0` answer there means
  that app is not excluded after all) and for the first excluded local prefix (a
  `tailscale0` answer means the LAN is going through the exit node); any rule
  that is installed but never evaluated, i.e. a `goto` the kernel could not
  resolve; every `--set-xmark` in `mangle`, ours and anyone else's, so a second
  app fighting over the fwmark is visible; `rp_filter` and the saved original;
  the daemon log's last run marker and `SO_MARK` line together with the verdict
  the app drew from them (why priority 200 was or was not installed); the
  **coexistence verdict** — whether another tunnel owns this device, what it was
  recognised by, and, when it bypasses apps, which uid ranges and which netId are
  being carried; the chains, with packet counters on the DNS chain, which
  tells "the rule is missing" apart from "the rule is there and never hit"; the
  `tailscale0` address and the `ip` version.
* Every root shell invocation (routing apply/cleanup, daemon stop, script
  install) is logged under the **ROOT** category, which has its own tab in the
  **Logs** screen. Failures land there instead of being silently discarded, and
  so does the one-line summary of every apply: which tiers went in, and why any
  of them did not.

### 7. SELinux: the on-demand `connectto` rule

The daemon is launched from a root shell, so it runs in the root solution's
domain (`magisk`, `su`, whatever the installed manager uses), while the app
connects to `tailscaled.sock` from its own app domain. Stock policy grants no
`connectto` on the daemon's `unix_stream_socket` across that pair, and on an
Enforcing device the connect fails with `EACCES` — which looks exactly like a
dead daemon.

Up to 3.6 the app and the boot script both ran a fixed
`magiskpolicy --live "allow untrusted_app magisk unix_stream_socket connectto"`
on every start and every boot. That granted the access to *every* untrusted app
on the device, and named domains that are wrong on most of them (`untrusted_app`
is not what an app with a modern `targetSdk` runs as; `magisk` is not KernelSU's
or APatch's domain), so it often did nothing but widen the policy.

Now: nothing is patched at boot, and `RootUtils.allowSocketConnect` runs when
the app attaches to (or waits for) the daemon socket. It connects first; only if
that connect is refused with a permission error does it read the two real
domains — the app's from `/proc/self/attr/current`, the daemon's from
`/proc/<pid>/attr/current` of the process matched by `--socket=` — and apply
`allow <app domain> <daemon domain> unix_stream_socket connectto` through
`magiskpolicy`, `ksud sepolicy patch` or `supolicy`. The rule is live-only and
gone after a reboot. Both the injection and a failure to inject are written to
the **ROOT** log tab; if no policy tool is available, Root Mode fails to reach
the daemon exactly as it would have without the rule.

`tools/root-debug.sh` prints `getenforce`, the shell and daemon contexts and the
last AVC denials, which is what a bug report about this needs.

### 8. Stop behaviour: Terminate Root Daemon on Stop

**Terminate Root Daemon on Stop** (Settings → Tunnel mode, on by default) sends
SIGTERM/SIGKILL to the root `libtailscale.so` process when you press Stop. Only
the daemon that was started for this app is matched — by its `--socket=` argument
pointing at the app's private data path — so a second profile, a Termux
`tailscaled`, or another app shipping the same library is never killed.

Turn it off to keep the root daemon running across app stops; the app then
merely detaches. On the next Start (or a settings change while the user wants the
connection) it re-attaches to the running daemon instead of starting a new one.
A manual Stop is final in either case: nothing revives the service until you
start it again.

---

## 📱 Enabling Root Integration in App

1. Open **TailSocks**.
2. Go to **Settings** → **Root Mode** tab.
3. **Grant Root Access:** Tap to verify `su` availability.
4. **Install Service Autostart:** Toggles deployment of `/data/adb/service.d/tailscaled.sh`.
5. **Install Tailscale CLI:** Toggles deployment of `/system/bin/tailscale` and Magisk module overlays.
6. The app displays real-time status (installed/not installed), target paths, and copyable script paths.

---

## 💻 CLI Usage Examples

Once CLI integration is installed, open any root terminal (`su` in Termux, ADB shell, or serial console) and run:

```bash
# Check node status and connected peers
su -c tailscale status

# Check Tailscale IP addresses
su -c tailscale ip

# Ping another node on your tailnet
su -c tailscale ping <node-ip-or-name>

# Perform network & NAT traversal check
su -c tailscale netcheck

# Check tailscaled daemon version
su -c tailscale version
```

---

## 🪵 Log & Troubleshooting

* **Daemon Logs:** Saved at `/data/data/io.github.bropines.tailscaled/logs/tailscaled.log`
* **Socket File:** Located at `/data/data/io.github.bropines.tailscaled/files/tailscaled.sock`
* **Magisk Module Prop:** `/data/adb/modules/tailscaled/module.prop`
* **In-app:** Logs screen → **ROOT** tab, and **Settings → Diagnostics & developer → Check Routing**.

### Useful Troubleshooting Commands:

```bash
# View live tailscaled logs
su -c tail -f /data/data/io.github.bropines.tailscaled/logs/tailscaled.log

# Check if tailscaled process is running under root
su -c ps -ef | grep tailscaled

# Verify socket permissions
su -c ls -la /data/data/io.github.bropines.tailscaled/files/tailscaled.sock

# Inspect the rules TailSocks owns (100: tailnet; 190: excluded apps; 200: exit-node
# catch-all — device-wide when nothing else holds the VPN slot, one rule per carried
# uid range in a partial yield, absent in a full one; no 52xx expected)
su -c ip rule show
su -c ip route show table 53
su -c iptables -t mangle -S TAILSOCKS_MARK
su -c iptables -t nat -S TAILSOCKS_DNS

# The daemon's table and the exit-node decision
su -c ip route show table 52                 # peers, subnet routes, 'default dev tailscale0' with an exit node
su -c ip route get 8.8.8.8                   # tailscale0 with an exit node and the device ours, Wi-Fi/cellular otherwise
su -c ip rule show | grep '^200:'            # empty is correct while another VPN holds the device
su -c ip route get 8.8.8.8 mark 0x2000000    # the daemon's own path: always the physical network
su -c grep -n 'TailSocks: daemon start' /data/data/io.github.bropines.tailscaled/logs/tailscaled.log | tail -n 1   # where the current run begins
su -c grep 'netns: SO_MARK' /data/data/io.github.bropines.tailscaled/logs/tailscaled.log   # expected after that line: '... set on tailscaled sockets (root bypass)'
```

### Diagnostic scripts (`tools/`)

Two scripts in the repository automate the above for bug reports:

* **`tools/root-debug.sh`** — a read-only snapshot to run on the device as root.
  It prints the app version, the relevant global settings, the daemon process
  and socket, the `tailscale0` interface and other tunnels, policy rules, table
  53 and 1099, route decisions with and without the mark, both chains **with packet
  counters** (which tells "the rule is missing" apart from "the rule is there
  but never hit"), leftover legacy rules, the `/system/etc/hosts` bind mount, a
  DNS resolution probe and the last 40 daemon log lines. Nothing is modified.

  ```bash
  adb push tools/root-debug.sh /data/local/tmp/
  adb shell su -c sh /data/local/tmp/root-debug.sh          # optional arg: package name
  ```

* **`tools/root-debug-session.sh <adb-serial> [package]`** — drives a full
  start/stop cycle over adb from the host and captures a snapshot at every phase
  into `./root-debug-<timestamp>/`: `00-before.txt`, `01-t{5,15,30}s.txt` while
  the daemon comes up, `02-after.txt`, `03-after-stop.txt` (this one shows
  whether any rules were left behind), plus `logcat.txt` and the in-app
  `applog.txt`. It starts and stops the service via
  `am start-foreground-service … -a START_ACTION` / `-a STOP_ACTION`.

  ```bash
  tools/root-debug-session.sh 192.168.1.83:44895
  ```

Attach the resulting directory (or the single snapshot) to a Root Mode issue.
