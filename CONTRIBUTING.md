# Contributing to TailSocks

Read this once before your first patch. It is short, and everything in it is
specific to this repository.

## 1. What you are working on

TailSocks is an Android client for Tailscale that runs the real `tailscaled` in
userspace, so the app works without Android's VPN permission at all. The UI is
Kotlin/Compose, the bridge between the app and the daemon is a Gomobile module
(`appctr/`), and the daemon itself is upstream Tailscale plus a handful of local
patches. Optional TUN and Root modes are built on top of that core, not instead
of it.

**The one rule that explains most of the codebase: the Go daemon is a separate
process, not a library inside the app.** `libtailscale.so` and
`libtailscale_cli.so` are position-independent executables (PIE) that the app
`fork/exec`s from `jniLibs`; the app then talks to the running process over the
unix socket `files/tailscaled.sock` using LocalAPI v0. Daemon management never
wraps the CLI binary, and the Kotlin side never links the daemon in. If you find
yourself wanting to call a Go function from Kotlin directly, the answer is
almost always a LocalAPI call or a new method on the `appctr` bridge — not a new
linkage.

## 2. Building

Two stages, in this order. Gradle only *packages* the Go and C artifacts; it
does not build them.

```bash
git clone --recurse-submodules https://github.com/bropines/tailsocks.git
cd tailsocks

# Stage 1 — the Go core and the JNI bridge (needs ANDROID_NDK_HOME)
cd appctr && bash build.sh && cd ..

# Stage 2 — the APK
./gradlew app:assembleDebug
```

`appctr/build.sh` downloads the Tailscale version pinned in
`appctr/TAILSCALE_VERSION` (currently `v1.102.1`), applies every patch in
`appctr/patches/`, cross-compiles the two PIE binaries for `arm64-v8a`,
`armeabi-v7a`, `x86` and `x86_64` into `app/src/main/jniLibs/`, and builds
`appctr/tmp/appctr.aar`.

**A Go change without stage 1 produces an APK that does not contain it.** The
`.so` files and the `.aar` are prebuilt inputs; Gradle would happily package
yesterday's ones. `verifyGoBridgeFresh` (wired into `preBuild`) compares the
mtime of `appctr/tmp/appctr.aar` against every `appctr/*.go` and every file in
`appctr/patches/`: a release build **fails** when the bridge is older, a debug
build only warns. Treat the warning as an error — a whole day of Go fixes once
shipped in an APK that did not contain them, which is why the check exists.

Release builds need a real keystore and all four variables; since 4.0.0 the
build refuses to fall back to the debug key (a release APK signed with a
throwaway key can never be updated by a properly signed one — Android rejects
the certificate change, and the only way out is uninstalling and losing the app
state):

```bash
KEYSTORE_FILE="$PWD/tailsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease
```

ABI splits are on, so output is a universal APK plus one per ABI under
`app/build/outputs/apk/<debug|release>/`.

Do not commit generated or private inputs: the `.so` binaries, `appctr/tmp/`,
`appctr/tailscale_src/`, `appctr/orig/` and keystores are all git-ignored, and
should stay that way. UI text is maintained by hand in this repository: add
the string to `app/src/main/res/values/strings.xml` **and** its translation to
`app/src/main/res/values-ru/strings.xml` in the same commit. Nothing regenerates
the Russian locale — `.github/crowdin.yml` exists but no workflow uses it — and
an untranslated Russian UI is treated as a defect, not as pending work.

## 3. Daemon changes are patches, not edits

`appctr/tailscale_src/` is **generated** and git-ignored: pristine upstream
Tailscale with `appctr/patches/*.patch` applied in alphabetical order
(`01-enable-socks-android` … `16-android-somark`, sixteen today).
`appctr/orig/` holds the pristine copy the patches are diffed against. The
source of truth is the patch files; the tree is scratch space.

Working loop:

1. Read the upstream file first. Design your change around the pattern Tailscale
   already uses — the pristine copy is right there in `appctr/orig/`.
2. Edit under `appctr/tailscale_src/`, build, iterate.
3. Regenerate the patch set: `./appctr/patches/recreate_patches.sh`. It deletes
   `patches/*.patch` and re-diffs `tailscale_src` against `orig` — per file, or
   per directory for the taildrop/taildrive/net stanzas.
4. Verify from scratch: `cd appctr && bash build.sh --clean`. Without `--clean`,
   `build.sh` sees an existing `tailscale_src/` and **skips patching entirely**,
   so a broken patch will not be noticed until someone else builds.

Two constraints on a patch:

* It must apply to pristine sources with no fuzz. `build.sh` runs
  `patch -p1 --batch --forward -F0`, so a version bump that shifts context fails
  the build instead of landing the hunk in the wrong place.
* It must not be empty. `build.sh` aborts on a zero-byte patch, because GNU
  `patch` accepts one and exits 0 — a lost fix would otherwise ship silently.

If you add a change to a file `recreate_patches.sh` does not already diff, add
the stanza for it in the same commit. Otherwise your work disappears at the next
clean build.

Bumping Tailscale means editing `appctr/TAILSCALE_VERSION`; `build.sh` notices
the change and forces a clean re-download and re-patch by itself.

## 4. The three tunnel modes

Settings → Tunnel mode picks exactly one:

| Mode | How traffic gets in | What it needs |
|---|---|---|
| **Proxy** (default) | Apps are pointed at the local SOCKS5 / HTTP proxy and the local DNS server. Nothing is captured. | Nothing — no VPN permission, no root |
| **VPN (TUN)** | Android's `VpnService` fd is handed to the native `hev-socks5-tunnel` (`app/src/main/jni/`), which forwards into that same local SOCKS5 proxy. | The system VPN permission |
| **Root** | The daemon runs through `su` on a real kernel interface `tailscale0`, with policy routing in table `53` and the `TAILSOCKS_MARK` / `TAILSOCKS_DNS` iptables chains. Android's VPN slot stays free. | Magisk / KernelSU / APatch |

**A change to one mode must not change the behaviour of the other two.** They
share the daemon and the local proxies and nothing else, and it is easy to
"fix" one by moving something into that shared middle. Concretely:

* Proxy mode must keep working with no VPN permission and no root. It is the
  reason this app exists; do not make any shared path depend on a `VpnService`
  or on `su`.
* Root Mode routing lives in `core/RootUtils.kt`, and almost all of it is
  *policy routing* written with `ip rule` / `ip route`, not iptables: the tailnet
  rules into table `53` at priority 100, the catch-all into the daemon's table
  `52` at priority 200, the per-app `uidrange … goto` exclusions at priority 190,
  and the LAN and foreign-CGNAT `throw` routes. iptables carries only marking and
  DNAT, and only inside `TAILSOCKS_MARK` / `TAILSOCKS_DNS`: never write a bare
  `--set-mark`, `OUTPUT` carries only the guarded jumps into those chains, and
  `FORWARD` only the `tailscale0` ACCEPT pair. Do not reintroduce a mangle-mark
  bypass chain — 4.0 deleted the old `TAILSOCKS_BYPASS` because a mark set in
  mangle `OUTPUT` arrives after the route and the source address have already
  been chosen. Its rules are tiered — T1
  (tailnet reachability) coexists with anything, T2 (default-route capture) and
  T3 (device-wide DNS) claim the whole device and therefore have exactly one
  owner. If you touch T2 or T3, read `docs/ROOT.md` §4–5 first: the coexistence
  logic there is what keeps another VPN client's apps from being stolen.
* TUN mode is, and in 4.0 remains, the `hev-socks5-tunnel` engine. The rebuild
  in which `tailscaled` owns the `VpnService` fd directly is a plan for 4.1
  ([`docs/NATIVE_TUN_PLAN.md`](docs/NATIVE_TUN_PLAN.md)), not code — do not
  half-land it, and do not document it as if it existed.

State the modes you tested in the pull request. "Proxy only" is a fine answer;
silently untested is not.

## 5. Commits and the changelog

**Subjects** are conventional-commit style, in English, as used in `git log`:

```
feat(root): health-gate the DNS redirect, guard foreign CGNAT, leave netd's table space
fix(ui): stop the DNS test button from covering its own title
perf(root): react to another tunnel at once instead of on the next tick
docs: hand off what is left — Root Mode device checks and the CLI binary
```

* Prefix: `feat`, `fix`, `docs`, `refactor`, `perf`, `style`, `chore`.
* Scope in parentheses, naming the area — `(root)`, `(ui)`, `(core)`, `(dns)`,
  `(tun)`, `(build)`, `(appctr)`, `(security)`, `(patches)`. Drop it only when
  the change really is repo-wide (`docs:`).
* The subject says what the behaviour now is, not which file moved. Lowercase
  after the colon, no trailing period, and it may run to ~90 characters —
  a readable sentence beats a truncated label.
* Use the body for *why*, and for what breaks without it. Not needed for
  trivia; expected for anything subtle. Commit messages, code, comments and
  logs are English regardless of the language of the discussion.

**Anything a user would notice goes into `CHANGELOG.md` in the same commit that
changes the behaviour** — not in a follow-up, not at release time. It goes under
the topmost heading, `## [X.Y.Z] - Unreleased`, in the `Added` / `Changed` /
`Fixed` / `Security` group that fits. Never edit a heading that already has a
date: released sections are history.

Changelog entries are for users and for whoever cuts the release. Write what
changed and why it mattered; leave out class names, file paths, constants,
resource ids and API payloads. Compare a commit subject with its changelog line
in the `[4.0.0]` section to calibrate.

**Versioning is derived, never typed.** Gradle computes `versionName` from
`git describe --tags --always --abbrev=0` and `versionCode` from
`git rev-list --count HEAD` + 500, so no file contains a version number to bump.
A release is made by tagging `vX.Y.Z`; at that moment the `Unreleased` heading
is renamed to `## [X.Y.Z] - YYYY-MM-DD`. That rename is load-bearing: CI builds
the GitHub release notes by matching `## [<tag without the v>]` in
`CHANGELOG.md`, and a tag with no matching heading ships an empty release.
Tagging is the maintainer's job — send a pull request against `main` and leave
the tag alone.

## 6. Testing

Be aware of what this project does and does not have: **there is no unit-test
suite for the Android side, and no instrumentation tests.** Do not assume CI
green means your change works.

What you can check without a device:

* `cd appctr && go test ./...` — the Go bridge's own tests (currently
  `extraargs_test.go`).
* `gofmt` over `appctr/` — CI fails on unformatted Go. `appctr/build.sh` runs
  `gofmt -w *.go` for you, and `git config core.hooksPath .githooks` enables a
  pre-commit hook that does the same.
* `./gradlew app:assembleDebug` — it compiles, and that is genuinely most of
  what the Kotlin side can be checked for automatically.
* If you add an `external fun`, build a release and let
  `verifyReleaseNativeMethods` run: it fails when R8 shrinks a native method
  away, which otherwise crashes `System.loadLibrary` at runtime. New native
  methods need a plain `-keep` for `native <methods>` in
  `app/proguard-rules.pro`.

What genuinely needs a device:

* Anything JNI or native — the TUN engine and ByeDPI are only exercised at
  runtime, and R8 behaviour differs between debug and release.
* The foreground-service lifecycle, Quick Settings tile, widgets, SAF.
* **All of Root Mode.** Policy routing, the iptables chains and the coexistence
  logic with another VPN client cannot be verified anywhere but a rooted phone;
  the 4.0.0 coexistence work was checked on a rooted Redmi (APatch) including
  across a real reboot. Tools for that: **Settings → Diagnostics & developer →
  Check Routing**, the **ROOT** tab in the Logs screen, and
  `tools/root-debug.sh` run under `su` on the device for a full snapshot.

## 7. Where to ask

* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how the daemon, the bridge
  and the app fit together, and why each workaround exists.
* [`docs/ROOT.md`](docs/ROOT.md) — the complete Root Mode reference: the three
  rule tiers, coexistence with other VPN clients, the boot script, diagnostics.
* [`agents.md`](agents.md) — the full engineering mandate this guide summarises.
* [`docs/BUILDING.md`](docs/BUILDING.md) — build pipeline details.

If none of those answer it, open an issue and say which mode, which Android
version, and whether the device is rooted.

*Русская версия: [CONTRIBUTING_RU.md](CONTRIBUTING_RU.md).*
