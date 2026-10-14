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

### Sections and rows

The rows are the ids of the app's settings catalogue ([`SettingsCatalog.kt`](https://github.com/bropines/tailsocks/blob/main/app/src/main/java/io/github/bropines/tailscaled/ui/SettingsCatalog.kt)).

| Section | Rows |
|---|---|
| `appearance` | `theme_mode`, `language`, `ui_scale`, `interface`, `palette`, `app_icon`, `qr_style`, `dynamic_color`, `amoled`, `show_changelog`, `connection_summary`, `managed_on_main`, `update_check`, `update_beta` |
| `account` | `login_server`, `auth_key`, `device_name`, `exit_node`, `exit_node_lan`, `accept_routes`, `advertise_exit_node`, `honest_hostinfo`, `shields_up`, `posture`, `tailnet_lock`, `advertise_routes`, `advertise_tags`, `relay`, `relay_port`, `app_connector`, `logout` |
| `tunnel` | `mode_proxy`, `mode_vpn`, `mode_root`, `tun_engine`, `tun_uses_socks`, `tun_ipv6`, `tun_address`, `tun_excluded_cidrs`, `tun_excluded_apps`, `root_tun`, `root_service`, `root_cli`, `root_vpn_bypass`, `root_take_device`, `root_kill_daemon` |
| `proxies` | `socks5_address`, `socks5_username`, `socks5_password`, `sagernet_link`, `http_enable`, `http_address`, `lan_access` |
| `dns` | `magicdns`, `dns_proxy_address`, `root_dns_redirect`, `dns_fallbacks`, `doh_fallback`, `dns_screen` |
| `bypass` | `control_proxy`, `byedpi`, `byedpi_flags`, `byedpi_ipv6`, `control_port`, `netmap_cache`, `netmap_cache_clear` |
| `sharing` | `taildrop_folder`, `taildrive`, `serve`, `web_ui`, `web_ui_address` |
| `background` | `permissions`, `autostart`, `auto_reconnect`, `auto_reconnect_attempts`, `watchdog`, `keep_awake` |
| `backup` | `backup_full`, `restore_full`, `profile_export`, `profile_import` |
| `automation` | `automation_enable`, `automation_token`, `admin_console` |
| `diagnostics` | `live_state`, `detailed_logs`, `extra_args`, `root_daemon_info`, `logs`, `console`, `netcheck`, `show_onboarding` |
| `advanced` | `remote_management` |
| `theme`, `root` | the theme and Root mode pages, wherever the device keeps them |
