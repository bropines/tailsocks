---
title: Автоматизация
description: Управление TailSocks из Tasker, MacroDroid, adb или ассистента.
---

## Настройка

Настройки → **Автоматизация и API** → **Tasker и автоматизация**:

1. Включите **Разрешить внешнюю автоматизацию**.
2. **Секретный токен (обязателен)** — нажмите **Сгенерировать**, потом **Копировать**.

Каждый broadcast должен нести токен в строковом extra `secret` (подойдут и `token`, `key`). Пока токен не задан, отклоняется всё — отключить эту проверку нельзя.

## Broadcast-команды

Отправляйте в broadcast receiver `io.github.bropines.tailscaled/.core.TaskerReceiver`. Действие — `io.github.bropines.tailscaled.action.<ИМЯ>`; короткая форма `io.github.bropines.tailscaled.<ИМЯ>` тоже работает, а `START` / `STOP` означают `CONNECT` / `DISCONNECT`.

| Действие | Extras | Что делает |
|---|---|---|
| `CONNECT` | — | запускает и подключает |
| `DISCONNECT` | — | останавливает; окончательно, как ручная остановка |
| `TOGGLE` | — | запускает или останавливает |
| `RESTART` | — | перезапускает демона |
| `GET_STATUS` | — | обновляет виджеты и плитку |
| `SET_EXIT_NODE` | `exit_node`: IP, `best` или `none` | задаёт или сбрасывает выходной узел |
| `SWITCH_ACCOUNT` | `account`: имя или ID | переключает аккаунт |
| `SET_BYEDPI` | `enabled` (boolean), `flags` (string) | обход DPI |
| `SET_TUN` | `enabled` (boolean) | режим VPN (TUN) |

`best` берёт выходной узел, который Tailscale рекомендует прямо сейчас. Broadcast статуса не выходит за пределы приложения: так состояние другим приложениям не прочитать — для этого есть `getStatus` ниже.

### adb

```sh
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT \
  -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE \
  -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node 100.64.0.1
```

Boolean-extras передаются через `--ez`: `--ez enabled true`.

### Tasker

**Система → Отправить Intent**: Action `io.github.bropines.tailscaled.action.CONNECT`, пакет `io.github.bropines.tailscaled`, класс `io.github.bropines.tailscaled.core.TaskerReceiver`, Extra `secret:YOUR_TOKEN`, цель **Broadcast Receiver**. В MacroDroid — те же поля.

## Ассистенты (Android 16+)

На Android 16 и новее TailSocks предлагает ассистентам на устройстве, например Gemini, 14 AppFunctions:

- Чтение, доступно всегда: `getStatus`, `getAvailableExitNodes`, `getTailnetPeers`, `getAccounts`.
- Изменения, только при включённой **Разрешить внешнюю автоматизацию**: `connect`, `disconnect`, `toggle`, `selectExitNode`, `clearExitNode`, `switchAccount`, `setByeDpi`, `setTunMode`, `setAllowLanAccess`, `setMagicDns`.

Токен им не нужен — кто может их вызывать, решает система.

## Ссылки на экраны

Ссылки `tailsocks://` открывают экран и ничего не меняют, поэтому токен им не нужен:

| Ссылка | Что откроет |
|---|---|
| `tailsocks://settings/…` | Настройки — см. [Поиск и ссылки на настройки](/tailsocks/ru/settings-links/) |
| `tailsocks://exitnode` | выбор выходного узла |
| `tailsocks://scan` | QR-сканер |
| `tailsocks://logs?category=ERROR` | логи одной категории |
| `tailsocks://peers`, `dns`, `netcheck`, `console`, `files`, `taildrive`, `serve`, `permissions`, `licenses` | этот экран |

```sh
adb shell am start -a android.intent.action.VIEW -d 'tailsocks://netcheck'
```

Эти ссылки открывает и QR-сканер самого приложения. Полный справочник — [AUTOMATION_RU.md](https://github.com/bropines/tailsocks/blob/main/docs/AUTOMATION_RU.md).
