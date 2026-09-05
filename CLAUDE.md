# TailSocks — instructions for Claude Code sessions

This file is loaded automatically at the start of every Claude Code session in
this repository. It is deliberately short; the substance lives in the files it
points to.

1. Read [`agents.md`](agents.md) first — architecture mandate, networking and
   root-mode rules, build pipeline, UI standards, and the **working
   agreements** (no `git push` without the author's explicit «пушь»; run
   `appctr/build.sh` after any Go change before building an APK; daemon changes
   are patches regenerated from pristine upstream files).
2. Unreleased work is described at the top of [`CHANGELOG.md`](CHANGELOG.md)
   (section `[4.0.0] - Unreleased`). Extend it in the same style when you change
   behaviour.
3. Open handoffs — self-contained briefs meant to be picked up by a fresh
   session or another model — live in `docs/HANDOFF_*.md`. Current:
   [`docs/HANDOFF_4.0_NATIVE_TUN.md`](docs/HANDOFF_4.0_NATIVE_TUN.md).
   Update or delete a handoff when you finish or abandon it.
4. Build: `docs/BUILDING.md`. Release builds need `KEYSTORE_FILE`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (keystore `tailsocks.jks`
   at the repo root); the build refuses the debug key and refuses a stale
   `appctr/tmp/appctr.aar`.
5. Devices arrive over `adb connect` from the author (Russian-speaking; answer
   in Russian). Non-exported components cannot be started from `adb shell` on
   release builds — drive the UI with `uiautomator dump` + `input tap`, or use a
   root shell on a rooted device / WSA. Never `adb uninstall` without asking.
