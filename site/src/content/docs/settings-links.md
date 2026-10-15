---
title: Settings search and links
description: Find any setting by name, and open it with a tailsocks:// link.
---

## Search

**Search settings**, at the top of Settings, finds every page and every row:

- in English or in Russian, whatever the app's language;
- with typos, in the wrong keyboard layout, or transliterated;
- by what a setting does, not only by its name.

Before you type it offers **Often needed** and **Recently found**. A result opens its page with the row in view and marked. If the row is not on its page right now, the result says why — for example *Shown in VPN (TUN) mode* — and opens the row that brings it.

Search is not on a TV.

## Links

A `tailsocks://settings` link opens Settings, and nothing else: it changes no setting.

| Link | Opens |
|---|---|
| `tailsocks://settings` | Settings |
| `tailsocks://settings/<section>` | a page |
| `tailsocks://settings/<section>/<row>` | a page, with one row in view and marked |
| `tailsocks://settings?q=<words>` | Settings with `<words>` typed into the search (URL-encoded) |

For example `tailsocks://settings/bypass/control_port` opens **Port 443 only**, and `tailsocks://settings?q=exit%20node` searches for "exit node".

Open them from a note, a QR code read by the app's scanner, or adb:

```sh
adb shell am start -a android.intent.action.VIEW -d 'tailsocks://settings/tunnel/tun_engine'
```

The device decides where a row lives: on a TV, Root mode and the theme are under Advanced, and a link still finds them.

## Every setting

Every page and every row, as the app names them. A note under a row says when it is not always there; its link then opens the row that brings it.

<!-- settings-table:start -->

### Appearance & language

`tailsocks://settings/appearance`

| Setting | Link |
|---|---|
| Interface Theme<br><small>On a TV: under Advanced → Theme and icon</small> | `tailsocks://settings/appearance/theme_mode` |
| App Language | `tailsocks://settings/appearance/language` |
| Interface scale | `tailsocks://settings/appearance/ui_scale` |
| Interface | `tailsocks://settings/appearance/interface` |
| Color Palette<br><small>Shown while dynamic colours are off · On a TV: under Advanced → Theme and icon</small> | `tailsocks://settings/appearance/palette` |
| App icon<br><small>On a TV: under Advanced → Theme and icon</small> | `tailsocks://settings/appearance/app_icon` |
| QR codes | `tailsocks://settings/appearance/qr_style` |
| Dynamic Colors<br><small>Android 12 and newer · On a TV: under Advanced → Theme and icon</small> | `tailsocks://settings/appearance/dynamic_color` |
| AMOLED Black<br><small>On a TV: under Advanced → Theme and icon</small> | `tailsocks://settings/appearance/amoled` |
| Show what's new after updates | `tailsocks://settings/appearance/show_changelog` |
| Connection summary | `tailsocks://settings/appearance/connection_summary` |
| Managed devices on the main screen | `tailsocks://settings/appearance/managed_on_main` |
| Check for updates on launch<br><small>Not when F-Droid or another store installed the app</small> | `tailsocks://settings/appearance/update_check` |
| Receive beta versions<br><small>Not when F-Droid or another store installed the app</small> | `tailsocks://settings/appearance/update_beta` |

### Account & connection

`tailsocks://settings/account`

| Setting | Link |
|---|---|
| Login Server (Headscale) | `tailsocks://settings/account/login_server` |
| Auth Key | `tailsocks://settings/account/auth_key` |
| Device name | `tailsocks://settings/account/device_name` |
| Exit Node | `tailsocks://settings/account/exit_node` |
| Allow LAN access | `tailsocks://settings/account/exit_node_lan` |
| Accept subnet routes | `tailsocks://settings/account/accept_routes` |
| Run as exit node | `tailsocks://settings/account/advertise_exit_node` |
| Report the real OS | `tailsocks://settings/account/honest_hostinfo` |
| Block incoming connections | `tailsocks://settings/account/shields_up` |
| Report device posture | `tailsocks://settings/account/posture` |
| Tailnet Lock | `tailsocks://settings/account/tailnet_lock` |
| Advertise Routes | `tailsocks://settings/account/advertise_routes` |
| Advertise Tags | `tailsocks://settings/account/advertise_tags` |
| Act as a peer relay | `tailsocks://settings/account/relay` |
| Relay UDP port<br><small>Shown once the peer relay is on</small> | `tailsocks://settings/account/relay_port` |
| App connector | `tailsocks://settings/account/app_connector` |
| Log Out & Reset Node | `tailsocks://settings/account/logout` |

### Tunnel mode

`tailsocks://settings/tunnel`

| Setting | Link |
|---|---|
| Proxy | `tailsocks://settings/tunnel/mode_proxy` |
| VPN (TUN) | `tailsocks://settings/tunnel/mode_vpn` |
| Root<br><small>On a TV: under Advanced → Root mode</small> | `tailsocks://settings/tunnel/mode_root` |
| TUN engine<br><small>Shown in VPN (TUN) mode</small> | `tailsocks://settings/tunnel/tun_engine` |
| Forwards into the local SOCKS5 proxy<br><small>Shown with the hev-socks5-tunnel engine</small> | `tailsocks://settings/tunnel/tun_uses_socks` |
| Route all IPv6 through the tunnel<br><small>Shown in VPN (TUN) mode</small> | `tailsocks://settings/tunnel/tun_ipv6` |
| TUN Address<br><small>Shown with the hev-socks5-tunnel engine</small> | `tailsocks://settings/tunnel/tun_address` |
| Excluded IP Ranges<br><small>Shown in VPN (TUN) mode</small> | `tailsocks://settings/tunnel/tun_excluded_cidrs` |
| Excluded Apps<br><small>Shown in VPN (TUN) and Root modes</small> | `tailsocks://settings/tunnel/tun_excluded_apps` |
| Native Linux TUN (tailscale0)<br><small>Shown in Root mode · On a TV: under Advanced → Root mode</small> | `tailsocks://settings/tunnel/root_tun` |
| Autostart Magisk/KernelSU Service (EXPERIMENTAL)<br><small>Shown in Root mode · On a TV: under Advanced → Root mode</small> | `tailsocks://settings/tunnel/root_service` |
| Install Tailscale CLI Utility (EXPERIMENTAL)<br><small>Shown in Root mode · On a TV: under Advanced → Root mode</small> | `tailsocks://settings/tunnel/root_cli` |
| Ignore other VPNs<br><small>Shown in Root mode · On a TV: under Advanced → Root mode</small> | `tailsocks://settings/tunnel/root_vpn_bypass` |
| Take the device anyway<br><small>Shown in Root mode · On a TV: under Advanced → Root mode</small> | `tailsocks://settings/tunnel/root_take_device` |
| Terminate Root Daemon on Stop<br><small>Shown in Root mode · On a TV: under Advanced → Root mode</small> | `tailsocks://settings/tunnel/root_kill_daemon` |

### Local proxies

`tailsocks://settings/proxies`

| Setting | Link |
|---|---|
| SOCKS5 Address | `tailsocks://settings/proxies/socks5_address` |
| SOCKS5 Username | `tailsocks://settings/proxies/socks5_username` |
| SOCKS5 Password | `tailsocks://settings/proxies/socks5_password` |
| Copy SagerNet Link<br><small>Not on a TV</small> | `tailsocks://settings/proxies/sagernet_link` |
| Enable HTTP Proxy | `tailsocks://settings/proxies/http_enable` |
| HTTP Proxy Address<br><small>Shown once the HTTP proxy is on</small> | `tailsocks://settings/proxies/http_address` |
| Expose proxies to local network | `tailsocks://settings/proxies/lan_access` |
| SOCKS5 from the tailnet | `tailsocks://settings/proxies/tailnet_socks5` |
| HTTP proxy from the tailnet | `tailsocks://settings/proxies/tailnet_http_proxy` |
| DNS proxy from the tailnet | `tailsocks://settings/proxies/tailnet_dns_proxy` |
| Taildrive proxy from the tailnet | `tailsocks://settings/proxies/tailnet_drive_proxy` |
| Strict: close everything else too | `tailsocks://settings/proxies/tailnet_strict` |
| Also open these ports<br><small>Shown once strict mode is on</small> | `tailsocks://settings/proxies/tailnet_extra_ports` |

### DNS

`tailsocks://settings/dns`

| Setting | Link |
|---|---|
| Use MagicDNS | `tailsocks://settings/dns/magicdns` |
| DNS Proxy Address | `tailsocks://settings/dns/dns_proxy_address` |
| System-wide DNS via MagicDNS<br><small>Shown in Root mode with the native TUN on</small> | `tailsocks://settings/dns/root_dns_redirect` |
| DNS Fallbacks | `tailsocks://settings/dns/dns_fallbacks` |
| DoH Fallback URL | `tailsocks://settings/dns/doh_fallback` |
| DNS<br><small>On a TV: under Advanced</small> | `tailsocks://settings/dns/dns_screen` |

### Censorship bypass

`tailsocks://settings/bypass`

| Setting | Link |
|---|---|
| Control Plane Proxy | `tailsocks://settings/bypass/control_proxy` |
| Enable ByeByeDPI Bypass | `tailsocks://settings/bypass/byedpi` |
| ByeByeDPI Flags | `tailsocks://settings/bypass/byedpi_flags` |
| Force IPv4 (Disable IPv6) | `tailsocks://settings/bypass/byedpi_ipv6` |
| Port 443 only | `tailsocks://settings/bypass/control_port` |
| Start from the saved network map | `tailsocks://settings/bypass/netmap_cache` |
| Clear the network map cache | `tailsocks://settings/bypass/netmap_cache_clear` |

### Sharing & access

`tailsocks://settings/sharing`

| Setting | Link |
|---|---|
| Taildrop Storage Folder | `tailsocks://settings/sharing/taildrop_folder` |
| Taildrive downloads | `tailsocks://settings/sharing/taildrive_download_folder` |
| Taildrive Shares | `tailsocks://settings/sharing/taildrive` |
| Serve & Funnel<br><small>On a TV: under Advanced</small> | `tailsocks://settings/sharing/serve` |
| Enable Web UI | `tailsocks://settings/sharing/web_ui` |
| Web UI Address<br><small>Shown once the web interface is on</small> | `tailsocks://settings/sharing/web_ui_address` |

### Background & permissions

`tailsocks://settings/background`

| Setting | Link |
|---|---|
| Permissions | `tailsocks://settings/background/permissions` |
| Auto-start on Boot | `tailsocks://settings/background/autostart` |
| Auto-reconnect | `tailsocks://settings/background/auto_reconnect` |
| Restart attempts (0 = unlimited)<br><small>Shown once auto-reconnect is on</small> | `tailsocks://settings/background/auto_reconnect_attempts` |
| Revive service in background | `tailsocks://settings/background/watchdog` |
| Keep the connection awake | `tailsocks://settings/background/keep_awake` |

### Backup & restore

`tailsocks://settings/backup`

On a TV this page is under Advanced.

| Setting | Link |
|---|---|
| Full backup (encrypted) | `tailsocks://settings/backup/backup_full` |
| Restore full backup | `tailsocks://settings/backup/restore_full` |
| Export this profile | `tailsocks://settings/backup/profile_export` |
| Import a profile | `tailsocks://settings/backup/profile_import` |

### Automation & API

`tailsocks://settings/automation`

On a TV this page is under Advanced.

| Setting | Link |
|---|---|
| Allow External Automation | `tailsocks://settings/automation/automation_enable` |
| Security Secret Token (required)<br><small>Shown once external automation is allowed</small> | `tailsocks://settings/automation/automation_token` |
| Admin Console<br><small>Not on a TV</small> | `tailsocks://settings/automation/admin_console` |

### Diagnostics & developer

`tailsocks://settings/diagnostics`

On a TV this page is under Advanced.

| Setting | Link |
|---|---|
| Live state | `tailsocks://settings/diagnostics/live_state` |
| Detailed Logs | `tailsocks://settings/diagnostics/detailed_logs` |
| Extra Arguments | `tailsocks://settings/diagnostics/extra_args` |
| Daemon Runtime Information (EXPERIMENTAL)<br><small>Shown in Root mode</small> | `tailsocks://settings/diagnostics/root_daemon_info` |
| Logs<br><small>On a TV: under Advanced</small> | `tailsocks://settings/diagnostics/logs` |
| Console<br><small>On a TV: under Advanced</small> | `tailsocks://settings/diagnostics/console` |
| Network check | `tailsocks://settings/diagnostics/netcheck` |
| Show Onboarding Screen | `tailsocks://settings/diagnostics/show_onboarding` |

### Advanced

`tailsocks://settings/advanced`

| Setting | Link |
|---|---|
| What this device exposes | `tailsocks://settings/advanced/exposure` |
| Remote management | `tailsocks://settings/advanced/remote_management` |

### Theme and icon

`tailsocks://settings/theme`

A page of its own only on a TV, under Advanced. Elsewhere its rows are on Appearance & language, and this link opens that.

### Root mode

`tailsocks://settings/root`

A page of its own only on a TV, under Advanced. Elsewhere its rows are on Tunnel mode, and this link opens that.

<!-- settings-table:end -->
