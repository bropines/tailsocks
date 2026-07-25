# ⚡ Руководство по Интеграции с Tasker и Автосценариями

TailSocks поддерживает полнофункциональное фоновое управление через **Android Broadcast Intents**. Это позволяет полностью контролировать подключения, профили, Exit Nodes, прокси ByeDPI и режим TUN из приложений автоматизации, таких как **Tasker**, **MacroDroid**, **Automate** или через консоль **ADB**.

---

## 🔒 Безопасность и Защита Токеном

Параметры автоматизации можно настроить в приложении: **Настройки → Tasker и Автоматизация**:

* **Разрешить внешнюю автоматизацию:** Главный переключатель включения/отключения обработки broadcast-сообщений.
* **Секретный Токен Безопасности (Опционально):** Задание секретного пароля (например, `my_secret_token_123`). Если токен задан, `TaskerReceiver` будет строго отклонять любые intent-запросы, не содержащие совпадающий строковый параметр `secret`, `token` или `key`.

---

## 🎯 Поддерживаемые Intent Команды

### Параметры Целевого Receiver
* **Имя пакета (Package):** `io.github.bropines.tailscaled`
* **Класс компонента (Component):** `io.github.bropines.tailscaled.core.TaskerReceiver` *(опционально)*
* **Тип назначения:** Broadcast Receiver

### Управляющие Действия (Actions)

| Action (Действие) | Короткий псевдоним | Extras / Параметры | Описание |
|-------------------|--------------------|--------------------|----------|
| `io.github.bropines.tailscaled.action.CONNECT` | `io.github.bropines.tailscaled.START` | `secret` *(опционально)* | Запускает фоновый демон и подключает TailSocks. |
| `io.github.bropines.tailscaled.action.DISCONNECT` | `io.github.bropines.tailscaled.STOP` | `secret` *(опционально)* | Отключает сеть и останавливает фоновый демон. |
| `io.github.bropines.tailscaled.action.TOGGLE` | `io.github.bropines.tailscaled.TOGGLE` | `secret` *(опционально)* | Переключает состояние подключения. |
| `io.github.bropines.tailscaled.action.RESTART` | `io.github.bropines.tailscaled.RESTART` | `secret` *(опционально)* | Перезапускает фоновый демон. |
| `io.github.bropines.tailscaled.action.GET_STATUS` | `io.github.bropines.tailscaled.GET_STATUS` | `secret` *(опционально)* | Запрашивает мгновенный broadcast-ответ с текущими метриками. |
| `io.github.bropines.tailscaled.action.SET_EXIT_NODE` | `io.github.bropines.tailscaled.SET_EXIT_NODE` | `exit_node` (String: IP или `"none"` для сброса) | Устанавливает Exit Node для активного профиля. |
| `io.github.bropines.tailscaled.action.SWITCH_ACCOUNT` | `io.github.bropines.tailscaled.SWITCH_ACCOUNT` | `account` (String: ID профиля или Имя) | Переключает активный профиль TailSocks. |
| `io.github.bropines.tailscaled.action.SET_BYEDPI` | `io.github.bropines.tailscaled.SET_BYEDPI` | `enabled` (Boolean), `flags` (String) | Настраивает прокси управляющего сервера ByeDPI. |
| `io.github.bropines.tailscaled.action.SET_TUN` | `io.github.bropines.tailscaled.SET_TUN` | `enabled` (Boolean) | Настраивает режим системного VPN (TUN). |

---

## 📡 Автоматические Уведомления о Статусе (События)

При каждом изменении состояния TailSocks или при получении запроса `GET_STATUS` приложение отправляет Broadcast-сообщение на **`io.github.bropines.tailscaled.STATUS_CHANGED`** (псевдоним **`io.github.bropines.tailscaled.STATUS`**).

Вы можете настроить триггеры **Intent Received** в Tasker / MacroDroid для отслеживания `io.github.bropines.tailscaled.STATUS_CHANGED` и чтения следующих параметров Extras:

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

### 1. MacroDroid / Tasker (С Секретным Токеном)
* **Тип отправки:** Broadcast
* **Action:** `io.github.bropines.tailscaled.action.CONNECT`
* **Package:** `io.github.bropines.tailscaled`
* **Extra 1:** Ключ: `secret`, Значение: `"my_secret_token_123"`

### 2. Примеры Команд ADB

```bash
# Подключение с передачей токена безопасности
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT --es secret "my_secret_token_123" -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Запрос текущего статуса
adb shell am broadcast -a io.github.bropines.tailscaled.action.GET_STATUS -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Установка Exit Node
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE --es exit_node "100.64.0.1" -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Переключение Профиля
adb shell am broadcast -a io.github.bropines.tailscaled.action.SWITCH_ACCOUNT --es account "Work" -n io.github.bropines.tailscaled/.core.TaskerReceiver
```
