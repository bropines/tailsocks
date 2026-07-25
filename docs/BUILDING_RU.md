# Инструкция по сборке TailSocks

Этот документ содержит руководство по компиляции нативных компонентов Go/C и сборке итогового Android APK.

---

## 🛠️ Требования к окружению

- **ОС**: Linux (Ubuntu 22.04+ рекомендовано)
- **Go**: 1.22+
- **Android SDK**: API 34 (Android 14)
- **Android NDK**: 27.0.12077973
- **Gradle**: 8.x+ (используется обертка `./gradlew`)

---

## 1. Сборка Нативного Go Ядра (`appctr`)

При изменении Go-кода, JNI-привязок или патчей Tailscale необходимо скомпилировать бинарные файлы `.so`:

```bash
export ANDROID_HOME=/home/pinus/android-sdk
export ANDROID_NDK_HOME=/home/pinus/android-sdk/ndk/27.0.12077973

cd appctr
./build.sh
```

`build.sh` автоматически:
1. Скачивает исходники Tailscale (v1.98.3), если они отсутствуют.
2. Применяет атомарные патчи из `appctr/patches/` в алфавитном порядке.
3. Компилирует нативные библиотеки `.so` под 4 архитектуры (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
4. Копирует скомпилированные `.so` в `app/src/main/jniLibs/`.

---

## 2. Сборка Android APK

### Debug / Dev APK (Идентификатор: `io.github.bropines.tailscaled.dev`)
```bash
./gradlew app:assembleDebug
```

### Release APK (Идентификатор: `io.github.bropines.tailscaled`)
```bash
./gradlew app:assembleRelease
```

---

## 📱 Установка и Запуск через ADB

```bash
# Установка DEV версий
adb install -r -d app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

# Запуск основного экрана
adb shell am start -n io.github.bropines.tailscaled.dev/io.github.bropines.tailscaled.ui.MainActivity
```
