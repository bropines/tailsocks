# Project Roadmap

This document outlines the planned features, architectural improvements, and refactoring goals for the TailSocks project.

## Completed Milestones
- [x] **Core Stability:** Passive daemon management and stateless configuration.
- [x] **File Sharing:** Taildrop implementation with Storage Access Framework integration.
- [x] **Profile Isolation:** Multi-account system with independent state persistence.
- [x] **Connectivity:** Custom DNS wrapping and Exit Node support.
- [x] **Tailscale Serve/Funnel:** Native UI for service hosting and public internet exposure.
- [x] **Local API v0:** 100% CLI-less operation via Go-HTTP bridge.
- [x] **System Integration:** Basic Quick Settings Tile for connectivity toggling.
- [x] **Service Monitoring:** Add health checks and request logs for hosted Serve/Funnel services.
- [x] **Search & Filter:** Implement search functionality for Peer list and Logs.
- [x] **Battery Optimization UI:** Add a prompt to help users whitelist the app from system battery restrictions.
- [x] **Account Switching in Tiles:** Enhance Quick Settings to allow switching between profiles.
- [x] **Socks5 proxy to Control Panel:** Added SOCKS5/HTTP proxy support for control plane traffic with atomic patches and netns bypass fix.
- [x] **Encrypted Backups:** Manual app state backups encrypted with a user-defined password (AES-GCM) to protect node keys.
- [x] **Quick Settings Tiles:** Quick Settings Tiles for profile switching and connection state management.
- [x] **R8 minification (4.0.0):** `isMinifyEnabled`/`isShrinkResources` are on for release builds. Gson was replaced by `kotlinx.serialization`, the AppFunctions service constructs the KSP-generated registries directly, and the `verifyReleaseNativeMethods` Gradle task fails the build if R8 drops a JNI method.
- [x] **Root Mode hardening (4.0.0):** dedicated `TAILSOCKS_MARK`/`TAILSOCKS_DNS` chains, masked fwmark `0x1000000/0x1000000`, Check Routing diagnostics and the ROOT log tab.
- [x] **Automation security (4.0.0):** broadcast receiver requires a secret token; AppFunctions (Gemini, Android 16+) actually execute and honour the automation switch.
- [x] **LAN Access, auto-reconnect, background revival and versioned backups (4.0.0).**
- [x] **Root Mode coexistence (4.0.0):** the policy ruleset is tiered — tailnet reachability, the default-route capture, the device-wide DNS redirect — with one owner per device for the last two, a partial yield that carries exactly the uids another VPN client bypasses, a health-gated DNS redirect and a CGNAT guard. Verified on the author's Redmi (APatch), including across a real reboot.

## Plans

Состояние на 2026-09-07, после выпуска 4.1.0.

### Крупное

- [ ] **Нативный TUN.** План: `docs/NATIVE_TUN_PLAN.md`, шесть шагов. Главный риск снят —
      политика SELinux разрешает приложению `TUNGETIFF` на дескрипторе туннеля
      (`allowxperm untrusted_app tun_device chr_file ioctl { 0x54D2 }`, прочитано с Redmi).
      Не проверено вживую: передача дескриптора в дочерний процесс через `SCM_RIGHTS`,
      поведение при смерти владельца, задержка при подмене. Требует отладочного помощника
      в APK — через `su` проверять бессмысленно, другой домен SELinux.
- [ ] **tsnet — идея для 5.0.** Демон внутрь процесса. Несовместимо с Root-режимом, где он
      обязан быть отдельным процессом под `su`.
- [ ] **Отдельный CLI-бинарник.** Решение за автором, отложено. Цена измерена: около 6 МБ в
      каждом срезе, 25 МБ в универсальном.

### Требует решения автора

- [ ] **Переключатель честной ОС.** Патч 06 подставляет `OS = linux`, потому что (по
      комментарию автора патча) координатор игнорирует объявленные сервисы у андроидных
      узлов. Проверено 2026-09-07: peerAPI это НЕ ломает — узел `Pixel 8` с `OS = android`
      в этой сети объявляет `peerapi4`/`peerapi6`, то есть Taildrop не под угрозой.
      Под вопросом остаются serve и funnel. Цена выясняется одним экспериментом на своей сети.
- [ ] **Три задачи от бота-сканера** (#5, #6, #7 и два PR). Проверено: `x/crypto/ssh` в
      сборке отсутствует (`ts_omit_ssh`), версию диктует апстрим, патч бота неприменим.
      Осталось закрыть с объяснением.
- [ ] **Задача #3** — запрос, с которого начался Root-режим. Автор уже ответил; закрывать
      или ждать подтверждения от `TheLastFlame` на его планшете.

### Проверка на устройствах

- [ ] **Root-режим на WSA после перезагрузки:** автозапуск через `service.d` и присоединение
      приложения к демону, которого оно не запускало.
- [ ] **Права принятых файлов в Root-режиме.** Правка сделана вслепую (`umask 022` + передача
      каталога приложению). В «Проверить маршрутизацию» добавлен вывод реальных прав —
      посмотреть и подтвердить.
- [ ] **Утечка exit node по IPv6** — на Redmi 2026-09-07 НЕ воспроизводится: маршрут по
      умолчанию в таблице есть, трафик уходит в туннель с тайлнет-адресом источника.
      Проверить на POCO, где сеть другая; если не воспроизведётся и там — вычеркнуть.
- [ ] **Версия пира из Admin API** — работает только при настроенном токене; проверить на
      устройстве, где он настроен.

### Мелкое

- [ ] **IPv6 в перехвате DNS.** На ядре Redmi (4.19) таблицы `nat` для шестой версии не
      существует — писать правила некуда, это ограничение прошивки, а не наш пробел.
      Сделать: сказать об этом в диагностике вместо нынешнего молчания, и проверить, не
      уходит ли DNS-запрос по IPv6 мимо MagicDNS.
- [ ] **Пять индикаторов загрузки внутри кнопок** остались старыми — новый компонент при
      размере 14 dp перестаёт читаться. Посмотреть на устройстве и решить.
- [ ] **Стабильная Material 3.** Сейчас `1.5.0-alpha27` ради компонентов, которых нет в 1.4.0.
      Выйдет стабильная — смена одной строки в `gradle/libs.versions.toml`.
- [ ] **Обновление Tailscale** с 1.102.1 на свежий выпуск. Подтянет и версии зависимостей,
      на которые ругается бот.
- [ ] **Английская версия пасхалки** — если делать, то другой шуткой, а не переводом.

### Аудит второго проекта

- [ ] **`bropines/tailscale-termux-cli`** — аудит запущен 2026-09-07, отчёт кладётся вне
      репозитория. Починка найденного — отдельной сессией из папки того проекта.

