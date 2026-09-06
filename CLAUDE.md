# TailSocks — instructions for Claude Code sessions

Loaded automatically at the start of every session in this repository. It is a
pointer, nothing more; the substance lives in the files it names.

1. **Read [`agents.md`](agents.md) first.** It is the project mandate:
   architecture, networking and Root Mode rules, the build and patch pipeline,
   UI standards, and the working agreements — including *never* `git push`
   without the author's explicit «пушь».
2. Unreleased work is at the top of [`CHANGELOG.md`](CHANGELOG.md). Extend that
   section in its own style whenever you change behaviour.
3. Plans and leftovers: [`docs/ROADMAP.md`](docs/ROADMAP.md). The one large
   effort ahead is [`docs/NATIVE_TUN_PLAN.md`](docs/NATIVE_TUN_PLAN.md) — TUN
   rebuilt around a tailscaled-owned `VpnService` fd, targeted at 4.1. It is a
   plan; shipped TUN mode still runs on `hev-socks5-tunnel`.
4. Building: [`docs/BUILDING.md`](docs/BUILDING.md). Run `appctr/build.sh` after
   any Go or patch change, or the APK will not contain it.
5. Devices arrive over `adb connect` from the author, who writes in Russian —
   answer in Russian. Never `adb uninstall` without asking.
