---
title: Разработчикам
description: Сборка, архитектура, перевод и выпуск релизов.
---

Исходники — на [github.com/bropines/tailsocks](https://github.com/bropines/tailsocks), под лицензией BSD-3-Clause. Правила для первого pull request — в [CONTRIBUTING_RU.md](https://github.com/bropines/tailsocks/blob/main/CONTRIBUTING_RU.md).

## Сборка

Нужны Android SDK и NDK (`ANDROID_NDK_HOME`), Go — нужный тулчейн сборка скачает сама — и `gomobile`.

```sh
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks

# Go-ядро: скачивает закреплённые исходники Tailscale, накладывает патчи,
# собирает libtailscale.so под четыре ABI.
(cd appctr && bash build.sh)

# Debug-сборка, ставится рядом с релизной как *.dev
./gradlew app:assembleDebug
```

- После любой правки Go или патчей снова запустите `appctr/build.sh`, иначе в APK её не будет.
- Release-сборке нужен собственный keystore, debug-ключом она подписывать отказывается.

Подробно — в [BUILDING_RU.md](https://github.com/bropines/tailsocks/blob/main/docs/BUILDING_RU.md).

## Архитектура

Три слоя:

- **Демон** — `tailscaled` от Tailscale с патчами (`appctr/patches/`), собранный как `libtailscale.so`. Этот же файл — CLI `tailscale`.
- **Мост** — Go через gomobile (`appctr`): LocalAPI через Unix-сокет демона, DNS-прокси, проверка сети, Taildrop и Taildrive.
- **Приложение** — Kotlin и Jetpack Compose, Material 3.

По умолчанию демон работает в userspace networking и отдаёт SOCKS5- и HTTP-прокси. В режиме VPN он сам ведёт VPN-интерфейс Android (`--tun=android-vpn`), а `hev-socks5-tunnel` остаётся движком для совместимости. У каждого аккаунта свой каталог состояния и свои настройки; переключение перезапускает демона.

Подробно — в [ARCHITECTURE_RU.md](https://github.com/bropines/tailsocks/blob/main/docs/ARCHITECTURE_RU.md) и [руководстве по Root](https://github.com/bropines/tailsocks/blob/main/docs/ROOT_RU.md).

## Перевод

Приложение переводится на [Hosted Weblate](https://hosted.weblate.org/engage/tailsocks/), прямо в браузере. Исходный язык — английский, русский ведётся вместе с ним, остальные языки приходят с Weblate пулл-реквестами.

- Плейсхолдеры оставляйте как есть: `%1$s`, `%2$d`, `\n`.
- Пишите коротко: пояснения на экране свёрнуты до одной-двух строк.
- Tailscale и названия его продуктов не переводятся.

Новые строки в коде: английский — в `values/`, русский — в `values-ru/`. Подробнее — в [TRANSLATING_RU.md](https://github.com/bropines/tailsocks/blob/main/docs/TRANSLATING_RU.md).

## Релизы

[RELEASING_RU.md](https://github.com/bropines/tailsocks/blob/main/docs/RELEASING_RU.md). История изменений — [CHANGELOG.md](https://github.com/bropines/tailsocks/blob/main/CHANGELOG.md).
