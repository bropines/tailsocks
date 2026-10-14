---
title: Поиск и ссылки на настройки
description: Найти любую настройку по названию и открыть её ссылкой tailsocks://.
---

## Поиск

**Поиск по настройкам** вверху раздела «Настройки» находит каждую страницу и каждую строку:

- по-русски и по-английски, на каком бы языке ни было приложение;
- с опечатками, в неверной раскладке и транслитом;
- по тому, что настройка делает, а не только по названию.

Пока вы ничего не ввели, он предлагает **Часто нужное** и **Недавно найденное**. Результат открывает страницу и подсвечивает нужную строку. Если строки на странице сейчас нет, результат скажет почему — например, *Появляется в режиме VPN (TUN)* — и откроет ту строку, которая её включает.

На телевизоре поиска нет.

## Ссылки

Ссылка `tailsocks://settings` открывает настройки — и только: ничего не меняет.

| Ссылка | Что откроет |
|---|---|
| `tailsocks://settings` | настройки |
| `tailsocks://settings/<section>` | страницу |
| `tailsocks://settings/<section>/<row>` | страницу с подсвеченной строкой |
| `tailsocks://settings?q=<слова>` | настройки с этими словами в поиске (в URL-кодировке) |

Например, `tailsocks://settings/bypass/control_port` откроет **Только порт 443**, а `tailsocks://settings?q=%D0%B2%D1%8B%D1%85%D0%BE%D0%B4%D0%BD%D0%BE%D0%B9` найдёт «выходной».

Открыть их можно из заметки, QR-кодом через сканер приложения или из adb:

```sh
adb shell am start -a android.intent.action.VIEW -d 'tailsocks://settings/tunnel/tun_engine'
```

Где строка живёт, решает устройство: на телевизоре Root-режим и тема лежат в «Дополнительно», и ссылка всё равно их найдёт.

### Разделы и строки

Строки — это id из каталога настроек приложения ([`SettingsCatalog.kt`](https://github.com/bropines/tailsocks/blob/main/app/src/main/java/io/github/bropines/tailscaled/ui/SettingsCatalog.kt)).

| Раздел | Строки |
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
| `theme`, `root` | страницы темы и Root-режима, где бы устройство их ни держало |
