<p align="center">
  <img src="docs/logo.svg" alt="Иконка TailSocks" width="128" height="128" />
</p>

<h1 align="center">TailSocks</h1>

<p align="center">
  <strong>Продвинутый Android-клиент для Tailscale с сетями в пользовательском пространстве (Userspace) и прозрачным TUN VPN</strong>
</p>

<p align="center">
  <a href="readme.md">English</a> | <strong>Русский</strong>
</p>

<p align="center">
  <a href="https://github.com/bropines/tailsocks/releases/latest"><img src="https://img.shields.io/github/v/release/bropines/tailsocks?style=for-the-badge&logo=github&logoColor=white&label=Latest%20Release&color=2ea44f" alt="Последний Релиз" /></a>
  <a href="https://github.com/bropines/tailsocks/releases"><img src="https://img.shields.io/github/downloads/bropines/tailsocks/total?style=for-the-badge&logo=android&logoColor=white&label=Downloads&color=3ddc84" alt="Загрузки" /></a>
  <a href="https://github.com/tailscale/tailscale/releases/tag/v1.98.3"><img src="https://img.shields.io/badge/Tailscale_Core-v1.98.3-blue?style=for-the-badge&logo=tailscale&logoColor=white" alt="Ядро Tailscale" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-BSD_3--Clause-orange?style=for-the-badge" alt="Лицензия" /></a>
</p>

<p align="center">
  <a href="https://github.com/bropines/tailsocks/releases/latest/download/app-release.apk">
    <img src="https://img.shields.io/badge/⬇_Download_APK-Release-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Скачать Release APK" />
  </a>
  &nbsp;
  <a href="https://github.com/bropines/tailsocks/releases">
    <img src="https://img.shields.io/badge/⬇_All_Releases-GitHub-24292e?style=for-the-badge&logo=github&logoColor=white" alt="Все Релизы" />
  </a>
</p>

---

TailSocks — это высокопроизводительный клиент Android для [Tailscale](https://tailscale.com/), работающий в режиме **пользовательской сети (userspace-networking)** через `tsnet`. Приложение предоставляет полный стек возможностей Tailscale — включая [Taildrop™](https://tailscale.com/kb/1106/taildrop), [Exit Nodes](https://tailscale.com/kb/1103/exit-nodes), [Serve & Funnel](https://tailscale.com/kb/1242/tailscale-serve) и [Taildrive™](https://tailscale.com/kb/1369/taildrive) — без необходимости запрашивать системное разрешение Android `VpnService`. Это позволяет параллельно использовать другие VPN и фаерволы на устройстве.

Опционально TailSocks поддерживает режим **прозрачного системного TUN VPN** на базе нативного движка [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), обеспечивая полную или разделяемую маршрутизацию трафика на уровне всей системы.

---

## ✨ Возможности

### Сеть и Подключение

| Функция | Описание |
|---------|----------|
| **Нативный LocalAPI** | Управление демоном 100% без CLI через Unix-сокет (`tailscaled.sock`) по протоколу LocalAPI v0. Без работы через оболочку. |
| **SOCKS5 Прокси** | Встроенный локальный сервер SOCKS5 с опциональной авторизацией для маршрутизации конкретных приложений. |
| **Прокси управляющего сервера** | Маршрутизация трафика к управляющему серверу через кастомный SOCKS5/HTTP прокси для заблокированных регионов. |
| **Режим TUN VPN** | Прозрачный системный VPN через нативную библиотеку `hev-socks5-tunnel` — полный и раздельный туннель, исключение приложений, кастомный IP шлюза. |
| **[Exit Nodes](https://tailscale.com/kb/1103/exit-nodes) ©** | Маршрутизация весь интернет-трафик через любой узел сети Tailscale с автовосстановлением и доступом к локальной сети. |
| **[MagicDNS](https://tailscale.com/kb/1081/magicdns) ©** | Разрешение имен узлов в памяти (0мс), Split DNS через SOCKS5 TCP, DoH фолбэк при сбоях. |
| **Обход NAT** | Мониторинг подключения `InMagicSock` в реальном времени. Диагностика STUN/DERP через нативный netcheck. |

### Сервисы и Обмен Файлами

| Функция | Описание |
|---------|----------|
| **[Tailscale Serve & Funnel](https://tailscale.com/kb/1242/tailscale-serve) ©** | Проброс локальных портов в Tailnet или публичный интернет. Режимы TCP и HTTPS, экспорт TLS-сертификатов. |
| **[Виртуальные Сервисы (`svc:`)](https://tailscale.com/kb/1438/virtual-ip) ©** | Создание именованных виртуальных сервисов с выделенными VIP и DNS-именами прямо из нативного UI. |
| **[Taildrop™](https://tailscale.com/kb/1106/taildrop) ©** | Отправка и получение файлов между устройствами Tailnet. Хаб входящих, интеграция с системным меню «Поделиться», DocumentsProvider. |
| **[Taildrive™](https://tailscale.com/kb/1369/taildrive) ©** | Шаринг локальных папок по WebDAV. Интеграция с SAF, монтирование удаленных ресурсов, SOCKS5-проксированный доступ. Исправлена чувствительность к регистру путей. |

### Управление и Администрирование

| Функция | Описание |
|---------|----------|
| **Изоляция Аккаунтов** | Строгое разделение данных каждого профиля — независимые каталоги состояния, настройки, ключи и папки Taildrop. |
| **Tailscale Admin API** | Интеграция с `api.tailscale.com/v2` — управление устройствами, DNS, пользователями, сервисами, вебхуками и логами. |
| **Биометрическая Защита** | Защита консоли администрирования по отпечатку пальца или Face ID. |
| **Ключи Авторизации** | Генерация, просмотр и отзыв Auth Keys прямо из приложения. |
| **Резервное Копирование** | Полный зашифрованный бэкап состояния приложения (ZIP) и экспорт отдельных аккаунтов (JSON). |

### Пользовательский Опыт

| Функция | Описание |
|---------|----------|
| **Компактный Дашборд** | Высокоплотный сетчатый интерфейс 2×4 — Консоль, Узлы, Логи, Файлы, DNS, Netcheck, Настройки, Serve. |
| **Дизайн Material 3** | Системная, Светлая, Тёмная и AMOLED Black темы. 7 цветовых пресетов + динамические цвета Material You. |
| **Локализация** | Crowdin-совместимая i18n система. Русский язык включен в комплект поставки. |
| **Виджеты Рабочего Стола** | Виджеты Jetpack Glance — Переключатель службы, Выходной узел, Дашборд статистики, Статус Serve. |
| **Плитка Быстрых Настроек** | Плитка в шторке Android с отображением активного профиля и быстрым переключением аккаунтов. |
| **Сетевая Диагностика** | Нативный netcheck с визуализацией задержки DERP-серверов, определением типа NAT и публичного IP. |

---

## 📸 Скриншоты

<details>
<summary><strong>Скриншоты Интерфейса</strong></summary>

<table width="100%">
  <tr>
    <td width="33%" align="center">
      <strong>Главный Дашборд</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/9366761f-f7de-4802-96ea-269d49bfffd3" />
    </td>
    <td width="33%" align="center">
      <strong>Переключатель Аккаунтов</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/b91dfc72-774c-4ad1-8eb0-77bd076ce1e9" />
    </td>
    <td width="33%" align="center">
      <strong>Список Узлов (Peers)</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/576774f6-8371-437b-b610-1555e1af12c0" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>Системные Логи</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/2056b039-201e-4f4a-b11f-5fdaaad38006" />
    </td>
    <td width="33%" align="center">
      <strong>Taildrop™ (Входящие)</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/7f92c77b-da1d-44d7-b082-5bca6c7f86ef" />
    </td>
    <td width="33%" align="center">
      <strong>Ресурсы Taildrive™</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/0985d06b-288f-4f08-b9ce-1919cbf91d59" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>Управление DNS</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/faea8000-94ae-4c55-b4be-8d577d5a5fa9" />
    </td>
    <td width="33%" align="center">
      <strong>Настройки Приложения</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/c4e59ea7-47e1-40c3-9d71-c35b0aa1d86a" />
    </td>
    <td width="33%" align="center">
      <strong>Настройки Профиля</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/38180ff1-fb2e-4aa4-8490-424696982f87" />
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <strong>Диагностика Сети</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/7b7c64d9-2a6f-4693-8b60-756159b7e96f" />
    </td>
    <td width="33%" align="center">
      <strong>Serve & Funnel</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/940bb4fe-da87-4d5c-a1df-4342b8d9ca03" />
    </td>
    <td width="33%" align="center">
      <strong>Отправка через Taildrop™</strong><br/>
      <img width="100%" src="https://github.com/user-attachments/assets/209669fb-803f-4e63-b0f2-3a13ac8d8840" />
    </td>
  </tr>
</table>

</details>

---

## 🏗️ Архитектура

TailSocks построен как гибридная многослойная система:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI (Kotlin)                  │
│  Dashboard · Peers · Logs · DNS · Netcheck · Serve · Settings   │
│  Admin API Console · Taildrive · Taildrop · TUN Config          │
├─────────────────────────────────────────────────────────────────┤
│                   JNI / Gomobile Bridge (appctr)                │
│  LocalAPI Client · DNS Proxy · IPN Bus · Netcheck · Taildrop    │
├─────────────────────────────────────────────────────────────────┤
│              Tailscale Daemon (libtailscale.so)                 │
│  tsnet · WireGuard · magicsock · DERP · Serve/Funnel · Drive    │
├───────────────────────┬─────────────────────────────────────────┤
│   Режим SOCKS5 Прокси │       Режим TUN VPN (опционально)       │
│  Маршрутизация через  │   Прозрачная системная маршрутизация    │
│  локальный SOCKS5     │   через нативную библиотеку C           │
│  без VpnService       │   hev-socks5-tunnel (полная/раздельная) │
└───────────────────────┴─────────────────────────────────────────┘
```

### Основные Компоненты

| Слой | Технология | Назначение |
|------|-----------|------------|
| **Демон** | Go → `libtailscale.so` (PIE) | Пропатченное ядро Tailscale, собранное с флагами оптимизации для отключения ненужных десктопных функций. Поддерживает `arm64`, `arm`, `x86`, `x86_64`. |
| **Мост** | Go → `appctr.aar` (Gomobile) | Высокоскоростной JNI-мост для вызовов LocalAPI, проксирования DNS, мониторинга шины IPN, netcheck, Taildrop и WebDAV Taildrive. |
| **Приложение** | Kotlin + Jetpack Compose | Пользовательский интерфейс Material 3, управление сервисом, интеграция с Android (SAF, Виджеты, Быстрые настройки, Поделиться). |
| **TUN Движок** | C → `hev-socks5-tunnel` | Опциональный прозрачный VPN-интерфейс. Перенаправляет трафик через SOCKS5-прокси на уровне ядра. Поддерживает исключения приложений и IP. |

### Ключевые Архитектурные Паттерны

- **Stateless Configuration:** Каждое обновление конфигурации явно. Serve/Funnel использует паттерн «Сброс-затем-Применение» (POST `{}` → POST новую конфигурацию) во избежание удержания устаревшего состояния.
- **Пассивное Управление Демоном:** Отсутствие агрессивных циклов опроса. Демон самостоятельно управляет своим жизненным циклом, синхронизацией политик и переподключением.
- **Изоляция Профилей:** Состояние в `files/states/{id}/`, настройки в `appctr_{id}`. Полный перезапуск демона при смене профиля.
- **Обертка DNS:** Разрешение MagicDNS из кэша узлов в памяти. Split DNS обернут как TCP-over-SOCKS5. Цепочка фолбэков: SOCKS5 UDP → Прямой UDP → DoH.
- **Защита от «стены 410»:** Обновления конфигурации блокируются во время активности URL авторизации (Login URL) для защиты сессий входа.

### Патчи Ядра (Upstream Patches)

TailSocks поддерживает 11 минимальных атомарных патчей в директории [`appctr/patches/`](appctr/patches/) для внедрения возможностей, недоступных через стандартный LocalAPI:

| Патч | Назначение |
|------|------------|
| `01-enable-socks-android` | Включение поддержки SOCKS5 в userspace-networking на Android |
| `02-socks5-auth` | Добавление имени пользователя и пароля в исходящий SOCKS5-слушатель |
| `03-taildrop-monolithic-fs` | Файловые операции `fsFileOps` на чистом Go во избежание паник JNI в Taildrop |
| `04-vip-services` | Добавление виртуальных сервисов (VIP) в `HostInfo` для видимости сервером координации |
| `05-localapi-cert` | Включение компиляции эндпоинта `/cert` на Android |
| `06-android-netmon` | Кастомный `netmon.InterfaceGetter` для ограничений `netlink` в Android 10+ |
| `07-taildrive-android` | Специфичные для Android адаптации Taildrive |
| `08-netstack-cgnat` | Исправление маршрутизации CGNAT для netstack |
| `09-netstack-loopback` | Loopback-маршрутизация для пакетов, адресованных самому себе в netstack |
| `10-taildrive-userspace-dial` | Маршрутизация WebDAV удаленных пиров через `tsdial.Dialer` |
| `11-noop-dns-fallback` | Внедрение переменной окружения DNS fallback во избежание SERVFAIL |

---

## 🚀 Быстрый Старт

### Скачать

Загрузите последний APK со страницы [Релизов](https://github.com/bropines/tailsocks/releases/latest) или используйте кнопки скачивания в начале данного README.

> **Поддерживаемые архитектуры:** `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`  
> **Минимальная версия Android:** 5.0 (API 21)

### Сборка из Исходников

<details>
<summary><strong>Инструкция по сборке</strong></summary>

**Требования:**
- Android NDK (установите переменную `ANDROID_NDK_HOME`)
- Go 1.23+
- `gomobile` (`go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`)
- Android SDK с Gradle

**1. Клонирование репозитория:**
```bash
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks
```

**2. Компиляция Go-ядра** (автоматически скачивает Tailscale v1.98.3, накладывает патчи и кросс-компилирует):
```bash
cd appctr
bash build.sh
cd ..
```

**3. Сборка APK:**
```bash
./gradlew app:assembleRelease
```

> Скрипт сборки автоматически скачивает нужную версию Tailscale, применяет все патчи и компилирует PIE-бинарники для 4 архитектур.

</details>

---

## 📚 Документация

| Документ | Описание |
|----------|----------|
| [Архитектура системы](docs/ARCHITECTURE_RU.md) | Подробный разбор обертки DNS, изоляции аккаунтов, netcheck и патчей |
| [Инструкция по сборке](docs/BUILDING_RU.md) | Настройка NDK, компиляция Go-ядра и конвейер динамических патчей |
| [Ретроспектива проекта](docs/RETROSPECTIVE_RU.md) | Эволюция архитектуры от PoC до текущей версии |
| [Настройка AdGuard](docs/ADGUARD_RU.md) | Сосуществование с системными блокировщиками рекламы |
| [Руководство по Serve & Funnel](docs/SERVE_FUNNEL_GUIDE_RU.md) | Проброс локальных портов и виртуальных сервисов |
| [Root-Интеграция и Системная Служба](docs/ROOT_RU.md) | Настройка автозапуска в режиме root, service.d и CLI-обёртки |
| [Руководство по автосценариям Tasker](docs/AUTOMATION_RU.md) | Интеграция Intents для Tasker, MacroDroid, Automate и ADB |
| [Планы разработки (Roadmap)](docs/ROADMAP_RU.md) | Запланированные функции и ближайшие цели |
| [История изменений](CHANGELOG.md) | Полный журнал версий |

---

## 🌐 Обход Блокировок и DPI (ByeDPI)

Для пользователей из регионов с ограничениями (где `controlplane.tailscale.com` блокируется или сбрасывается), TailSocks предлагает встроенный механизм обхода блокировок сервера координации:

### Обход DPI управляющего сервера (ByeDPI JNI)
TailSocks включает нативную JNI-реализацию [ByeDPI](https://github.com/hufyhang/byedpi) непосредственно внутри процесса приложения. Это позволяет обходить аналитическую проверку пакетов по SNI (DPI) без запуска внешних бинарных процессов.
* **Безопасность:** ByeDPI при каждом запуске привязывается к случайному loopback IP (например, `127.182.201.43`) и случайному порту в подсети `127.0.0.0/8`. Это защищает прокси от обнаружения другими приложениями через сканирование портов.
* **Использование:** Включите **Обход DPI (ByeDPI)** в Настройки -> Вкладка Сеть -> Прокси управляющего сервера и настройте кастомные флаги ByeDPI (по умолчанию: `-s 1 -d split -r`).

---

## ⚡ Интеграция с Tasker и Автосценариями

TailSocks поддерживает фоновое управление через **Android Broadcast Intents**. Вы можете автоматизировать подключение с помощью Tasker, MacroDroid, Automate или `adb`.

* **Целевой Receiver:** `io.github.bropines.tailscaled/.core.TaskerReceiver` (или пакет `io.github.bropines.tailscaled`)
* **Поддерживаемые действия (Actions):**
  * `io.github.bropines.tailscaled.action.CONNECT` (или `io.github.bropines.tailscaled.START`) — Запуск подключения
  * `io.github.bropines.tailscaled.action.DISCONNECT` (или `io.github.bropines.tailscaled.STOP`) — Остановка подключения
  * `io.github.bropines.tailscaled.action.TOGGLE` (или `io.github.bropines.tailscaled.TOGGLE`) — Переключение состояния
  * `io.github.bropines.tailscaled.action.RESTART` (или `io.github.bropines.tailscaled.RESTART`) — Перезапуск подключения

#### Пример настройки в Tasker:
1. Действие: **Система** → **Отправить Intent**
2. Action: `io.github.bropines.tailscaled.action.CONNECT`
3. Категория: **Broadcast Receiver**
4. Пакет: `io.github.bropines.tailscaled`

---

## 🤝 Благодарности

| | |
|-|-|
| **Приложение и Патчи** | [Bropines](https://github.com/bropines) — разработка приложения, архитектура и большинство патчей ядра |
| **Первичные Android-патчи** | [Asutorufa](https://github.com/Asutorufa) — оригинальные [патчи](https://github.com/Asutorufa/tailscale) сети (`anet`) и мониторинга (`netmon`), послужившие отправной точкой |
| **Обход DPI** | [hufyhang/byedpi](https://github.com/hufyhang/byedpi) — утилита обхода DPI через локальный HTTP/SOCKS5 прокси |
| **TUN Движок** | [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — нативная библиотека трансляции SOCKS5 в TUN-интерфейс |
| **Ядро Tailscale** | [Tailscale Inc.](https://github.com/tailscale/tailscale) — сетевой движок в пользовательском пространстве (`tsnet`) |
| **ИИ Помощник** | [Google Gemini](https://gemini.google.com/) — разработка интерфейса, исследование LocalAPI и проектирование патчей |

---

## 📜 Лицензия

Распространяется под лицензией **BSD 3-Clause License**. Смотрите [`LICENSE`](LICENSE) для подробностей.

*Tailscale, Taildrop, Taildrive, MagicDNS и Funnel являются товарными знаками Tailscale Inc. Этот проект является независимой разработкой с открытым исходным кодом и не связан с Tailscale Inc.*
