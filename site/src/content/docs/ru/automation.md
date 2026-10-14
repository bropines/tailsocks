---
title: Автоматизация
description: Управление TailSocks из Tasker, MacroDroid, Automate, adb или ассистента.
---

Другие приложения могут подключать и отключать TailSocks, выбирать выходной узел, переключать аккаунт, включать и выключать обход DPI и режим VPN — через broadcast-команды, которые умеют отправлять Tasker, MacroDroid, Automate и adb. На Android 16 и новее то же самое может ассистент — через AppFunctions.

## Настройка

Настройки → **Автоматизация и API** → **Tasker и автоматизация** (на телевизоре: Настройки → Дополнительно → Автоматизация и API):

1. Включите **Разрешить внешнюю автоматизацию** — главный переключатель. Когда он выключен, все broadcast-команды игнорируются, а все AppFunctions, которые что-то меняют, возвращают ошибку.
2. **Секретный токен (обязателен)** — нажмите **Сгенерировать**, потом **Копировать** и вставьте его в приложение автоматизации.

Каждая broadcast-команда должна нести токен в строковом extra: `secret`, `token` или `key`, проверяются в этом порядке. Без токена или с неверным команда пишется в лог и отбрасывается — в том числе команды только для чтения вроде `GET_STATUS`.

Пока токен не задан, отклоняется всё, а в logcat появляется `Automation intent rejected: no secret token configured`. Отключить проверку нельзя: receiver открыт для любого приложения на устройстве, и без токена любое из них могло бы остановить ваше соединение или перенаправить трафик.

## Broadcast-команды

Каждую отправляйте сюда:

| Поле | Значение |
|---|---|
| Пакет | `io.github.bropines.tailscaled` |
| Класс | `io.github.bropines.tailscaled.core.TaskerReceiver` — необязательно, но лучше указать: Android 8 и новее может не доставить команду, в которой не назван получатель |
| Цель | Broadcast Receiver |
| Extra | `secret`: ваш токен |

Каждое действие — `io.github.bropines.tailscaled.action.<ИМЯ>`. Короткая форма `io.github.bropines.tailscaled.<ИМЯ>` работает так же; для `CONNECT` и `DISCONNECT` это `START` и `STOP`.

| Действие | Extras | Что делает |
|---|---|---|
| `CONNECT` | — | Запускает демона и подключается. |
| `DISCONNECT` | — | Отключается и останавливает демона. Окончательно, как ручная остановка: после неё службу ничто не вернёт. |
| `TOGGLE` | — | Запускает, если остановлено; останавливает, если работает. |
| `RESTART` | — | Перезапускает демона на месте. |
| `GET_STATUS` | — | Обновляет виджеты и плитку быстрых настроек. |
| `SET_EXIT_NODE` | `exit_node` (строка; также `exit_node_ip`): Tailscale IP узла, `best` или `none` / `disabled` / `off` | Задаёт или сбрасывает выходной узел активного профиля, на лету, если демон работает. `best` берёт тот, который Tailscale рекомендует прямо сейчас: нужен работающий демон, а без рекомендации ничего не меняется. |
| `SWITCH_ACCOUNT` | `account` (строка; также `account_id`, `account_name`): ID или имя профиля, регистр не важен | Переключает активный профиль; демон перезапускается, если работал. Неизвестное имя пишется в лог и игнорируется. |
| `SET_BYEDPI` | `enabled` (boolean, необязательно), `flags` (строка, необязательно) | Включает или выключает обход ByeDPI и/или заменяет его флаги, затем применяет настройки. |
| `SET_TUN` | `enabled` (boolean, обязательно) | Включает или выключает режим VPN (TUN) и применяет настройки. Без `enabled` игнорируется. |

### Broadcast статуса

Когда состояние меняется или приходит `GET_STATUS`, TailSocks отправляет `io.github.bropines.tailscaled.STATUS_CHANGED` (также `…STATUS`). Он адресован самому TailSocks — его виджетам и плитке, — поэтому **Tasker и другие приложения его не получат**. Чтобы прочитать состояние из другого приложения, используйте [AppFunction](#ассистенты-android-16) `getStatus`. Для справки, его extras:

| Extra | Тип | Пример |
|---|---|---|
| `running` | boolean — демон работает | `true` |
| `status` | строка | `ACTIVE`, `STOPPED`, `STARTING` |
| `account` | строка — имя активного профиля | `Personal` |
| `account_id` | строка — его ID | `acc_123456` |
| `exit_node` | строка — IP выходного узла | `100.64.0.1` |
| `tun_enabled` | boolean — режим VPN (TUN) | `true` |
| `byedpi_enabled` | boolean — обход ByeDPI | `true` |

### adb

```sh
# Отключиться (токен нужен для каждого действия)
adb shell am broadcast -a io.github.bropines.tailscaled.action.DISCONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Подключиться
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Обновить виджеты и плитку
adb shell am broadcast -a io.github.bropines.tailscaled.action.GET_STATUS -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Задать выходной узел, взять рекомендованный, сбросить
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node 100.64.0.1
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node best
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node none

# Переключить аккаунт
adb shell am broadcast -a io.github.bropines.tailscaled.action.SWITCH_ACCOUNT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es account Work

# Режим VPN (TUN) и ByeDPI (boolean-extras передаются через --ez)
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_TUN -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_BYEDPI -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true --es flags "-o1 -a1 -r-5+se"
```

### Tasker и MacroDroid

**Система → Отправить Intent** в Tasker:

- Action: `io.github.bropines.tailscaled.action.CONNECT`
- Пакет: `io.github.bropines.tailscaled`
- Класс: `io.github.bropines.tailscaled.core.TaskerReceiver`
- Extra: `secret:YOUR_TOKEN`
- Цель: **Broadcast Receiver**

В MacroDroid действие «Отправить Intent» принимает те же поля, extra — ключ `secret` и значение `YOUR_TOKEN`.

## Ассистенты (Android 16+)

На Android 16 и новее TailSocks регистрирует в системе 14 AppFunctions, чтобы ассистент на устройстве, например Gemini, мог управлять им обычными словами. На более старых Android ничего не регистрируется. Проверить: `adb shell dumpsys app_function`.

Токен им не нужен: кто может их вызывать, решает система. Функции только для чтения отвечают всегда; функции, которые что-то меняют, слушаются **Разрешить внешнюю автоматизацию** — когда он выключен, они возвращают `success = false` и *External automation is disabled in TailSocks settings.*

| Функция | Вид | Что делает |
|---|---|---|
| `getStatus()` | чтение | Состояние соединения, активный аккаунт, IP выходного узла и включены ли режим VPN, ByeDPI, MagicDNS и доступ из локальной сети. |
| `getAvailableExitNodes()` | чтение | Узлы, которые предлагают себя выходными, в сети ли каждый и какой используется. |
| `getTailnetPeers()` | чтение | Все узлы tailnet: имя, IP, ОС и в сети ли. |
| `getAccounts()` | чтение | Профили и активный из них. |
| `connect(exitNodeIp)` | изменение | Запускает службу, сначала выбрав выходной узел, если он передан; ждёт до 6 с и сообщает, что видит. |
| `disconnect()` | изменение | Останавливает службу. Окончательно, как ручная остановка. |
| `toggle()` | изменение | `disconnect()`, если работает, иначе `connect("")`. |
| `selectExitNode(exitNodeIp)` | изменение | Задаёт выходной узел по Tailscale IP — `best` берёт рекомендованный Tailscale, `off`, `none` или пустая строка сбрасывает — и передаёт его работающему демону. |
| `clearExitNode()` | изменение | То же, что `selectExitNode("")`. |
| `switchAccount(accountNameOrId)` | изменение | Переключает активный профиль по имени или ID; перезапускает демона, если он работал. |
| `setByeDpi(enabled, flags)` | изменение | Включает или выключает обход ByeDPI, заменяя флаги, если они переданы. |
| `setTunMode(enabled)` | изменение | Включает или выключает режим VPN (TUN). |
| `setAllowLanAccess(enabled)` | изменение | Включает или выключает **Открыть прокси в локальную сеть**: SOCKS5, HTTP и DNS слушают на `0.0.0.0`. |
| `setMagicDns(enabled)` | изменение | Включает или выключает **Использовать MagicDNS**. |

## Ссылки на экраны

Ссылка `tailsocks://` открывает экран и больше ничего не делает: не меняет настроек и ничего не запускает, поэтому токен ей не нужен. `tailcat/add` только заполняет новое подключение TailCat; сохраняете его вы.

| Ссылка | Что откроет |
|---|---|
| `tailsocks://serve` | Serve & Funnel |
| `tailsocks://exitnode` | выбор выходного узла, на главном экране |
| `tailsocks://scan` | QR-сканер, поверх главного экрана |
| `tailsocks://tailcat` | TailCat |
| `tailsocks://tailcat/add?cmd=<адрес или команда подключения>` | новое подключение TailCat, уже заполненное (`cmd` — в URL-кодировке) |
| `tailsocks://logs?category=ERROR` | логи одной категории: `ALL`, `ERROR`, `CORE`, `TAILSCALE`, `TAILCAT`, … |
| `tailsocks://settings/<раздел>` | страницу настроек |
| `tailsocks://settings/<раздел>/<строка>` | страницу настроек с подсвеченной строкой |
| `tailsocks://settings?q=<слова>` | настройки с этими словами в поиске (в URL-кодировке) |
| `tailsocks://peers`, `dns`, `netcheck`, `console`, `files`, `taildrive`, `permissions`, `licenses` | этот экран |

Все страницы и строки настроек со ссылками: [Поиск и ссылки на настройки](/tailsocks/ru/settings-links/#все-настройки). Экран, которого на устройстве нет, — например, TailCat на телевизоре, — открывает вместо себя главный экран.

```sh
adb shell am start -a android.intent.action.VIEW -d 'tailsocks://logs?category=TAILCAT'
```

### В QR-кодах

QR-сканер приложения — в верхней панели главного экрана, в верхней панели TailCat, в его пустом списке и в редакторе подключения или по `tailsocks://scan` — читает камеру или картинку из галереи:

- Ссылка `tailsocks://` открывается ровно так, как если бы по ней нажали.
- Адрес TailCat или команда подключения открывает редактор нового подключения, уже заполненный.
- Всё остальное только показывается, с кнопками «Копировать» и, для веб-адреса, «Открыть». Ничего из кода не выполняется без нажатия.

У QR-кода TailCat — карточки подключения или сервера, «Подключиться к этому телефону», — две вкладки: **Ссылка**, `tailsocks://tailcat/add?cmd=…`, которую камера любого телефона откроет в TailSocks, и **Команда** — строки `tailcat` для компьютера. Сканер читает обе. Разрешение на камеру запрашивается, когда сканер открывается; галерее оно не нужно.

## В фоне

Переживёт ли соединение уход приложения с экрана, решают четыре переключателя в Настройки → **Работа в фоне и разрешения**:

- **Автопереподключение** — по умолчанию выключено. Перезапускает демона, если соединение не поднялось или оборвалось: после трёх неудачных проверок подряд и никогда — пока он ждёт, когда вы войдёте. **Попыток перезапуска (0 — без лимита)** ограничивает число перезапусков, по умолчанию 3.
- **Восстанавливать службу в фоне** — по умолчанию включено. Раз в 15 минут проверяет, жива ли служба, пока вы хотите соединение, и запускает её снова, если её остановила система или «оптимизатор» производителя. При включении просит разрешение «Будильники и напоминания»: с ним проверка точна и может поднять службу из фона, без него приходит позже. Если запуск всё равно запрещён, уведомление переподключит одним нажатием, а при следующем открытии приложение один раз подскажет настройку автозапуска производителя.
- **Держать соединение активным** — по умолчанию выключено. Держит wake lock всю сессию, чтобы keepalive уходили и в глубоком сне. Расходует батарею.
- **Автозапуск при загрузке** — по умолчанию выключено. Запускает приложение после перезагрузки, если оно работало до неё.

Два правила действуют, что бы ни говорили переключатели:

- **Ручная остановка окончательна.** Остановка из приложения, уведомления, плитки, командой `DISCONNECT` или AppFunction `disconnect` значит, что ничто из перечисленного не вернёт службу, пока вы её не запустите.
- **Смахивание приложения из «Недавних» соединение не рвёт.**
