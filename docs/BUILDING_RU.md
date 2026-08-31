# 🛠 Сборка TailSocks

TailSocks использует высокоавтоматизированный и модульный конвейер сборки, исключающий технический долг, связанный с поддержкой громоздкого форка Tailscale.

---

## ⚙️ Конвейер Динамических Патчей (Dynamic Injection Pipeline)

Вместо постоянного разрешения конфликтов слияния (merge conflicts) скрипт сборки применяет патч-конвейер:
1. **Загрузка Чистого Ядра:** Скачивается облегченный архив стабильного исходного кода Tailscale (версия задаётся в `appctr/TAILSCALE_VERSION`).
2. **Атомарное Внедрение Патчей:** Применяется серия модульных атомарных `.patch` файлов (из папки `appctr/patches/`) в алфавитном порядке для адаптации исходников под мобильные функции (поддержка SOCKS5 прокси, нативная файловая система для Taildrop, генерация сертификатов LocalAPI и мониторинг сети Android).
3. **Агрессивное Оптимизирование:** С помощью большого набора тегов сборки Go (`ts_omit_systray`, `ts_omit_kube`, `ts_omit_aws`, `ts_omit_bird`, `ts_omit_drive` и др.) из кода вырезаются неиспользуемые компоненты Linux, серверных систем и корпоративных модулей.
4. **Результат:** Высокооптимизированные библиотеки `libtailscale.so`, быстро компилирующиеся и минимально расходующие ресурсы в песочнице Android.

---

## 🛠️ Требования к Окружению

- **Операционная система**: Linux / macOS
- **Go**: 1.23+
- **Android SDK**: API 34 (Android 14)
- **Android NDK**: 27.x (переменная `ANDROID_NDK_HOME`)
- **gomobile**: `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`
- **Gradle**: 8.x+ (используется обертка `./gradlew`)

---

## 📦 Инструкция по Сборке

### 1. Клонирование репозитория:
```bash
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks
```

### 2. Компиляция нативного ядра Go (`appctr`):
```bash
cd appctr
bash build.sh
cd ..
```
*Скрипт сборки автоматически скачает нужную версию Tailscale, применит патчи и скомпилирует бинарники PIE (`libtailscale.so`) под 4 архитектуры: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.*

### 3. Сборка Android APK:

#### Release APK:
```bash
./gradlew app:assembleRelease
```

#### Debug APK:
```bash
./gradlew app:assembleDebug
```

> **Примечание:** Оптимизатор R8 полностью поддерживается. В Kotlin-классах данных используются аннотации `@Keep` для обеспечения корректного парсинга JSON через `Gson` в релизных сборках.

---

## 📱 Установка и Запуск через ADB

```bash
# Установка Release версии
adb install -r app/build/outputs/apk/release/app-release.apk

# Запуск основного экрана
adb shell am start -n io.github.bropines.tailscaled/io.github.bropines.tailscaled.ui.MainActivity
```
