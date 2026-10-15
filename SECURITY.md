# Security policy / Политика безопасности

English first, русский ниже.

## Reporting a vulnerability

Report it **privately** through GitHub: [Report a vulnerability](https://github.com/bropines/tailsocks/security/advisories/new). Please do not open a public issue or discussion, and do not post exploit details.

Say which version (About), which mode, and how to reproduce it. TailSocks has one maintainer: an answer may take a while, but reports are read.

**In scope**

- the app: its automation receiver and `tailsocks://` links, backups, the admin console lock;
- the Go bridge (`appctr/`) and our patches to the Tailscale daemon (`appctr/patches/`);
- remote management: pairing, owner and guest roles.

**Out of scope**

- unpatched upstream Tailscale and its servers: report those to Tailscale (security@tailscale.com);
- Headscale, ByeDPI and other third-party code: report those upstream;
- a rooted or already compromised device, or an unlocked phone in someone's hands.

Only the latest release is supported.

---

## Сообщение об уязвимости

Сообщите **приватно** через GitHub: [Report a vulnerability](https://github.com/bropines/tailsocks/security/advisories/new). Пожалуйста, не создавайте публичные issue или обсуждения и не публикуйте подробности эксплойта.

Укажите версию (в «О программе»), режим и как воспроизвести. Мейнтейнер один: ответ может занять время, но сообщения читаются.

**В области действия**

- приложение: приёмник автоматизации и ссылки `tailsocks://`, резервные копии, блокировка консоли администратора;
- Go-мост (`appctr/`) и наши патчи к демону Tailscale (`appctr/patches/`);
- удалённое управление: сопряжение, роли владельца и гостя.

**Вне области действия**

- оригинальный Tailscale без наших патчей и его серверы: сообщайте в Tailscale (security@tailscale.com);
- Headscale, ByeDPI и другой сторонний код: сообщайте их авторам;
- устройство с root или уже скомпрометированное, или разблокированный телефон в чужих руках.

Поддерживается только последний выпуск.
