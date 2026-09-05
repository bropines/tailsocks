# ⚡ Руководство по Интеграции с Tasker и Автосценариями

TailSocks поддерживает полнофункциональное фоновое управление через **Android Broadcast Intents**. Это позволяет полностью контролировать подключения, профили, Exit Nodes, прокси ByeDPI и режим TUN из приложений автоматизации, таких как **Tasker**, **MacroDroid**, **Automate** или через консоль **ADB**.

На Android 16+ те же операции доступны on-device ассистентам через **AppFunctions** (см. [Gemini / AppFunctions](#-gemini--appfunctions-android-16) ниже).

---

## 🔒 Безопасность и Защита Токеном

Автоматизация настраивается в приложении: **Настройки → вкладка APP → Tasker & Automation**:

* **Разрешить внешнюю автоматизацию:** Главный переключатель. Когда он выключен, все broadcast-сообщения игнорируются, а все изменяющие AppFunctions возвращают ошибку.
* **Секретный токен (обязателен с версии 4.0.0):** Receiver экспортирован без permission, поэтому без токена любое установленное приложение могло остановить VPN или перенаправить трафик. С 4.0.0 receiver **отклоняет все intent-запросы, пока токен не задан**. Кнопка **Сгенерировать** создаёт токен, **Копировать** переносит его в приложение автоматизации.

Каждый intent должен содержать токен в одном из строковых extra (проверяются в этом порядке): **`secret`**, **`token`** или **`key`**. Сравнение выполняется за константное время; отсутствующее или неверное значение записывается в лог и отбрасывается. Токен нужен и для read-only действий, например `GET_STATUS`.

> Если токен пуст, в logcat появится `Automation intent rejected: no secret token configured`, и ничего не произойдёт. Задайте токен — отключить проверку нельзя.

---

## 🎯 Поддерживаемые Intent Команды

### Параметры Целевого Receiver
* **Имя пакета (Package):** `io.github.bropines.tailscaled`
* **Класс компонента (Component):** `io.github.bropines.tailscaled.core.TaskerReceiver` *(опционально; рекомендуется на Android 8+, где неявные broadcast не доставляются manifest-receiver'ам)*
* **Тип назначения:** Broadcast Receiver
* **Всегда добавляйте:** строковый extra `secret` (или `token` / `key`) с вашим токеном.

### Управляющие Действия (Actions)

| Action (Действие) | Короткий псевдоним | Extras / Параметры | Описание |
|-------------------|--------------------|--------------------|----------|
| `io.github.bropines.tailscaled.action.CONNECT` | `io.github.bropines.tailscaled.START` | — | Запускает фоновый демон и подключает TailSocks. |
| `io.github.bropines.tailscaled.action.DISCONNECT` | `io.github.bropines.tailscaled.STOP` | — | Отключает сеть и останавливает демон. Эквивалент ручного **Stop**: после него ничто не оживляет службу (см. [Поведение в фоне](#-поведение-в-фоне)). |
| `io.github.bropines.tailscaled.action.TOGGLE` | `io.github.bropines.tailscaled.TOGGLE` | — | Запускает, если остановлено; останавливает, если работает. |
| `io.github.bropines.tailscaled.action.RESTART` | `io.github.bropines.tailscaled.RESTART` | — | Перезапускает демон на месте. |
| `io.github.bropines.tailscaled.action.GET_STATUS` | `io.github.bropines.tailscaled.GET_STATUS` | — | Обновляет состояние виджетов / плитки. Итоговый broadcast `STATUS_CHANGED` не виден другим приложениям (см. ниже). |
| `io.github.bropines.tailscaled.action.SET_EXIT_NODE` | `io.github.bropines.tailscaled.SET_EXIT_NODE` | `exit_node` (String; псевдоним `exit_node_ip`). Tailscale IP узла или `none` / `disabled` / `off` для сброса. | Устанавливает Exit Node активного профиля и применяет его «на лету», если демон запущен. |
| `io.github.bropines.tailscaled.action.SWITCH_ACCOUNT` | `io.github.bropines.tailscaled.SWITCH_ACCOUNT` | `account` (String; псевдонимы `account_id`, `account_name`). ID или имя профиля без учёта регистра. | Переключает активный профиль; демон перезапускается, если работал. Неизвестное имя пишется в лог и игнорируется. |
| `io.github.bropines.tailscaled.action.SET_BYEDPI` | `io.github.bropines.tailscaled.SET_BYEDPI` | `enabled` (Boolean, опционально), `flags` (String, опционально) | Включает/выключает обход ByeDPI и/или заменяет его флаги, затем повторно применяет настройки. |
| `io.github.bropines.tailscaled.action.SET_TUN` | `io.github.bropines.tailscaled.SET_TUN` | `enabled` (Boolean, обязателен) | Включает или выключает прозрачный TUN-режим и повторно применяет настройки. Без extra `enabled` игнорируется. |

Длинная форма `…action.X` и короткий псевдоним объявлены в манифесте и работают одинаково.

---

## 📡 Автоматические Уведомления о Статусе (События)

При каждом изменении состояния TailSocks или при получении `GET_STATUS` приложение отправляет broadcast **`io.github.bropines.tailscaled.STATUS_CHANGED`** (псевдоним **`io.github.bropines.tailscaled.STATUS`**). Broadcast ограничен собственным пакетом приложения (`setPackage`), поэтому доставляется только виджетам и плитке быстрых настроек самого TailSocks; **сторонние приложения, например Tasker, получить его не могут**. `GET_STATUS`, соответственно, лишь обновляет встроенные виджеты. Чтобы прочитать состояние из другого приложения, используйте AppFunction `getStatus()` на Android 16+ (см. ниже). Для справки, extras внутреннего broadcast:

| Ключ Extra | Тип | Описание | Пример |
|------------|-----|----------|--------|
| `running` | Boolean | `true`, если демон запущен | `true` |
| `status` | String | Текст статуса | `"ACTIVE"`, `"STOPPED"`, `"STARTING"` |
| `account` | String | Имя активного профиля | `"Personal"` |
| `account_id` | String | ID активного профиля | `"acc_123456"` |
| `exit_node` | String | IP активного Exit Node | `"100.64.0.1"` |
| `tun_enabled` | Boolean | Состояние режима TUN VPN | `true` |
| `byedpi_enabled`| Boolean | Состояние прокси ByeDPI | `true` |

---

## 📱 Примеры Настройки Автоматизации

### 1. MacroDroid / Tasker
* **Тип отправки:** Broadcast Receiver
* **Action:** `io.github.bropines.tailscaled.action.CONNECT`
* **Package:** `io.github.bropines.tailscaled`
* **Class:** `io.github.bropines.tailscaled.core.TaskerReceiver`
* **Extra 1:** Ключ: `secret`, Значение: `YOUR_TOKEN`

### 2. Примеры Команд ADB

```bash
# Отключение (токен обязателен для каждого действия)
adb shell am broadcast -a io.github.bropines.tailscaled.action.DISCONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Подключение
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Обновить виджеты / плитку (итоговый broadcast STATUS_CHANGED остаётся внутри приложения)
adb shell am broadcast -a io.github.bropines.tailscaled.action.GET_STATUS -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Установка Exit Node / сброс
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node 100.64.0.1
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node none

# Переключение профиля
adb shell am broadcast -a io.github.bropines.tailscaled.action.SWITCH_ACCOUNT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es account Work

# Переключение TUN / ByeDPI (boolean extras передаются через --ez)
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_TUN -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_BYEDPI -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true --es flags "-s 1 -d split -r"
```

---

## 🔄 Поведение в фоне

За поведение соединения, когда приложение не на переднем плане, отвечают три настройки — все в **Настройки → APP → Система и Резервное копирование**, если не указано иное:

* **Автопереподключение** (по умолчанию выключено): перезапускает демон, если соединение не поднимается или обрывается. Перед перезапуском требуется три подряд неудачные проверки, ожидание входа пользователя сбоем не считается, а **число попыток** ограничивает перезапуски (0 = без ограничений, по умолчанию 3).
* **Восстанавливать службу в фоне** (по умолчанию включено): неточный будильник раз в 15 минут проверяет, что служба жива, пока пользователь хочет соединение, и запускает её снова после убийства в фоне (OEM task killers). На прошивках, запрещающих фоновый запуск, отказ пишется в лог — выдайте приложению разрешение на автозапуск.
* **Принудительный фоновый запуск** (Настройки → TS-Core → Флаги и Логи, по умолчанию выключено): держит partial wake lock на всю сессию, чтобы демон продолжал обслуживать keepalive WireGuard/DERP в глубоком сне. Расходует батарею.

Два правила действуют независимо от этих переключателей:

* **Ручная остановка окончательна.** Остановка из приложения, уведомления, плитки быстрых настроек, broadcast `DISCONNECT` или AppFunction `disconnect` сбрасывает флаг «хочу работать» и снимает watchdog до начала любого teardown. Ни sticky-перезапуск, ни 15-минутный watchdog, ни автопереподключение не оживят службу после этого.
* **Смахивание приложения из «Недавних» сохраняет соединение.** Служба сама запрашивает свой перезапуск и заново ставит watchdog, так что удаление задачи не отключает вас.

---

## 🤖 Gemini / AppFunctions (Android 16+)

TailSocks регистрирует в системе **14 AppFunctions** (`android.app.appfunctions`), чтобы on-device ассистент вроде Gemini мог управлять приложением на естественном языке. Требуется **Android 16 (API 36) или новее**; на более старых версиях служба не запускается и ничего не индексируется. Регистрацию можно проверить командой `adb shell dumpsys app_function`.

Функции выполняются вне главного потока. **Все изменяющие функции подчиняются переключателю «Разрешить внешнюю автоматизацию»** — при выключенном переключателе они возвращают `success = false` и сообщение *"External automation is disabled in TailSocks settings."* Read-only функции отвечают всегда. Broadcast-токен для AppFunctions **не** требуется: доступ контролируется системным permission `BIND_APP_FUNCTION_SERVICE`.

| Функция | Тип | Описание |
|---------|-----|----------|
| `getStatus()` | чтение | Состояние соединения, активный аккаунт, IP Exit Node, флаги TUN / ByeDPI / MagicDNS / LAN-доступа. |
| `getAvailableExitNodes()` | чтение | Узлы, объявленные как Exit Node, с online-статусом и отметкой активного. |
| `getTailnetPeers()` | чтение | Все узлы tailnet: имя, IP, ОС, online-статус. |
| `getAccounts()` | чтение | Настроенные профили и активный. |
| `connect(exitNodeIp)` | изменение | Запускает службу, при необходимости предварительно выбрав Exit Node; ждёт до ~6 с и сообщает фактическое состояние. |
| `disconnect()` | изменение | Останавливает службу (окончательно, как ручной Stop). |
| `toggle()` | изменение | `disconnect()`, если работает, иначе `connect("")`. |
| `selectExitNode(exitNodeIp)` | изменение | Устанавливает Exit Node по Tailscale IP (`off` / `none` / пустая строка сбрасывает) и передаёт его работающему демону. |
| `clearExitNode()` | изменение | То же, что `selectExitNode("")`. |
| `switchAccount(accountNameOrId)` | изменение | Переключает активный профиль по имени или ID; перезапускает демон, если он работал. |
| `setByeDpi(enabled, flags)` | изменение | Включает/выключает обход ByeDPI, при необходимости заменяя флаги. |
| `setTunMode(enabled)` | изменение | Переключает прозрачный TUN-режим. |
| `setAllowLanAccess(enabled)` | изменение | Переключает **Доступ из локальной сети** (привязка SOCKS5/HTTP/DNS к `0.0.0.0`). |
| `setMagicDns(enabled)` | изменение | Переключает MagicDNS (`accept_dns`). |
