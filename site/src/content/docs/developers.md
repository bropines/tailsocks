---
title: For developers
description: Building, the architecture, translating and releasing.
---

The source is at [github.com/bropines/tailsocks](https://github.com/bropines/tailsocks), under the BSD-3-Clause license. Rules for a first pull request: [CONTRIBUTING.md](https://github.com/bropines/tailsocks/blob/main/CONTRIBUTING.md).

## Building

You need the Android SDK and NDK (`ANDROID_NDK_HOME`), Go — the build fetches the exact toolchain itself — and `gomobile`.

```sh
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks

# The Go core: downloads the pinned Tailscale source, applies the patches,
# builds libtailscale.so for four ABIs.
(cd appctr && bash build.sh)

# Debug build, installs next to the release app as *.dev
./gradlew app:assembleDebug
```

- Run `appctr/build.sh` again after any Go or patch change, or the APK does not contain it.
- A release build needs your own keystore and refuses the debug key.

Details: [BUILDING.md](https://github.com/bropines/tailsocks/blob/main/docs/BUILDING.md).

## Architecture

Three layers:

- **The daemon** — Tailscale's `tailscaled`, patched (`appctr/patches/`) and built as `libtailscale.so`. The same file is the `tailscale` CLI.
- **The bridge** — Go, through gomobile (`appctr`): LocalAPI over the daemon's Unix socket, the DNS proxy, network check, Taildrop and Taildrive.
- **The app** — Kotlin and Jetpack Compose, Material 3.

The daemon runs in userspace networking by default and serves SOCKS5 and HTTP proxies. In VPN mode it runs the Android VPN interface itself (`--tun=android-vpn`), with `hev-socks5-tunnel` as the compatibility engine. Each account has its own state directory and preferences; switching restarts the daemon.

Details: [ARCHITECTURE.md](https://github.com/bropines/tailsocks/blob/main/docs/ARCHITECTURE.md) and the [Root guide](https://github.com/bropines/tailsocks/blob/main/docs/ROOT.md).

## Translating

The app is translated on [Hosted Weblate](https://hosted.weblate.org/engage/tailsocks/), in the browser. English is the source, Russian is kept alongside it, every other language comes from Weblate as pull requests.

- Keep placeholders exactly: `%1$s`, `%2$d`, `\n`.
- Keep it short: explanations on screen are folded to one or two lines.
- Tailscale and its product names stay as they are.

Adding strings in code: English to `values/`, Russian to `values-ru/`. More in [TRANSLATING.md](https://github.com/bropines/tailsocks/blob/main/docs/TRANSLATING.md).

## Releasing

[RELEASING.md](https://github.com/bropines/tailsocks/blob/main/docs/RELEASING.md). The changes: [CHANGELOG.md](https://github.com/bropines/tailsocks/blob/main/CHANGELOG.md).
