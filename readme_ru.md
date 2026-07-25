<p align="center">
  <img src="docs/logo.svg" alt="TailSocks Icon" width="128" height="128" />
</p>

<h1 align="center">TailSocks</h1>

<p align="center">
  <strong>Продвинутый Android-клиент для Tailscale с сетей в пользовательском пространстве (Userspace) и прозрачным TUN VPN</strong>
</p>

<p align="center">
  <a href="readme.md">English</a> | <strong>Русский</strong>
</p>

<p align="center">
  <a href="https://github.com/bropines/tailsocks/releases/latest"><img src="https://img.shields.io/github/v/release/bropines/tailsocks?style=for-the-badge&logo=github&logoColor=white&label=Latest%20Release&color=2ea44f" alt="Latest Release" /></a>
  <a href="https://github.com/bropines/tailsocks/releases"><img src="https://img.shields.io/github/downloads/bropines/tailsocks/total?style=for-the-badge&logo=android&logoColor=white&label=Downloads&color=3ddc84" alt="Downloads" /></a>
  <a href="https://github.com/tailscale/tailscale/releases/tag/v1.98.3"><img src="https://img.shields.io/badge/Tailscale_Core-v1.98.3-blue?style=for-the-badge&logo=tailscale&logoColor=white" alt="Tailscale Core" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-BSD_3--Clause-orange?style=for-the-badge" alt="License" /></a>
</p>

<p align="center">
  <a href="https://github.com/bropines/tailsocks/releases/latest/download/app-release.apk">
    <img src="https://img.shields.io/badge/⬇_Скачать_APK-Release-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Скачать Release APK" />
  </a>
  &nbsp;
  <a href="https://github.com/bropines/tailsocks/releases">
    <img src="https://img.shields.io/badge/⬇_Все_Релизы-GitHub-24292e?style=for-the-badge&logo=github&logoColor=white" alt="Все Релизы" />
  </a>
</p>

---

TailSocks — это высокопроизводительный клиент Android для [Tailscale](https://tailscale.com/), работающий в режиме **пользовательской сети (userspace-networking)** через `tsnet`. Приложение предоставляет полный стек возможностей Tailscale — включая [Taildrop™](https://tailscale.com/kb/1106/taildrop), [Exit Nodes](https://tailscale.com/kb/1103/exit-nodes), [Serve & Funnel](https://tailscale.com/kb/1242/tailscale-serve) и [Taildrive™](https://tailscale.com/kb/1369/taildrive) — без необходимости запрашивать системное разрешение Android `VpnService`. Это позволяет параллельно использовать другие VPN и фаерволы на устройстве.

Опционально TailSocks поддерживает режим **прозрачного системного TUN VPN** на базе нативного движка [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), обеспечивая полную или разделяемую маршрутизацию трафика.

---

## ✨ Возможности

### Сеть и Подключение

| Функция | Описание |
|---------|----------|
| **Нативный LocalAPI** | Управление демоном 100% без CLI через Unix-сокет (`tailscaled.sock`) по протоколу LocalAPI v0. |
| **SOCKS5 Прокси** | Встроенный локальный сервер SOCKS5 с опциональной авторизацией для маршрутизации конкретных приложений. |
| **Прокси управляющего сервера** | Маршрутизация трафика к управляющему серверу через кастомный SOCKS5/HTTP прокси для заблокированных регионов. |
| **Режим TUN VPN** | Прозрачный системный VPN через `hev-socks5-tunnel` — полный и раздельный туннель, исключение приложений, кастомный IP шлюза. |
| **[Exit Nodes](https://tailscale.com/kb/1103/exit-nodes) ©** | Маршрутизация интернет-трафика через любой узел сети Tailscale с автовосстановлением и доступом к локальной сети. |
| **[MagicDNS](https://tailscale.com/kb/1081/magicdns) ©** | Разрешение имен узлов в памяти (0мс), Split DNS через SOCKS5 TCP, DoH фолбэк. |
| **Обход NAT** | Мониторинг подключения `InMagicSock` в реальном времени. Диагностика STUN/DERP через нативный netcheck. |

### Сервисы и Обмен Файлами

| Функция | Описание |
|---------|----------|
| **[Tailscale Serve & Funnel](https://tailscale.com/kb/1242/tailscale-serve) ©** | Проброс локальных портов в Tailnet или публичный интернет. Режимы TCP и HTTPS, экспорт TLS-сертификатов. |
| **[Виртуальные Сервисы (`svc:`)](https://tailscale.com/kb/1438/virtual-ip) ©** | Создание именованных виртуальных сервисов с выделенными VIP и DNS-именами прямо из нативного UI. |
| **[Taildrop™](https://tailscale.com/kb/1106/taildrop) ©** | Отправка и получение файлов между устройствами Tailnet. Хаб входящих, интеграция с системным меню «Поделиться», DocumentsProvider. |
| **[Taildrive™](https://tailscale.com/kb/1369/taildrive) ©** | Шаринг локальных папок по WebDAV. Интеграция с SAF, монтирование удаленных ресурсов, SOCKS5-проксированный доступ. |

### Управление и Администрирование

| Функция | Описание |
|---------|----------|
| **Изоляция Аккаунтов** | Строгое разделение данных каждого профиля — независимые каталоги состояния, настройки, ключи и папки Taildrop. |
| **Tailscale Admin API** | Интеграция с `api.tailscale.com/v2` — управление устройствами, DNS, пользователями, сервисами, вебхуками и логами. |
| **Биометрическая Защита** | Защита консоли администрирования по отпечатку пальца или Face ID. |
| **Ключи Авторизации** | Генерация, просмотр и отзыв Auth Keys прямо из приложения. |
| **Резервное Копирование** | Полный зашифрованный бэкап состояния приложения (ZIP) и экспорт отдельных аккаунтов (JSON). |

---

## 🛠️ Сборка и Документация

- 📖 **[Инструкция по сборке](docs/BUILDING_RU.md)** — Сборка Go-ядра и Android APK.
- 🏗️ **[Архитектура системы](docs/ARCHITECTURE_RU.md)** — Подробный разбор слоёв гибридной системы (Go, JNI, Kotlin, C TUN).
- ⚡ **[Руководство по автоматизации](docs/AUTOMATION_RU.md)** — Интеграция с Tasker, MacroDroid и Intent API.
- 🌐 **[Руководство по Serve & Funnel](docs/SERVE_FUNNEL_GUIDE_RU.md)** — Настройка публичного и локального проброса портов.

---

## 📜 Лицензия

TailSocks распространяется под лицензией **BSD 3-Clause License**. Исходное ядро Tailscale лицензировано под BSD 3-Clause.
