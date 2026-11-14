# 🛠 Сборка TailSocks

TailSocks использует высокоавтоматизированный и модульный конвейер сборки, исключающий технический долг, связанный с поддержкой громоздкого форка Tailscale.

---

## ⚙️ Конвейер Динамических Патчей (Dynamic Injection Pipeline)

Вместо постоянного разрешения конфликтов слияния (merge conflicts) скрипт сборки применяет патч-конвейер:
1. **Загрузка Чистого Ядра:** Скачивается облегченный архив стабильного исходного кода Tailscale (версия закреплена в `appctr/TAILSCALE_VERSION`, сейчас `v1.104.5`; архив сверяется с `appctr/TAILSCALE_SHA256`).
2. **Атомарное Внедрение Патчей:** Применяется серия модульных атомарных `.patch` файлов (из папки `appctr/patches/`) в алфавитном порядке для адаптации исходников под мобильные функции (поддержка SOCKS5 прокси, нативная файловая система для Taildrop, генерация сертификатов LocalAPI и мониторинг сети Android).
3. **Агрессивное Оптимизирование:** С помощью большого набора тегов сборки Go (`ts_omit_systray`, `ts_omit_kube`, `ts_omit_aws`, `ts_omit_bird`, `ts_omit_drive` и др.) из кода вырезаются неиспользуемые компоненты Linux, серверных систем и корпоративных модулей.
4. **Результат:** Один `libtailscale.so` на каждую ABI — демон со встроенным CLI (`ts_include_cli`), быстро компилирующийся и минимально расходующий ресурсы в песочнице Android.

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
*Скрипт сборки автоматически скачает нужную версию Tailscale, применит патчи и скомпилирует бинарник PIE (`libtailscale.so`) под 4 архитектуры: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`. Он собран с апстримовским тегом `ts_include_cli` и потому заодно является CLI: работает как `tailscale`, когда его argv[0] — `tailscale` (ссылка приложения в каталоге данных) или когда задан `TS_BE_CLI=1` (обёртка Root-режима). До 4.9 CLI был отдельным `libtailscale_cli.so`; один файл вместо двух экономит около 12 МБ на ABI на устройстве. Устаревший `libtailscale_cli.so`, найденный в `jniLibs`, `build.sh` удаляет.*

### 3. Сборка Android APK:

#### Debug APK (без keystore, ставится рядом с релизом благодаря суффиксу `.dev`):
```bash
./gradlew app:assembleDebug
```

#### Одна ABI на время разработки
Обычный `assembleDebug` пишет и подписывает пять APK (по одному на ABI и universal, вместе около 450 МБ, один universal — ~145 МБ). Для устройства под рукой собирайте только его ABI; `appctr/build.sh` принимает тот же выбор через `TS_ABIS`:
```bash
(cd appctr && TS_ABIS=arm64-v8a bash build.sh)
./gradlew app:assembleDebug -PtargetAbi=arm64-v8a
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```
Gradle тогда делает два APK этой одной ABI (`app-arm64-v8a-debug.apk` и universal с той же ABI — universal APK собирается всегда, ради воспроизводимости релиза). Без `-PtargetAbi` ничего не меняется: все четыре ABI и universal APK — так собирают релиз и F-Droid. В ядре, собранном с `TS_ABIS`, нет остальных ABI, поэтому перед полной сборкой запустите `build.sh` ещё раз без него.

#### Кэш конфигурации
Сборка чиста под кэшем конфигурации Gradle (проверены debug, юнит-тесты и релизная сборка), а он экономит фазу конфигурации на каждой повторной команде. В `gradle.properties` репозитория он не включён: этот файл использует и F-Droid, и проблема кэша сломала бы их сборку ради выигрыша, который им не нужен. Включите его у себя в `~/.gradle/gradle.properties`:
```properties
org.gradle.configuration-cache=true
```
`scripts/licenses/generate.py` передаёт `--no-configuration-cache` ради своего init-скрипта.

#### Тесты
JVM-тесты и, на Linux, Go-тесты моста (им нужно пропатченное дерево, которое `build.sh` оставляет в `appctr/tailscale_src`; на macOS мост не собирается под хост — `netmon.WakePollingMonitors` из патча 21 не собирается для darwin, — запускайте их в Linux-контейнере, например в образе `golang`, или оставьте CI):
```bash
./gradlew app:testDebugUnitTest
(cd appctr && go test -count=1 .)
```
CI запускает и те и другие — вместе с быстрыми тестами пропатченных пакетов Tailscale, debug APK одной ABI и lint-проверкой NewApi — на каждый push в `main` и каждый pull request (`.github/workflows/ci.yml`), а релизный workflow — перед публикацией тега. Вызов API выше `minSdk` роняет любую релизную сборку (`lintVital`, см. `app/build.gradle.kts`).

#### Release APK (нужен собственный keystore):
С версии 4.0.0 сборка **отказывается подписывать релиз debug-ключом**: такой APK ставится один раз, а потом его нельзя обновить нормально подписанной сборкой (Android отвергает обновление с другим сертификатом, и остаётся только удалять приложение вместе с данными). Задайте все четыре переменные, иначе задача `packageRelease` завершится с понятной ошибкой:
```bash
KEYSTORE_FILE="$PWD/tailsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease
```
Если keystore ещё нет, создайте его: `keytool -genkeypair -v -keystore tailsocks.jks -alias tailsocks -keyalg RSA -keysize 4096 -validity 10000`.

---

## 🔬 Особенности релизной сборки

* **R8-минификация и сжатие ресурсов включены** (`isMinifyEnabled = true`, `isShrinkResources = true`). В проекте намеренно нет JSON через рефлексию: модели сериализуются `kotlinx.serialization` (`core/AppJson.kt`), ресурсы никогда не ищутся по динамическому имени, а сервис AppFunctions создаёт сгенерированные KSP классы `$Aggregated…_Impl` напрямую, а не через рефлексию, поэтому урезанная сборка не зависит от keep-правил для этих путей.
* **Проверка JNI (`verifyReleaseNativeMethods`).** TUN-библиотека регистрирует Java-методы по имени внутри `JNI_OnLoad`; если R8 удалит хотя бы один `external fun`, `System.loadLibrary` упадёт в рантайме (однажды так и было — каждая остановка роняла приложение). Задача запускается автоматически после `minifyReleaseWithR8` и перед `assembleRelease` / `bundleRelease`: она собирает все `external fun` из `app/src/main/java`, проверяет, что каждый попал под keep-правило в `seeds.txt` R8 и что ни один native-член не значится в `usage.txt`, иначе сборка падает. Отдельно: `./gradlew :app:verifyReleaseNativeMethods` после релизной сборки. При ошибке правьте `app/proguard-rules.pro` — обычный `-keep` для `native <methods>` (не `-keepclasseswithmembernames`).
* **Лицензии сторонних компонентов (`app/src/main/assets/licenses.json`).** Экран «Лицензии открытого ПО» читает закоммиченный список: `python3 scripts/licenses/generate.py` собирает его из того, что реально попадает в сборку, — списков Go-модулей, записанных в `libgojni.so` и `libtailscale.so`, артефактов `releaseRuntimeClasspath` (через `scripts/licenses/shipped-artifacts.init.gradle.kts`) и C-библиотек ndk-build, — а тексты лицензий берёт из кэша Go-модулей, POM-файлов и самих артефактов. Запускайте после `appctr/build.sh` и Gradle-сборки при каждой смене зависимостей; CI делает `--check` и предупреждает, если список устарел. Тексты, которых нет ни в одном артефакте, лежат в `scripts/licenses/texts/`.
* **Версионирование:** `versionName` и `versionCode` берутся из `version.properties` (`4.5.2` / `4050200`; отладочная сборка добавляет `-dev`); хэш коммита — `BuildConfig.GIT_HASH`. Сборщик, который сам обновляет приложение, добавляет `-PselfUpdate=false`. Код должен следовать из имени — релиза или беты (`5.1.0-beta.1` / `5009910`), — иначе Gradle откажется конфигурировать сборку: правило, два канала и порядок выпуска релиза описаны в [RELEASING_RU.md](RELEASING_RU.md).

---

## 📱 Установка и Запуск через ADB

Включены ABI-сплиты, поэтому каждая сборка даёт universal APK и по одному на архитектуру (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) в `app/build/outputs/apk/<debug|release>/`.

Для одного устройства APK его ABI вдвое меньше universal (см. «Одна ABI на время разработки» выше).

```bash
# Debug-сборка
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk

# Release-сборка
adb install -r app/build/outputs/apk/release/app-universal-release.apk

# Запуск основного экрана
adb shell am start -n io.github.bropines.tailscaled/io.github.bropines.tailscaled.ui.MainActivity
```
