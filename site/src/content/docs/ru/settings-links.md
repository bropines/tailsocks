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

## Все настройки

Каждая страница и каждая строка — так, как их называет приложение. Пометка под строкой говорит, когда её бывает не видно; тогда ссылка откроет строку, которая её включает.

<!-- settings-table:start -->

### Оформление и язык

`tailsocks://settings/appearance`

| Настройка | Ссылка |
|---|---|
| Тема оформления<br><small>На телевизоре: Дополнительно → Тема и иконка</small> | `tailsocks://settings/appearance/theme_mode` |
| Язык приложения | `tailsocks://settings/appearance/language` |
| Масштаб интерфейса | `tailsocks://settings/appearance/ui_scale` |
| Интерфейс | `tailsocks://settings/appearance/interface` |
| Цветовая палитра<br><small>Доступна, пока динамические цвета выключены · На телевизоре: Дополнительно → Тема и иконка</small> | `tailsocks://settings/appearance/palette` |
| Иконка приложения<br><small>На телевизоре: Дополнительно → Тема и иконка</small> | `tailsocks://settings/appearance/app_icon` |
| QR-коды | `tailsocks://settings/appearance/qr_style` |
| Динамические цвета<br><small>Android 12 и новее · На телевизоре: Дополнительно → Тема и иконка</small> | `tailsocks://settings/appearance/dynamic_color` |
| AMOLED черный<br><small>На телевизоре: Дополнительно → Тема и иконка</small> | `tailsocks://settings/appearance/amoled` |
| Показывать «Что нового» после обновления | `tailsocks://settings/appearance/show_changelog` |
| Сводка подключения | `tailsocks://settings/appearance/connection_summary` |
| Управляемые устройства на главном | `tailsocks://settings/appearance/managed_on_main` |
| Проверять обновления при запуске<br><small>Нет, если приложение поставлено из F-Droid или другого магазина</small> | `tailsocks://settings/appearance/update_check` |
| Получать бета-версии<br><small>Нет, если приложение поставлено из F-Droid или другого магазина</small> | `tailsocks://settings/appearance/update_beta` |

### Аккаунт и подключение

`tailsocks://settings/account`

| Настройка | Ссылка |
|---|---|
| Сервер авторизации (Headscale) | `tailsocks://settings/account/login_server` |
| Ключ авторизации | `tailsocks://settings/account/auth_key` |
| Имя устройства | `tailsocks://settings/account/device_name` |
| Выходной узел (Exit Node) | `tailsocks://settings/account/exit_node` |
| Доступ к локальной сети | `tailsocks://settings/account/exit_node_lan` |
| Принимать маршруты подсетей | `tailsocks://settings/account/accept_routes` |
| Быть выходным узлом | `tailsocks://settings/account/advertise_exit_node` |
| Сообщать настоящую ОС | `tailsocks://settings/account/honest_hostinfo` |
| Блокировать входящие подключения | `tailsocks://settings/account/shields_up` |
| Сообщать о состоянии устройства | `tailsocks://settings/account/posture` |
| Tailnet Lock | `tailsocks://settings/account/tailnet_lock` |
| Анонсируемые маршруты | `tailsocks://settings/account/advertise_routes` |
| Анонсируемые теги | `tailsocks://settings/account/advertise_tags` |
| Быть ретранслятором (peer relay) | `tailsocks://settings/account/relay` |
| UDP-порт ретранслятора<br><small>Появляется, когда включён ретранслятор</small> | `tailsocks://settings/account/relay_port` |
| Коннектор приложений (app connector) | `tailsocks://settings/account/app_connector` |
| Tailscale SSH<br><small>Появляется в режиме Root</small> | `tailsocks://settings/account/ssh` |
| Выйти и сбросить узел | `tailsocks://settings/account/logout` |

### Режим туннеля

`tailsocks://settings/tunnel`

| Настройка | Ссылка |
|---|---|
| Прокси | `tailsocks://settings/tunnel/mode_proxy` |
| VPN (TUN) | `tailsocks://settings/tunnel/mode_vpn` |
| Root<br><small>На телевизоре: Дополнительно → Root-режим</small> | `tailsocks://settings/tunnel/mode_root` |
| Движок TUN<br><small>Появляется в режиме VPN (TUN)</small> | `tailsocks://settings/tunnel/tun_engine` |
| Отдаёт трафик в локальный SOCKS5<br><small>Появляется с движком hev-socks5-tunnel</small> | `tailsocks://settings/tunnel/tun_uses_socks` |
| Пересобирать туннель на ходу<br><small>Появляется с нативным движком TUN</small> | `tailsocks://settings/tunnel/tun_swap_in_place` |
| Весь IPv6 через туннель<br><small>Появляется в режиме VPN (TUN)</small> | `tailsocks://settings/tunnel/tun_ipv6` |
| Прозрачный режим<br><small>Появляется в режиме VPN (TUN)</small> | `tailsocks://settings/tunnel/tun_transparent` |
| Адрес TUN-интерфейса<br><small>Появляется с движком hev-socks5-tunnel</small> | `tailsocks://settings/tunnel/tun_address` |
| Исключенные подсети IP<br><small>Появляется в режиме VPN (TUN) и в режиме Root с нативным TUN</small> | `tailsocks://settings/tunnel/tun_excluded_cidrs` |
| Исключенные приложения<br><small>Появляется в режимах VPN (TUN) и Root</small> | `tailsocks://settings/tunnel/tun_excluded_apps` |
| Нативный Linux TUN (tailscale0)<br><small>Появляется в режиме Root · На телевизоре: Дополнительно → Root-режим</small> | `tailsocks://settings/tunnel/root_tun` |
| Автозапуск Magisk/KernelSU службы (ЭКСПЕРИМЕНТАЛЬНО)<br><small>Появляется в режиме Root · На телевизоре: Дополнительно → Root-режим</small> | `tailsocks://settings/tunnel/root_service` |
| Установить консольную утилиту Tailscale CLI (ЭКСПЕРИМЕНТАЛЬНО)<br><small>Появляется в режиме Root · На телевизоре: Дополнительно → Root-режим</small> | `tailsocks://settings/tunnel/root_cli` |
| Игнорировать чужие VPN<br><small>Появляется в режиме Root · На телевизоре: Дополнительно → Root-режим</small> | `tailsocks://settings/tunnel/root_vpn_bypass` |
| Всё равно занять устройство<br><small>Появляется в режиме Root · На телевизоре: Дополнительно → Root-режим</small> | `tailsocks://settings/tunnel/root_take_device` |
| Завершать Root-демон при остановке<br><small>Появляется в режиме Root · На телевизоре: Дополнительно → Root-режим</small> | `tailsocks://settings/tunnel/root_kill_daemon` |

### Локальные прокси

`tailsocks://settings/proxies`

| Настройка | Ссылка |
|---|---|
| Адрес SOCKS5 | `tailsocks://settings/proxies/socks5_address` |
| Имя пользователя SOCKS5 | `tailsocks://settings/proxies/socks5_username` |
| Пароль SOCKS5 | `tailsocks://settings/proxies/socks5_password` |
| Копировать ссылку SagerNet<br><small>Нет на телевизоре</small> | `tailsocks://settings/proxies/sagernet_link` |
| Включить HTTP-прокси | `tailsocks://settings/proxies/http_enable` |
| Адрес HTTP-прокси<br><small>Появляется, когда включён HTTP-прокси</small> | `tailsocks://settings/proxies/http_address` |
| Открыть прокси в локальную сеть | `tailsocks://settings/proxies/lan_access` |
| SOCKS5 из tailnet | `tailsocks://settings/proxies/tailnet_socks5` |
| HTTP-прокси из tailnet | `tailsocks://settings/proxies/tailnet_http_proxy` |
| DNS-прокси из tailnet | `tailsocks://settings/proxies/tailnet_dns_proxy` |
| Прокси Taildrive из tailnet | `tailsocks://settings/proxies/tailnet_drive_proxy` |
| Строго: закрыть из tailnet и всё остальное | `tailsocks://settings/proxies/tailnet_strict` |
| Оставить открытыми порты<br><small>Появляется, когда включён строгий режим</small> | `tailsocks://settings/proxies/tailnet_extra_ports` |

### DNS

`tailsocks://settings/dns`

| Настройка | Ссылка |
|---|---|
| Использовать MagicDNS | `tailsocks://settings/dns/magicdns` |
| Адрес DNS-прокси | `tailsocks://settings/dns/dns_proxy_address` |
| Системный DNS через MagicDNS<br><small>Появляется в режиме Root с нативным TUN</small> | `tailsocks://settings/dns/root_dns_redirect` |
| Резервные DNS | `tailsocks://settings/dns/dns_fallbacks` |
| DoH URL резервного DNS | `tailsocks://settings/dns/doh_fallback` |
| DNS<br><small>На телевизоре: Дополнительно</small> | `tailsocks://settings/dns/dns_screen` |

### Обход блокировок

`tailsocks://settings/bypass`

| Настройка | Ссылка |
|---|---|
| Прокси Control Plane | `tailsocks://settings/bypass/control_proxy` |
| Включить обход ByeByeDPI | `tailsocks://settings/bypass/byedpi` |
| Флаги ByeByeDPI | `tailsocks://settings/bypass/byedpi_flags` |
| Принудительный IPv4 (Отключить IPv6) | `tailsocks://settings/bypass/byedpi_ipv6` |
| Только порт 443 | `tailsocks://settings/bypass/control_port` |
| Запускаться по сохранённой карте сети | `tailsocks://settings/bypass/netmap_cache` |
| Очистить кэш карты сети | `tailsocks://settings/bypass/netmap_cache_clear` |

### Общий доступ

`tailsocks://settings/sharing`

| Настройка | Ссылка |
|---|---|
| Папка для Taildrop | `tailsocks://settings/sharing/taildrop_folder` |
| Загрузки Taildrive | `tailsocks://settings/sharing/taildrive_download_folder` |
| Параллельные загрузки | `tailsocks://settings/sharing/taildrive_parallel` |
| Перемотка в других приложениях | `tailsocks://settings/sharing/taildrive_seek` |
| Общие папки Taildrive | `tailsocks://settings/sharing/taildrive` |
| Serve & Funnel<br><small>На телевизоре: Дополнительно</small> | `tailsocks://settings/sharing/serve` |
| Включить Веб-интерфейс | `tailsocks://settings/sharing/web_ui` |
| Адрес Веб-интерфейса<br><small>Появляется, когда включён веб-интерфейс</small> | `tailsocks://settings/sharing/web_ui_address` |

### Работа в фоне и разрешения

`tailsocks://settings/background`

| Настройка | Ссылка |
|---|---|
| Разрешения | `tailsocks://settings/background/permissions` |
| Автозапуск при загрузке | `tailsocks://settings/background/autostart` |
| Автопереподключение | `tailsocks://settings/background/auto_reconnect` |
| Попыток перезапуска (0 — без лимита)<br><small>Появляется, когда включено автопереподключение</small> | `tailsocks://settings/background/auto_reconnect_attempts` |
| Восстанавливать службу в фоне | `tailsocks://settings/background/watchdog` |
| Держать соединение активным | `tailsocks://settings/background/keep_awake` |

### Резервные копии

`tailsocks://settings/backup`

На телевизоре эта страница — в разделе «Дополнительно».

| Настройка | Ссылка |
|---|---|
| Полный бэкап (зашифрованный) | `tailsocks://settings/backup/backup_full` |
| Восстановить из полного бэкапа | `tailsocks://settings/backup/restore_full` |
| Экспортировать профиль | `tailsocks://settings/backup/profile_export` |
| Импортировать профиль | `tailsocks://settings/backup/profile_import` |

### Автоматизация и API

`tailsocks://settings/automation`

На телевизоре эта страница — в разделе «Дополнительно».

| Настройка | Ссылка |
|---|---|
| Разрешить внешнюю автоматизацию | `tailsocks://settings/automation/automation_enable` |
| Секретный токен (обязателен)<br><small>Появляется, когда разрешена внешняя автоматизация</small> | `tailsocks://settings/automation/automation_token` |
| Админ-панель<br><small>Нет на телевизоре</small> | `tailsocks://settings/automation/admin_console` |

### Диагностика и разработчику

`tailsocks://settings/diagnostics`

На телевизоре эта страница — в разделе «Дополнительно».

| Настройка | Ссылка |
|---|---|
| Состояние сейчас | `tailsocks://settings/diagnostics/live_state` |
| Подробные логи | `tailsocks://settings/diagnostics/detailed_logs` |
| Дополнительные аргументы | `tailsocks://settings/diagnostics/extra_args` |
| Служебная информация о демоне (ЭКСПЕРИМЕНТАЛЬНО)<br><small>Появляется в режиме Root</small> | `tailsocks://settings/diagnostics/root_daemon_info` |
| Логи<br><small>На телевизоре: Дополнительно</small> | `tailsocks://settings/diagnostics/logs` |
| Консоль<br><small>На телевизоре: Дополнительно</small> | `tailsocks://settings/diagnostics/console` |
| Проверка сети | `tailsocks://settings/diagnostics/netcheck` |
| Показать приветственный экран | `tailsocks://settings/diagnostics/show_onboarding` |

### Дополнительно

`tailsocks://settings/advanced`

| Настройка | Ссылка |
|---|---|
| Что открыто на этом устройстве | `tailsocks://settings/advanced/exposure` |
| Удалённое управление | `tailsocks://settings/advanced/remote_management` |

### Тема и иконка

`tailsocks://settings/theme`

Отдельная страница есть только на телевизоре, в разделе «Дополнительно». На других устройствах её строки — на странице «Оформление и язык», и ссылка откроет её.

### Root-режим

`tailsocks://settings/root`

Отдельная страница есть только на телевизоре, в разделе «Дополнительно». На других устройствах её строки — на странице «Режим туннеля», и ссылка откроет её.

<!-- settings-table:end -->
