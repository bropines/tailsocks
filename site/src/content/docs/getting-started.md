---
title: Getting started
description: Install TailSocks on a phone or an Android TV, sign in, and add more accounts.
---

## Install

- Download the APK from [GitHub Releases](https://github.com/bropines/tailsocks/releases/latest). The `universal` APK fits every device; the `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` ones are smaller.
- Or add the repository to [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/bropines/tailsocks), which keeps it updated.
- Android 7.0 or newer.
- **Android TV** takes the same APK. The TV launcher shows TailSocks with a banner — see [Android TV](/tailsocks/android-tv/).

Betas of the next version come out as GitHub pre-releases. The app offers them with **Receive beta versions** on (Settings → Appearance & language).

## First start

A short introduction runs once. Everything in it can be changed later in Settings.

1. **Welcome to TailSocks** — the language.
2. **How it works** — **Proxy Mode** or **VPN (TUN) Mode**; Root Mode on rooted devices. See [Connection modes](/tailsocks/connection-modes/).
3. **Coordination Server** — leave it empty for Tailscale, or enter your Headscale address.
4. **Censorship Bypass** — where Tailscale is blocked. See [Blocked networks](/tailsocks/censorship/).
5. **Login & Permissions** — an optional auth key, and **Report the real OS**.
6. **Permissions & Services** — notifications and battery optimisation.

**Report the real OS** registers the node as an Android device instead of a Linux client. Decide before the first sign-in: changing it later means a new profile.

## Sign in

Tap the status card to start. When the node needs signing in, the main screen shows **Authentication Required**:

- **Log In** — the login page in a browser tab over the app.
- **Use a key** — paste an auth key (`tskey-auth-…`) and tap **Submit Key**.
- **Sign in on another device** — the login link as a QR code, with Share and Copy. Open it on any device with a browser.

**Headscale or another login server:** set **Login Server (Headscale)** in Settings → Account & connection, or in the **Coordination Server** step, or when adding an account.

### On a TV

- The TV opens **Sign in from your phone** by itself: a QR code of the login link, and the link as text. Sign in on the phone; the TV follows.
- **Use a key from a phone** shows a QR code instead. On the phone, tap **Scan QR code** in the main screen's top bar, scan it, enter the key in **Send a key to this TV**, tap **Send**.
- The key goes over the local network, or through a relay that cannot read it. Both screens show a **Check code**; compare it, then **Confirm** on the TV.

## Several accounts

Tap the account name under the title on the main screen: **Accounts** opens.

- **Add account** — a name, an optional **Login Server (Headscale)** and auth key, and **Report the real OS**.
- Each account is its own node, with its own keys, settings and Taildrop files. Switching restarts the connection (**Restart & Switch**).
- **The main account** cannot be deleted. In **Edit** mode the star marks it; **Make main** moves the star. Deleting the account in use moves to the main one.
