# Handoff: TailSocks backlog

Counterpart of [`HANDOFF_4.0_NATIVE_TUN.md`](HANDOFF_4.0_NATIVE_TUN.md): that
one is a single large effort, this one is a list of **independent** tasks. Pick
one, finish it, tick it off here. Nothing below depends on anything else below
unless the task says so.

Written for a coding agent starting with an empty context. This file is the
index: ground rules, devices, and every task in one line. The detail for a task
— what is true today with `path:line` evidence, steps, acceptance criteria and
a test procedure — lives in the topic file linked from its section. Read this
file, then only the topic file you are working on.

Line numbers were correct at commit `22a49ae`. If a reference no longer
matches, trust the named function or constant and re-locate it rather than
assuming the claim is wrong.

## 0. Ground rules (non-negotiable)

- **Never `git push`.** Commit locally as much as you like; the author
  publishes himself. Do not rebase or rewrite history.
- **Every Go change needs `appctr/build.sh` before an APK means anything.**
  Gradle only packages the prebuilt `appctr/tmp/appctr.aar` and the daemon
  binaries in `app/src/main/jniLibs/`; `verifyGoBridgeFresh` fails a release
  build when the AAR is older than any `appctr/*.go` or patch:
  ```bash
  cd appctr && ANDROID_HOME=$HOME/android-sdk ANDROID_NDK_HOME=$HOME/android-sdk/ndk/28.2.13676358 \
    GOTOOLCHAIN=auto PATH="$PATH:$HOME/go/bin" ./build.sh
  ```
- **Daemon changes are patches, not edits.** `appctr/tailscale_src/` is
  upstream Tailscale v1.102.1 plus `appctr/patches/*.patch`. Edit the file
  under `tailscale_src/`, then regenerate the patch
  (`appctr/patches/recreate_patches.sh`; add new diff targets there). A patch
  must apply to pristine sources with `patch -p1 --batch --forward -F0`. New Go
  files carry `//go:build android` where they are Android-only.
- **Release builds** need `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`; the build refuses the debug key. `assembleDebug` is enough
  for local testing.
- **Log behaviour changes** at the top of `CHANGELOG.md`, section
  `[4.0.0] - Unreleased`, in the voice of the entries already there.
- **Proxy mode, TUN mode and Root Mode must keep working.** Root Mode's
  exit-node routing (table 52, `SO_MARK 0x2000000`, the rule at pref 200) was
  finished and verified on 2026-09-05 — commit `180f951`, `docs/ROOT.md` §4.
  Do not redesign it as a side effect of something else.

## 1. Test devices

| device | address | notes |
| --- | --- | --- |
| WSA | `127.0.0.1:58526` | Android 13, x86_64, Magisk root. Main root target; the author restarts it from Windows when it dies. |
| Redmi | `192.168.1.83:5555` | Android 16, APatch root (`/data/adb/ap/bin/magiskpolicy`). Second root target. |
| POCO | `192.168.1.70:5555` | Android 16 / HyperOS, **no root**, the author's daily phone. Non-intrusive checks only; ask before reinstalling. |

Non-exported components cannot be started from a plain `adb shell` on release
builds. On a rooted device use

```bash
su -c 'am start-foreground-service -n io.github.bropines.tailscaled/.core.TailscaledService -a START_ACTION'
```

(`STOP_ACTION` stops it); otherwise drive the UI with `uiautomator dump` +
`input tap`. Never `adb uninstall` without asking. On WSA `uiautomator dump`
returns "null root node" whenever the Windows-side window is unfocused, so
prefer the root shell there. HyperOS denies the app's autostart op, so on the
POCO the package-replaced broadcast is never delivered, so an update leaves the
service down until the watchdog alarm fires or the app is opened; that is known
and handled, not something you just broke.

## 2. How to work through this

1. Pick a task from the tables below. Effort is `S` (under an hour), `M` (half
   a day), `L` (a day or more).
2. Open the topic file and read that task's section in full, plus the topic's
   "What is true today".
3. Do the work, run the task's own test procedure, and log the change in
   `CHANGELOG.md`.
4. Commit locally and tick the task off in the topic file (`- [x]`), noting the
   commit.
5. If a task needs a decision listed under "needs the author's decision", stop
   and ask instead of guessing.

## 3. Tasks

### A. Root Mode: finish device verification

Root Mode exit-node routing (table 52 plus a pref-200 catch-all gated on the daemon's
SO_MARK line) was proven only for a MANUAL start on WSA, in commit 180f951.

Detail: [`docs/handoff/backlog-a-root-devices.md`](handoff/backlog-a-root-devices.md)

| task | effort | what |
| --- | --- | --- |
| **A1** | S | Land and prove the attach-path Context fix (already in the working tree) |
| **A2** | M | WSA (Magisk, x86_64): verify reboot autostart via service.d and the app's attach (force_bg on and off) |
| **A3** | L | Redmi (Android 16, APatch): preflight, root detection, SELinux/socket access, SO_MARK, then reboot autostart |
| **A4** | S | Bring tools/root-debug.sh and the session driver up to the 4.0 layout |
| **A5** | M | DECISION: make the boot-started daemon equivalent to the app-started one (SOCKS5/HTTP listeners, TUN mode) |
| **A6** | S | DECISION: align the boot script's file modes with the app (socket 666, state dir 700, log 644) |
| **A7** | S | CONDITIONAL (after the Redmi preflight): APatch sepolicy — absolute magiskpolicy path and the daemon's real domain |
| **A8** | S | CONDITIONAL (after the Redmi reboot): wait for user-0 CE storage in the boot script on FBE phones |
| **A9** | S | CONDITIONAL (after variant A on either device): do not kill a daemon that is still starting when the boot script is installed |
| **A10** | S | OPTIONAL: version header in the installed script and an "outdated" status in Settings |

8 open question(s) for the author are listed at the end of the topic file; the tasks
that depend on them say so.

### B. Make the tailscale CLI binary optional

Every TailSocks APK ships a second Go program, the `tailscale` CLI
(libtailscale_cli.so), next to the daemon.

Detail: [`docs/handoff/backlog-b-cli-binary.md`](handoff/backlog-b-cli-binary.md)

| task | effort | what |
| --- | --- | --- |
| **B1** | S | Runtime presence check and graceful degradation when libtailscale_cli.so is absent (prerequisite for options b, c, d; harmless under a) |
| **B2** | M | Move the in-app CLI uses to LocalAPI so the CLI becomes a Root-Mode-only feature |
| **B3** | M | Option (c)/(d): `lite` (no CLI) and `full` variants via a Gradle product flavor, with CI naming and updater awareness |
| **B4** | L | Option (b): download the CLI on demand from the app's own GitHub release (only if the author picks it) |
| **B5** | S | Documentation and changelog for the chosen option |

6 open question(s) for the author are listed at the end of the topic file; the tasks
that depend on them say so.

## 4. Where the rest of the context is

- `agents.md` — architecture mandate, networking and root rules, UI standards.
- `CHANGELOG.md` — everything already done for 4.0.0, top section.
- `docs/ROOT.md`, `docs/ROOT_RU.md` — Root Mode design, §4 covers the routing.
- `docs/BUILDING.md` — build pipeline and signing.
- `docs/research/` — design JSON for the native-TUN and root exit-node work.
- `git log --oneline -40` — recent work; do not re-derive decisions it records.
