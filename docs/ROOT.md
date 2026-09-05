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
  Traffic is steered into it by the `ip rule … fwmark 0x1000000/0x1000000 table 1099`
  rule (priority 100).
* **`TAILSOCKS_MARK`** (mangle, hooked from `OUTPUT` for the tailnet ranges) sets
  that mark with `--set-xmark 0x1000000/0x1000000`. Only a single high bit is
  touched through a mask: Android packs its own routing decision into fwmark (the
  netId in the low 16 bits, then the explicit/protect/permission flags), and the
  bare `--set-mark 1099` used before 3.6.0 overwrote all of it, which broke
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

Everything lives in named chains, so the rules are idempotent, can be inspected
with `iptables -t nat -S TAILSOCKS_DNS` / `iptables -t mangle -S TAILSOCKS_MARK`,
and are replaced as a unit on every start and removed in one shot when the
service stops. The script verifies its own result (table populated, DNS chain
present) instead of trusting the shell's exit code, and after three failed
attempts it gives up and says so in the log.

### 5. Diagnostics: Check Routing and the ROOT log tab

* **Settings → Root Mode → Check Routing** dumps the live state of everything
  above: `ip rule` entries for table 1099, other tunnels present on the device,
  the contents of table 1099, both chains, and the `tailscale0` address.
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

# Inspect the rules TailSocks owns
su -c ip rule list | grep 1099
su -c ip route show table 1099
su -c iptables -t mangle -S TAILSOCKS_MARK
su -c iptables -t nat -S TAILSOCKS_DNS
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
