# TailSocks: Инструкция для ИИ-агентов (Developer Agent Guide)

Данный документ содержит обязательные правила, архитектурные принципы и практические инструкции для любого искусственного интеллекта (агента), работающего с репозиторием **TailSocks**.

---

## 🏗️ Архитектура и стек технологий

Проект TailSocks представляет собой гибридное Android-приложение, состоящее из следующих уровней:
1. **Core (Go daemon)**: Патченный исходный код `tailscaled` (версия v1.98.3), компилируемый в нативную библиотеку `libtailscale.so`.
2. **Bridge (Go/Gomobile)**: Модуль `appctr`, предоставляющий JNI-биндинги (класс `appctr.Appctr` в Kotlin) для управления демоном через Unix-сокет (`tailscaled.sock`) без использования CLI.
3. **UI (Kotlin/Compose)**: Современный интерфейс на Jetpack Compose, Material 3, с компактным дашбордом.
4. **TUN Engine (C)**: Нативная библиотека `libhev-socks5-tunnel.so` для прозрачной переадресации трафика на уровне ядра через VPN-интерфейс в SOCKS5-прокси демона.
5. **ByeDPI (JNI/C)**: Нативный обход DPI для координационного сервера Tailscale, привязывающийся к случайному loopback IP/порту.

### Ключевые архитектурные правила:
* **CLI-less управление**: Управление демоном происходит строго через `tailscaled.sock` посредством LocalAPI v0. Запрещено использовать вызовы внешних бинарников через CLI (`Runtime.exec` или `exec.Command` для утилит tailscale/tailscaled), за исключением перезапуска процессов в аварийном режиме.
* **Принцип "Reset-then-Apply"**: При обновлении настроек Serve/Funnel или AdvertiseServices сначала отправляется пустой объект `{}` (Reset) для сброса состояния, затем применяется новая конфигурация.
* **Изоляция учетных записей (Profiles)**: Данные каждого аккаунта изолируются в `/files/states/{id}/`, а настройки сохраняются в `appctr_{id}`.

---

## 🛠️ Сборка проекта (Build System)

Модификация проекта состоит из двух этапов сборки:

### 1. Сборка Go-ядра (`appctr`)
При изменении Go-кода, сетевого моста или патчей Tailscale необходимо скомпилировать `.aar` библиотеку и нативные `.so` файлы:
```bash
cd appctr
./build.sh
```
*Скрипт автоматически скачает чистые исходники Tailscale (если их нет), наложит патчи из `appctr/patches/` в алфавитном порядке и соберет бинарники под архитектуры `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.*

* **Управление патчами**: Если вы изменили исходники в `appctr/tailscale_src/`, сгенерируйте новые патчи с помощью скрипта:
  ```bash
  ./appctr/patches/recreate_patches.sh
  ```
  Патчи создаются путем сравнения `appctr/tailscale_src/` с оригинальным кодом в `appctr/orig/`.

### 2. Сборка Android-приложения (APK)
```bash
./gradlew app:assembleRelease
```

---

## 📂 Структура проекта (Project Layout)

* [`app/src/main/java/io/github/bropines/tailscaled/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/) — Исходный код Android-приложения (Kotlin/Compose).
  * [`admin/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/admin/) — Панель администратора Tailscale Admin API.
  * [`core/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/) — Фоновые службы, VPN-сервис, менеджер аккаунтов, ByeDPI.
  * [`ui/`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/ui/) — Compose-экраны и компоненты интерфейса.
* [`appctr/`](file:///home/pinus/projects/tailsocks/appctr/) — Go-мост (Gomobile).
  * [`patches/`](file:///home/pinus/projects/tailsocks/appctr/patches/) — Атомарные патчи для исходного кода Tailscale.
  * [`tailscale_src/`](file:///home/pinus/projects/tailsocks/appctr/tailscale_src/) — Измененные исходные коды Tailscale (не коммитить в git, скачиваются/патчатся при сборке).
  * [`orig/`](file:///home/pinus/projects/tailsocks/appctr/orig/) — Оригинальные исходные коды Tailscale для генерации патчей (не коммитить в git).

---

## 🧼 Принципы DRY и KISS

1. **KISS (Keep It Simple, Stupid)**:
   - Не усложняйте логику сетевых проверок и не пишите тяжелых фоновых циклов мониторинга. Доверяйте жизненному циклу демона Tailscale.
   - Избегайте создания избыточных абстракций в Kotlin/Compose. Используйте стандартные Material 3 компоненты.
2. **DRY (Don't Repeat Yourself)**:
   - Общие функции (парсинг адресов, получение активного аккаунта, работа с SharedPreferences) должны находиться в [`Utils.kt`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/Utils.kt) или [`GlobalSettings.kt`](file:///home/pinus/projects/tailsocks/app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt).
   - При внесении изменений сначала проверьте наличие готовых методов и хелперов.

---

## 📝 Правила ведения документации и CHANGELOG.md

Ведение файла [`CHANGELOG.md`](file:///home/pinus/projects/tailsocks/CHANGELOG.md) подчиняется строгим правилам:
1. **Эгида новой версии**:
   - Если последний существующий Git-тег (например, `v3.1.0`) уже выпущен/существует в репозитории, для любых новых изменений **необходимо объявить следующую разрабатываемую версию** в CHANGELOG.md (например, `## [3.1.1] - YYYY-MM-DD` или `## [3.2.0-beta]`), и описывать изменения под её заголовком.
   - Нельзя дописывать изменения в уже существующую (выпущенную) версию, так как история изменений должна точно отражать содержимое конкретного релиза.
2. **Только реальные факты**:
   - Записывайте в CHANGELOG.md только те изменения, которые **были фактически интегрированы в кодовую базу** (то, что попало в коммиты).
   - Запрещено писать в Changelog "придуманные по пути" идеи, планы на будущее или нереализованные концепты агента. Люди смотрят Changelog, чтобы понять текущую разницу в коде.
3. **Минималистичный стиль**:
   - Пишите на простом, лаконичном английском языке (факты, без рекламы и лишних прилагательных). Используйте стандарт Keep a Changelog (Added, Changed, Fixed, Removed).

---

## 🐙 Правила работы с Git

1. **Атомарные коммиты**:
   - Делайте `git commit -m "..."` сразу после выполнения каждой логической задачи (например, пофиксили баг в разметке — коммит, добавили новый хелпер — коммит).
   - Не накапливайте кучу разнородных изменений для одного огромного коммита в конце сессии.
2. **Игнорирование директории `.agents`**:
   - Директория `.agents/` (содержащая скилы и временные файлы агента) добавлена в `.gitignore`.
   - **Категорически запрещено коммитить папку `.agents/` или её содержимое в Git.** Всегда проверяйте вывод `git status` перед коммитом.
