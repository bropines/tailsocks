# Руководство по Автоматизации (Tasker & Intent API)

TailSocks предоставляет полный набор Broadcast Intent для интеграции с **Tasker**, **MacroDroid** и другими системами автоматизации Android.

---

## 🔐 Настройка Безопасности

Для защиты команд автоматизации можно включить **Секретный Токен Автоматизации** в Настройках приложения. При включении токена все Broadcast Intent должны содержать строковый параметр `secret`.

---

## 📡 Поддерживаемые Intent Команды

### 1. Подключение / Отключение / Переключение

- **Подключить**:
  - Action: `io.github.bropines.tailscaled.CONNECT`
- **Отключить**:
  - Action: `io.github.bropines.tailscaled.DISCONNECT`
- **Переключить (Toggle)**:
  - Action: `io.github.bropines.tailscaled.TOGGLE`

### 2. Смена Аккаунта
- Action: `io.github.bropines.tailscaled.SWITCH_ACCOUNT`
- Extra (String): `account_id` = `<ID профиля>`

### 3. Настройка Exit Node
- Action: `io.github.bropines.tailscaled.SET_EXIT_NODE`
- Extra (String): `exit_node_id` = `<ID узла>` (передайте пустую строку `""` для сброса)

### 4. Управление ByeDPI и TUN
- **ByeDPI**: Action `io.github.bropines.tailscaled.SET_BYEDPI`, Extra `enabled` (Boolean)
- **TUN VPN**: Action `io.github.bropines.tailscaled.SET_TUN`, Extra `enabled` (Boolean)

---

## 📊 Слушатель Статуса (`STATUS_CHANGED`)

Приложение транслирует изменения состояния через Broadcast:
- Action: `io.github.bropines.tailscaled.STATUS_CHANGED`
- Extras:
  - `running` (Boolean)
  - `status` (String: `"ACTIVE"`, `"STOPPED"`, `"NEEDS_LOGIN"`)
  - `account` (String)
  - `exit_node` (String)
