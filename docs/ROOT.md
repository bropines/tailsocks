# TailSocks Root Integration & System Service Guide

TailSocks supports advanced **Root Mode** for Android devices running root solution (e.g. **Magisk**, **KernelSU**, or **APatch**). Root mode allows the `tailscaled` core daemon to run as a native system daemon independently of the Android UI lifecycle, auto-start on device boot, and provide a full system-wide `tailscale` Command Line Interface (CLI) in terminal environments.

---

## ⚡ Key Architecture & Features

### 1. System Autostart via `service.d` (`tailscaled.sh`)
* **Path:** `/data/adb/service.d/tailscaled.sh`
* **Execution:** Executed automatically by Magisk / KernelSU / APatch during early boot (`late_start` service phase) under `root` (UID 0).
* **State Directory Auto-Resolution:** Automatically detects existing account state directories inside `/data/data/io.github.bropines.tailscaled/files/states/` (defaults to `default` or `root`).
* **Socket & Log Management:** Uses `/data/data/io.github.bropines.tailscaled/files/tailscaled.sock` with native `0666` socket permissions (via TailSocks atomic safesocket patches) and handles log rotation (`tailscaled.log`) automatically when size exceeds 2 MB.
* **Proxy & Control Plane Propagation:** Reads control proxy environment settings from `/data/data/io.github.bropines.tailscaled/files/control_proxy.env` (including `ALL_PROXY`, `HTTP_PROXY`, `HTTPS_PROXY`, and pre-resolved `TS_STATIC_HOSTS` overrides).

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
  Traffic is steered into it by firewall mark `1099`.
* **`TAILSOCKS_MARK`** (mangle) sets that mark on tailnet-bound packets.
* **`TAILSOCKS_DNS`** (nat) redirects system-wide port 53 to MagicDNS
  (`100.100.100.100`). Two classes of traffic are explicitly excluded, in this
  order, before the redirect:
  * the tailnet range `100.64.0.0/10`, so Split DNS resolvers hosted on peers
    are reached directly;
  * the daemon's own upstream resolvers (`TS_DNS_FALLBACK`, mirrored from the
    DNS fallback setting). Without this, the resolver's own queries are
    redirected back into itself and no external name ever resolves.

  The redirect is installed **only** when `accept-dns` is on — redirecting the
  whole device to a resolver that is not answering would break DNS entirely.

Everything lives in named chains, so the rules are idempotent, can be inspected
with `iptables -t nat -S TAILSOCKS_DNS`, and are removed in one shot when the
service stops. **Settings → Root Mode → Check Routing** dumps the live state of
all of the above, and failures are reported in the **ROOT** tab of the Logs
screen instead of being silently discarded.

---

## 📱 Enabling Root Integration in App

1. Open **TailSocks**.
2. Go to **Settings** → **Root Integration**.
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

### Useful Troubleshooting Commands:

```bash
# View live tailscaled logs
su -c tail -f /data/data/io.github.bropines.tailscaled/logs/tailscaled.log

# Check if tailscaled process is running under root
su -c ps -ef | grep tailscaled

# Verify socket permissions
su -c ls -la /data/data/io.github.bropines.tailscaled/files/tailscaled.sock
```
