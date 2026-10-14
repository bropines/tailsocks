---
title: Android TV
description: The same APK on a television, driven by a remote.
---

:::caution
Walked on emulators, not yet on a real television. Reports are welcome in [Issues](https://github.com/bropines/tailsocks/issues).
:::

Install the same APK as on a phone. The TV launcher shows TailSocks with a banner.

## What works

- The whole app on a remote: a frame around what is in focus, a margin from the screen's edges.
- Proxy and VPN (TUN) modes, exit nodes, MagicDNS.
- Taildrop: files are picked in the app; received ones go to `Download/Taildrop` and an arrival is announced on screen.
- Taildrive: shares are added with the built-in folder browser; the proxy address shows as a QR code.
- Where a TV has no file picker or browser, the app uses its own file browser, and shows a link as its address with a QR code.

## Signing in

No keyboard needed:

- **Sign in from your phone** opens by itself: scan the QR code with a phone and sign in there.
- **Use a key from a phone**: send an auth key from TailSocks on a phone, confirmed by a check code.

Step by step: [Getting started → On a TV](/tailsocks/getting-started/#on-a-tv).

## What is hidden or moved

Hidden: the admin console, the QR scanner, signing nodes into Tailnet Lock, settings search, copy and paste, battery optimisation prompts, signing in through a browser.

Moved to Settings → **Advanced**: theme and icon, Root mode, backups, diagnostics, logs, console, DNS, Serve, automation.

## The remote from a phone

The home screen's **From a phone** tile opens [remote management](/tailsocks/remote-management/): pair a phone once, and change the TV's settings from it.

## Interface

Settings → Appearance & language → **Interface**: **Automatic** picks the TV interface on a television. **TV** turns it on anywhere; **Phone & tablet** turns it off on a television.
