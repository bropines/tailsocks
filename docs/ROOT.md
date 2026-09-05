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
* **Boot-time routing:** Once `tailscale0` appears, the script installs the same rule layout as the app (section 4 below): masked fwmark `0x1000000/0x1000000` → table `1099`, the `TAILSOCKS_MARK` / `TAILSOCKS_DNS` chains, IPv4 and IPv6. The DNS redirect is installed only if neither `accept_dns` nor `root_dns_redirect` is set to `false` in `shared_prefs/tailsocks_global.xml`; leftover rules from 3.5.x (bare mark `1099`, direct `OUTPUT` entries) are removed first. The app re-applies the same rules as a unit when it attaches to the daemon, and the `service.d` copy of the script is refreshed automatically on app update (section 3).

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

### 4. Native TUN Routing (`tailscale0`)

When **Native Linux TUN** is enabled, the daemon creates a real kernel interface
`tailscale0`, leaving Android's VPN slot free. TailSocks installs the policy
routing around it once the daemon reaches the `Running` state:

* **Table `1099`** carries `100.64.0.0/10` (and `fd7a:115c:a1e0::/48`) via `tailscale0`.
  Traffic is steered into it by two rules at priority 100: `ip rule … fwmark
  0x1000000/0x1000000 table 1099` for marked packets, and `ip rule … to
  100.64.0.0/10 table 1099` (and the IPv6 range) by destination. The second one
  matters for the daemon's own sockets: the mark is applied in `mangle OUTPUT`,
  *after* the kernel has already chosen a source address from the Wi-Fi table,
  so without it the daemon's queries to a split-DNS resolver on a peer left
  `tailscale0` with the Wi-Fi address as source and never got an answer.
* **`TAILSOCKS_MARK`** (mangle, hooked from `OUTPUT` for the tailnet ranges) sets
  that mark with `--set-xmark 0x1000000/0x1000000`. Only a single high bit is
  touched through a mask: Android packs its own routing decision into fwmark (the
  netId in the low 16 bits, then the explicit/protect/permission flags), and the
  bare `--set-mark 1099` used before 4.0.0 overwrote all of it, which broke
  routing whenever another VPN owned the default network. The old mark and the
  old un-chained rules are removed automatically on the first start after an
  upgrade.
* **`TAILSOCKS_DNS`** (nat, hooked from `OUTPUT` for port 53 UDP/TCP) redirects
  system-wide DNS to MagicDNS (`100.100.100.100`). Two classes of traffic are
  explicitly excluded, in this order, before the redirect:
  * the tailnet range `100.64.0.0/10`, so Split DNS resolvers hosted on peers
    are reached directly;
  * the daemon's own upstream resolvers (`TS_DNS_FALLBACK`, mirrored from the
    DNS fallback setting). Without this, the resolver's own queries are
    redirected back into itself and no external name ever resolves.

  The redirect is installed **only** when `accept-dns` is on — redirecting the
  whole device to a resolver that is not answering would break DNS entirely —
  and can be turned off independently with **System-wide DNS via MagicDNS**
  (Root Mode tab), which is the escape hatch when another VPN or a DNS filtering
  app should keep the system resolver.
* **Exit nodes, subnet routes and table `52`.** Everything only the daemon knows
  goes into its own table `52`: peer `/32`s, accepted subnet routes,
  `100.100.100.100/32`, `default dev tailscale0` while an exit node is
  selected, and `throw` routes for the LAN prefixes when *allow LAN access* is
  on. The app never writes to, flushes or checks that table; it adds one
  permanent rule next to the priority-100 ones:

  ```
  200: from all fwmark 0x0/0x2020000 iif lo lookup 52
  ```

  Every unmarked, locally generated packet consults table 52 first and falls
  through to Android's own rules (10000 and up) when nothing matches, so
  switching an exit node on or off is handled entirely by the daemon's
  `router.Set()`, as on desktop Linux — no exit-node state in the app, nothing
  to redo on a Wi-Fi/cellular handover. Two things make that rule safe:

  * **The daemon marks its own sockets.** Every socket tailscaled opens itself
    (WireGuard/disco UDP, DERP, control plane, STUN, DNS fallbacks) carries
    `SO_MARK 0x2000000` (bit 25, patch 16 — outside Android's own fwmark bits
    0-20 and apart from the app's bit 24). Those packets skip pref 200 and reach
    the physical network through netd's rules. Without the mark, table 52's
    default route would swallow the tunnel's own packets and loop the whole
    device — so TailSocks installs pref 200 **only after** the current daemon
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
    and gets no pref 200. In every negative case the ROOT log says
    `exit node unavailable: daemon does not mark sockets (<reason>)` and nothing
    is installed at pref 200; tailnet routing through table 1099 is unaffected.
    The boot script waits up to 20 s for the same line in its own run's part of
    the log before adding the rule; lines tagged `TailSocks:` are the app's and
    the script's own and never count.
  * **The mask honours Android's `protectedFromVpn` bit** (`0x20000`), the idiom
    netd itself uses for its VPN rules: sockets Android deliberately keeps off
    VPNs (network validation probes, MMS/IMS, other VPN apps' protected
    sockets) bypass the exit node as well. `iif lo` limits the rule to output
    lookups, so tethered clients stay on netd's rules.

  Replies to the daemon's marked sockets arrive on Wi-Fi/cellular and are
  reverse-path checked with mark 0, which now resolves to `tailscale0` through
  table 52, so a strict `rp_filter` (1) would drop them: if any interface is
  strict, `all` is set to loose (2), the previous value is kept in
  `/data/adb/tailsocks/rp_filter.orig` and restored on cleanup. The rule is
  read back after installing (an `ip` binary that dropped the mask would leave
  a match-everything rule, which is removed again and reported), and the
  desktop-Linux rules a pre-4.0 core left behind (`5210 lookup main`, `5230
  lookup default`, `5250 unreachable`, `5270 lookup 52`) are purged by content
  on every apply and on cleanup.

  On Android the daemon's router does only what only it can do: link up,
  addresses, routes into table 52. It installs no `52xx` ip rules (Android's
  `main`/`default` tables are empty, so the desktop rules either loop or
  blackhole) and no netfilter at all — Tailscale's `0x40000`/`0x80000` marks
  are netd's permission bits, and the nft `nat` chain type is missing on some
  kernels, which is where the old `router config failed` health warning came
  from. Root Mode is client-only: this device cannot act as an exit node or
  subnet router while in Root Mode.

Everything lives in named chains, so the rules are idempotent, can be inspected
with `iptables -t nat -S TAILSOCKS_DNS` / `iptables -t mangle -S TAILSOCKS_MARK`,
and are replaced as a unit on every start and removed in one shot when the
service stops. The script verifies its own result (table populated, DNS chain
present) instead of trusting the shell's exit code, and after three failed
attempts it gives up and says so in the log.

### 5. Diagnostics: Check Routing and the ROOT log tab

* **Settings → Root Mode → Check Routing** dumps the live state of everything
  above: the complete `ip rule` / `ip -6 rule` lists (the app's pref-100 rules,
  the pref-200 catch-all, and any stale `52xx` rules), other tunnels present on
  the device, tables 1099 and 52, every `tailscale0` route, the route decision
  for `8.8.8.8` with and without the daemon's bypass mark, `rp_filter`, the
  daemon log's last run marker and `SO_MARK` line together with the verdict
  the app drew from them (why pref 200 was or was not installed), both chains,
  the `tailscale0` address and the `ip` version.
* Every root shell invocation (routing apply/cleanup, daemon stop, script
  install) is logged under the **ROOT** category, which has its own tab in the
  **Logs** screen. Failures land there instead of being silently discarded.

### 6. Stop behaviour: Terminate Root Daemon on Stop

**Terminate Root Daemon on Stop** (Root Mode tab, on by default) sends
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
* **In-app:** Logs screen → **ROOT** tab, and **Settings → Root Mode → Check Routing**.

### Useful Troubleshooting Commands:

```bash
# View live tailscaled logs
su -c tail -f /data/data/io.github.bropines.tailscaled/logs/tailscaled.log

# Check if tailscaled process is running under root
su -c ps -ef | grep tailscaled

# Verify socket permissions
su -c ls -la /data/data/io.github.bropines.tailscaled/files/tailscaled.sock

# Inspect the rules TailSocks owns (100: tailnet, 200: exit-node catch-all; no 52xx expected)
su -c ip rule show
su -c ip route show table 1099
su -c iptables -t mangle -S TAILSOCKS_MARK
su -c iptables -t nat -S TAILSOCKS_DNS

# The daemon's table and the exit-node decision
su -c ip route show table 52                 # peers, subnet routes, 'default dev tailscale0' with an exit node
su -c ip route get 8.8.8.8                   # tailscale0 with an exit node, Wi-Fi/cellular without
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
