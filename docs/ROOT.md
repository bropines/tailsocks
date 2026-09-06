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
* **Boot-time routing:** Once `tailscale0` appears, the script installs the tiers it can put in without an app process (section 4 below): the tailnet rules — masked fwmark `0x1000000/0x1000000` → table `1099` and the `TAILSOCKS_MARK` chain, IPv4 and IPv6 — and, once the daemon has confirmed that it marks its own sockets, the exit-node catch-all and the `TAILSOCKS_DNS` redirect. The redirect is installed only if neither `accept_dns` nor `root_dns_redirect` is set to `false` in `shared_prefs/tailsocks_global.xml`; leftover rules from 3.5.x (bare mark `1099`, direct `OUTPUT` entries) are removed first. What the script cannot do is anything that needs a package resolved to a uid or a look at the live network: there are no per-app exclusions and no LAN `throw` routes at boot, and no other VPN client has started yet at `late_start`, so the device-wide tiers go in unconditionally. The app re-evaluates all of it when it attaches to the daemon and takes down what should not be there (section 5). The `service.d` copy of the script is refreshed automatically on app update (section 3).

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
| **T1 — tailnet reachability** | table `1099`, the two priority-100 rules, the `TAILSOCKS_MARK` chain, the `tailscale0` FORWARD pair, the `/etc/hosts` publication and the loopback SOCKS5/HTTP proxies | only packets this device sends to a tailnet address |
| **T2 — default-route capture** | the priority-200 catch-all into the daemon's table `52`, the LAN `throw` routes, the `rp_filter` loosening, and the priority-190 per-app exclusions that punch holes in it | everything this device sends, whenever an exit node is selected |
| **T3 — device-wide DNS** | the `TAILSOCKS_DNS` chain and its two `nat OUTPUT` hooks | every port-53 packet on the device |

T1 coexists with anything, by construction. T2 and T3 claim the whole device and
therefore have exactly one owner: when another VPN client already holds
Android's VPN slot, both are yielded to it — see section 5.

#### T1 — tailnet reachability

* **Table `1099`** carries `100.64.0.0/10` (and `fd7a:115c:a1e0::/48`) via
  `tailscale0`. Traffic is steered into it by two rules at priority 100:
  `ip rule … fwmark 0x1000000/0x1000000 table 1099` for marked packets, and
  `ip rule … to 100.64.0.0/10 iif lo table 1099` by destination. The second one
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
    borrow a verdict; a log with no run start at all is treated as unverified
    and gets no priority 200. In every negative case the ROOT log says
    `exit node unavailable: daemon does not mark sockets (<reason>)` and nothing
    is installed at priority 200; tailnet routing through table 1099 is
    unaffected. The boot script waits up to 20 s for the same line in its own
    run's part of the log before adding the rule; lines tagged `TailSocks:` are
    the app's and the script's own and never count.
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
DNS). It is also yielded automatically whenever another VPN client holds the
device, so that switch is no longer the only way out of that conflict.

The hook sits at position 1 of `nat OUTPUT`, which means the destination is
rewritten *after* the kernel has already chosen a source address. That is why
this tier cannot be shared: while another tunnel owned the device, its members'
queries entered ours carrying **that tunnel's** address as source — measured as
a stream of `netstack inject ts-service: src=100.100.100.100 dst=<the other
tunnel's address>` fast enough to trip the daemon's own log rate limiter.

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

These rules go in **only** together with the catch-all they are a hole in. While
another VPN client holds the device nothing is captured in the first place, so
no exclusion rule is installed either — one would jump the app over that
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

So T2 and T3 have one owner per device, and from 4.0.0 TailSocks yields them.

#### The three states

| Situation | Installed | What works |
|---|---|---|
| **TailSocks alone** — nothing else holds the VPN slot | T1 + T2 + T3 | Everything, exactly as before: exit nodes, accepted subnet routes, system-wide MagicDNS, per-app exclusions. |
| **Another client holds the device** | T1 only | The node stays connected. Tailnet addresses are reachable from every app, MagicDNS names resolve through the `/etc/hosts` publication, and the loopback SOCKS5/HTTP proxies keep working — with exit-node egress for whatever is pointed at them. *Not*: the exit node or accepted subnet routes for apps that are not pointed at those proxies, and not system-wide DNS. The other client keeps its apps and its resolver, unharmed. |
| **Both, with the override on** | T1 + T2 + T3 | Our side is complete; the other client's apps lose their traffic and their name resolution for as long as TailSocks runs. |

The middle row is what happens by default, and it is not a failure state, so
the app says as much rather than reporting a fault: the dashboard reads **Tailnet only**
instead of the usual Root line, a selected exit node is shown as not in use
rather than pretending to carry traffic, and both **Tunnel mode** and **DNS** in
Settings carry a note explaining what is switched off and until when. The Logs
screen (ROOT tab) records which tiers went in and why, and **Check Routing**
shows the same verdict. An empty priority 200 is healthy there.

What is *not* installed is also actively **removed**: an earlier apply's
catch-all, the LAN `throw` routes we wrote into table 52, the `rp_filter` change
and both DNS hooks are taken down when the tier is yielded. Skipping an install
would remove nothing, and the boot script installs these before any app process
exists (below).

The decision is re-made whenever the situation changes — another client starting
or stopping, or a setting that feeds the choice — not once per daemon run, so
neither yielding nor taking the device back waits for a restart.

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
device", never the reverse. The verdict and the reason behind it are written to
the ROOT log on every apply.

#### The override

**Take the device anyway** (Settings → Tunnel mode, with the rest of the Root
Mode switches) installs all three tiers regardless. It is off by default,
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
almost every time. It does not try: the script installs what it can, and the app
makes the real decision at its first apply and removes what should not be there.
This is why the removal is explicit rather than a skipped install.

With **Keep running in background** off, the app is not started at boot at all,
and the boot script's rules stand until you open TailSocks.

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
  tunnel interfaces present on the device; tables 1099 and 52 and every
  `tailscale0` route; the route decision for `8.8.8.8` with and without the
  daemon's bypass mark, **per excluded uid** (a `tailscale0` answer there means
  that app is not excluded after all) and for the first excluded local prefix (a
  `tailscale0` answer means the LAN is going through the exit node); any rule
  that is installed but never evaluated, i.e. a `goto` the kernel could not
  resolve; every `--set-xmark` in `mangle`, ours and anyone else's, so a second
  app fighting over the fwmark is visible; `rp_filter` and the saved original;
  the daemon log's last run marker and `SO_MARK` line together with the verdict
  the app drew from them (why priority 200 was or was not installed); the
  **coexistence verdict** — whether another tunnel owns this device and what it
  was recognised by; the chains, with packet counters on the DNS chain, which
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
# catch-all, absent while another VPN holds the device; no 52xx expected)
su -c ip rule show
su -c ip route show table 1099
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
  1099, route decisions with and without the mark, both chains **with packet
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
