# 🛠 Сборка TailSocks

TailSocks использует высокоавтоматизированный и модульный конвейер сборки, исключающий технический долг, связанный с поддержкой громоздкого форка Tailscale.

---

## ⚙️ Конвейер Динамических Патчей (Dynamic Injection Pipeline)

Вместо постоянного разрешения конфликтов слияния (merge conflicts) скрипт сборки применяет патч-конвейер:
1. **Загрузка Чистого Ядра:** Скачивается облегченный архив стабильного исходного кода Tailscale (версия закреплена в `appctr/TAILSCALE_VERSION`, сейчас `v1.102.1`).
2. **Атомарное Внедрение Патчей:** Применяется серия модульных атомарных `.patch` файлов (из папки `appctr/patches/`) в алфавитном порядке для адаптации исходников под мобильные функции (поддержка SOCKS5 прокси, нативная файловая система для Taildrop, генерация сертификатов LocalAPI и мониторинг сети Android).
3. **Агрессивное Оптимизирование:** С помощью большого набора тегов сборки Go (`ts_omit_systray`, `ts_omit_kube`, `ts_omit_aws`, `ts_omit_bird`, `ts_omit_drive` и др.) из кода вырезаются неиспользуемые компоненты Linux, серверных систем и корпоративных модулей.
4. **Результат:** Высокооптимизированные библиотеки `libtailscale.so`, быстро компилирующиеся и минимально расходующие ресурсы в песочнице Android.

---

## 🛠️ Требования к Окружению

- **Операционная система**: Linux / macOS
- **Go**: конкретная версия не требуется — сборка использует `GOTOOLCHAIN=auto` и сама скачивает нужный тулчейн Go
- **Android SDK**: compileSdk 37, targetSdk 35, minSdk 24
- **Android NDK**: 27.x (переменная `ANDROID_NDK_HOME`)
- **gomobile**: `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`
- **Gradle**: 8.x+ (используется обертка `./gradlew`)
- **Keystore для релиза**: собственный `.jks` и переменные `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (см. ниже)

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

#### Debug APK (без keystore, ставится рядом с релизом благодаря суффиксу `.dev`):
```bash
./gradlew app:assembleDebug
```

#### Release APK (нужен собственный keystore):
С версии 3.6.0 сборка **отказывается подписывать релиз debug-ключом**: такой APK ставится один раз, а потом его нельзя обновить нормально подписанной сборкой (Android отвергает обновление с другим сертификатом, и остаётся только удалять приложение вместе с данными). Задайте все четыре переменные, иначе задача `packageRelease` завершится с понятной ошибкой:
```bash
KEYSTORE_FILE="$PWD/tailsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease
```
Если keystore ещё нет, создайте его: `keytool -genkeypair -v -keystore tailsocks.jks -alias tailsocks -keyalg RSA -keysize 4096 -validity 10000`.

---

## 🔬 Особенности релизной сборки

* **R8-минификация и сжатие ресурсов включены** (`isMinifyEnabled = true`, `isShrinkResources = true`). В проекте намеренно нет JSON через рефлексию: модели сериализуются `kotlinx.serialization` (`core/AppJson.kt`), ресурсы никогда не ищутся по динамическому имени, а сервис AppFunctions создаёт сгенерированные KSP классы `$Aggregated…_Impl` напрямую, а не через рефлексию, поэтому урезанная сборка не зависит от keep-правил для этих путей.
* **Проверка JNI (`verifyReleaseNativeMethods`).** TUN-библиотека регистрирует Java-методы по имени внутри `JNI_OnLoad`; если R8 удалит хотя бы один `external fun`, `System.loadLibrary` упадёт в рантайме (однажды так и было — каждая остановка роняла приложение). Задача запускается автоматически после `minifyReleaseWithR8` и перед `assembleRelease` / `bundleRelease`: она собирает все `external fun` из `app/src/main/java`, проверяет, что каждый попал под keep-правило в `seeds.txt` R8 и что ни один native-член не значится в `usage.txt`, иначе сборка падает. Отдельно: `./gradlew :app:verifyReleaseNativeMethods` после релизной сборки. При ошибке правьте `app/proguard-rules.pro` — обычный `-keep` для `native <methods>` (не `-keepclasseswithmembernames`).
* **Версионирование:** `versionCode` — число коммитов git плюс 500, `versionName` — `v<последний тег>-<6 символов хэша>` с суффиксами `.release` / `-dev`; в CI используйте `fetch-depth: 0`.

---

## 📱 Установка и Запуск через ADB

Включены ABI-сплиты, поэтому каждая сборка даёт universal APK и по одному на архитектуру (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) в `app/build/outputs/apk/<debug|release>/`.

```bash
# Debug-сборка
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk

# Release-сборка
adb install -r app/build/outputs/apk/release/app-universal-release.apk

# Запуск основного экрана
adb shell am start -n io.github.bropines.tailscaled/io.github.bropines.tailscaled.ui.MainActivity
```
