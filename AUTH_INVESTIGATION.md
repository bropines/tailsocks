# Исследование механизма аутентификации Tailscale Android (для фикса TailSocks)

## 📌 Контекст проблемы
В проекте `TailSocks` при попытке подмены `OS: linux` для обхода JNI-зависимостей возникает ошибка `410 Gone: auth path not found`. Это означает, что сервер (control plane) аннулирует сессию авторизации, так как видит несоответствие между типом клиента, его ключами и ожидаемым поведением протокола.

## 🛠 Архитектура аутентификации в tailscale-android

### 1. Цепочка вызова (Frontend -> Backend)
Процесс начинается в Kotlin и уходит в Go через Local API:
1.  **Kotlin (`IpnViewModel.kt`)**: Метод `login()`.
    - Сначала вызывает `UninitializedApp.get().startForegroundForLogin()` (поддержка процесса в фоне).
    - Вызывает `client.start(opts)`, где `opts` содержит `AuthKey` (если есть) и настройки `ControlURL`.
    - **Важно:** `client.start()` вызывает `backend.Start` в Go, который сбрасывает старый `ControlClient` и создает новый.
    - Затем вызывает `client.startLoginInteractive()` -> эндпоинт `/localapi/v0/login-interactive`.

2.  **Go-Bridge (`libtailscale/localapi.go`)**:
    - Принимает запрос от Kotlin через JNI.
    - Передает его в `localapi.Handler`, который встроен в `LocalBackend`.

3.  **Go-Core (`tailscale.com/ipn/ipnlocal`)**:
    - `LocalBackend` инициирует `Login` в `ControlClient`.
    - Генерируется событие `ipn.Notify` с заполненным полем `BrowseToURL`.

### 2. Передача URL обратно в UI
1.  **Go (`libtailscale/notifier.go`)**: Метод `WatchNotifications` маршалит структуру `ipn.Notify` в JSON и через JNI вызывает `cb.OnNotify(b)`.
2.  **Kotlin (`Notifier.kt`)**: Декодирует JSON и обновляет `StateFlow<String?>` под названием `browseToURL`.
3.  **UI (`MainActivity.kt`)**: Подписан на `Notifier.browseToURL`. Как только значение появляется, вызывается `login(urlString)`, который открывает Custom Tabs или системный браузер.

## 🔍 Ключевые технические факторы (Почему возникает 410 Gone)

### 1. Поле `HostInfo` и идентификация ОС
В `libtailscale/backend.go` вызывается:
```go
hostinfo.SetOSVersion(a.osVersion())
hostinfo.SetPackage(a.appCtx.GetInstallSource())
hostinfo.SetDeviceModel(deviceModel)
```
Если в `TailSocks` подменяется только `OS: linux`, но остаются Android-специфичные поля (например, модель устройства в формате Android или InstallSource), сервер Tailscale детектирует аномалию и сбрасывает сессию.

### 2. Hardware Attestation (Android-only)
Официальный клиент использует `HardwareAttestationKey` (связка с Android KeyStore).
- Файл: `libtailscale/keystore.go`
- В `tailscale.go` выполняется регистрация: `key.RegisterHardwareAttestationKeyFns(...)`.
- **Гипотеза:** Если сервер видит клиента "Android", он может требовать аппаратную подпись ключа машины. Если подмена на "Linux" происходит не полностью, сервер может ожидать аттестацию, не получать её и возвращать 410.

### 3. MachineKey и состояние сессии
Ошибка 410 (Gone) часто означает, что `long polling` запрос клиента пришел по пути, который сервер больше не считает валидным.
Это происходит, если:
- Клиент создал сессию с `MachineKey_A`, но из-за перезапуска бэкенда или ошибки в логике `editPrefs` попытался продолжить с `MachineKey_B`.
- В Android-клиенте `MachineKey` хранится в `EncryptedSharedPreferences`. При подмене на Linux путь к хранилищу ключей может меняться.

## 💡 Рекомендации для написания фиксов

1.  **Полная мимикрия (если выбран путь OS: linux)**:
    - Нужно патчить не только `runtime.GOOS`, но и весь пакет `hostinfo`.
    - Необходимо убедиться, что `MachineKey` и `NodeKey` генерируются и сохраняются в файловую систему, а не пытаются уйти в JNI-заглушки Android KeyStore.

2.  **Стабилизация Android-идентичности (рекомендуемый путь)**:
    - Оставить `OS: android`.
    - Принудительно отключить `HardwareAttestation` в Go-коде (установить `hwAttestEnabled = false`), чтобы бэкенд использовал стандартные программные ключи, которые не требуют JNI-вызовов к системному KeyStore.
    - Проверить, что `ControlURL` передается корректно до вызова `startLoginInteractive`.

3.  **Логирование Local API**:
    - Внедрить логирование всех запросов к Local API внутри Go-моста. Ошибка 410 часто сопровождается телом ответа (JSON), в котором сервер Tailscale пишет более подробную причину (например, `session expired` или `invalid machine key`).

4.  **Синхронизация состояний**:
    - Убедиться, что `backend.Start` вызывается только один раз перед началом логина. Повторный вызов `Start` во время ожидания браузера — гарантированный способ получить 410, так как старый `ControlClient` (и его сессия на сервере) уничтожается.

## 📑 Список важных файлов для анализа:
- `libtailscale/backend.go` — инициализация бэкенда и HostInfo.
- `libtailscale/interfaces.go` — JNI-мостик.
- `libtailscale/localapi.go` — обработка запросов к API.
- `android/src/main/java/com/tailscale/ipn/ui/viewModel/IpnViewModel.kt` — логика запуска логина.
