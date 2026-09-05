# B. Make the tailscale CLI binary optional (about 15 MB per APK)

Part of the [TailSocks backlog handoff](../HANDOFF_BACKLOG.md). Read that file first — it carries the ground rules and the device list.

## Why it matters

Every TailSocks APK ships a second Go program, the `tailscale` CLI
(libtailscale_cli.so), next to the daemon. It weighs 15.8-17.5 MB uncompressed per ABI,
adds 5.8-6.6 MB to each per-ABI release APK (about a quarter of the download) and 25 MB
to the universal APK; because native libs are extracted at install the on-device cost is
roughly 22 MB. Yet the app itself needs it for only two things: the Root-Mode shell
wrapper (`su -c tailscale status`) and a handful of Console commands that already have
LocalAPI equivalents. The author has NOT chosen how to slim it down; this brief lays out
the four options, their consequences, and a recommendation, and maps every place the CLI
is built, packaged and used.

## What is true today

- The CLI binary is a gitignored build artifact at
  app/src/main/jniLibs/<abi>/libtailscale_cli.so for all four ABIs (`.gitignore:24`
  ignores `app/src/main/jniLibs/**/*.so`). On-disk sizes today (`ls -la`): arm64-v8a
  16,153,664 B; armeabi-v7a 15,833,456 B; x86 16,069,448 B; x86_64 17,545,472 B. The
  daemon libtailscale.so beside it is 22.8-25.9 MB.
  Evidence: `.gitignore:24`, `.gitignore:25`
- Measured with `unzip -lv` on the existing local release APKs
  (app/build/outputs/apk/release, built 2026-09-05 16:54 from jniLibs dated 16:03), the
  CLI compresses to: arm64-v8a 5,846,130 B of a 23,950,693 B APK (24 %); armeabi-v7a
  6,211,804 of 24,756,029; x86 6,642,687 of 26,195,381; x86_64 6,369,141 of 25,467,011;
  the universal APK carries all four copies = 25,069,762 B of 85,038,811 (29 %). Because
  `jniLibs.useLegacyPackaging = true` extracts .so files at install, the per-device cost
  of a split is roughly compressed-in-APK + extracted, about 22 MB for arm64.
  Evidence: `app/build.gradle.kts:132`
- appctr/build.sh compiles the CLI four times from `./cmd/tailscale` with the same TAGS
  list as the daemon (`TAGS=` at :90; -buildmode=pie, -ldflags='-s -w
  -checklinkname=0'), then copies each into jniLibs as libtailscale_cli.so. Note the two
  PIE builds do NOT pass -trimpath, while the `gomobile bind` at :166 does. CI runs this
  same script (`bash build.sh`).
  Evidence: `appctr/build.sh:90`, `appctr/build.sh:101`, `appctr/build.sh:106`,
  `appctr/build.sh:117`, `appctr/build.sh:122`, `appctr/build.sh:133`,
  `appctr/build.sh:138`, `appctr/build.sh:149`, `appctr/build.sh:154`,
  `appctr/build.sh:166`, `appctr/build.sh:171`, `appctr/build.sh:175`,
  `appctr/build.sh:179`, `appctr/build.sh:183`, `.github/workflows/android.yml:76`
- Packaging invariant that no option may break: the daemon is exec()'d from the
  extracted native-lib directory. Kotlin passes `execPath =
  "${applicationInfo.nativeLibraryDir}/libtailscale.so"` into Go
  `StartOptions.ExecPath`; Go derives execDir from it in `newPathControl`, symlinks
  `<dataDir>/tailscaled` -> the .so, and runs `exec.Command(p.Tailscaled(), ...)`. Root
  Mode does the same from Kotlin (`tailscaledBin` / `daemonBin`). This is why
  `jniLibs.useLegacyPackaging = true` must stay (ROADMAP records it). The manifest
  <application> tag carries no explicit `extractNativeLibs`; AGP derives it from
  useLegacyPackaging.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:837`,
  `appctr/appctr.go:111`, `appctr/appctr.go:540`, `appctr/appctr.go:606`,
  `appctr/paths.go:22`, `appctr/paths.go:27`, `appctr/paths.go:28`,
  `appctr/daemon.go:38`, `appctr/daemon.go:51`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:256`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:933`,
  `app/build.gradle.kts:132`, `app/src/main/AndroidManifest.xml:20`,
  `docs/ROADMAP.md:32`
- Runtime consumer #1 (app-spawned daemon only, NOT Root Mode): the Go bridge symlinks
  `<dataDir>/tailscale` -> `<execDir>/libtailscale_cli.so` inside `tailscaledCmd` (the
  `ln` at daemon.go:37, using `TailscaleCliSo()` / `Tailscale()` from paths.go), and
  `RunTailscaleCmd` / `RunTailscaleArgs` exec that symlink with `--socket`.
  `tailscaledCmd` is reached only from `Start()` (appctr.go:560); Root Mode goes through
  `AttachExternal` (appctr.go:596, called at TailscaledService.kt:654 and :689), which
  never creates the symlink — so on a Root-Mode-only install the Console's CLI commands
  already fail unless an earlier proxy-mode run left the symlink behind.
  Evidence: `appctr/daemon.go:29`, `appctr/daemon.go:36`, `appctr/daemon.go:37`,
  `appctr/appctr.go:560`, `appctr/appctr.go:596`, `appctr/paths.go:29`,
  `appctr/paths.go:30`, `appctr/auth.go:51`, `appctr/auth.go:55`, `appctr/auth.go:60`,
  `appctr/auth.go:61`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:654`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:689`
- A missing CLI binary is silent today. `RunTailscaleArgs` returns an explicit error
  string only when the daemon is not running (auth.go:56-58); on exec failure it logs
  and still returns the (empty) CombinedOutput string, and for
  `status`/`dns`/`netcheck`/`ping` the `isRoutineCheck` flag suppresses even the log
  line — so the UI shows a blank result, not an error.
  Evidence: `appctr/auth.go:56`, `appctr/auth.go:63`, `appctr/auth.go:65`,
  `appctr/auth.go:78`, `appctr/auth.go:88`
- Exactly two Kotlin callers of the CLI exist (`grep -rn runTailscaleCmd app/src`):
  ConsoleActivity runs every command that does not start with '/' through
  `Appctr.runTailscaleCmd(cmd)` inside `executeCmd` (so the built-in presets 'status',
  'netcheck', 'ping 8.8.8.8' at :246 hit the CLI), and the peer-details Ping button
  falls back to `Appctr.runTailscaleCmd("ping $targetIp")` when `Appctr.pingTarget`
  returns blank or starts with 'Error'. R8 keeps all of `appctr.**`.
  Evidence: `app/src/main/java/io/github/bropines/tailscaled/ui/ConsoleActivity.kt:169`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/ConsoleActivity.kt:178`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/ConsoleActivity.kt:195`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/ConsoleActivity.kt:246`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/UIComponents.kt:321`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/UIComponents.kt:323`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/UIComponents.kt:325`,
  `app/proguard-rules.pro:3`
- Runtime consumer #2 (Root Mode): `RootUtils.setTailscaleCliInstalled` bakes
  `File(nativeLibraryDir, "libtailscale_cli.so")` into the asset template
  `scripts/tailscale_cli.sh` (placeholders %PKG_NAME% at :973 and %CLI_BIN% at :974) and
  installs the wrapper as root to four locations: /data/adb/service.d/tailscale, the
  Magisk module /data/adb/modules/tailscaled/system/bin/tailscale (with a generated
  module.prop), /product/bin/tailscale (remount) and /system/bin/tailscale (remount).
  `isTailscaleCliInstalled` probes those four paths. The wrapper falls back to `pm
  path`-derived lib dirs named x86_64/arm64/arm/x86, prints 'TailSocks CLI binary not
  found' and exits 1 if none is executable, and appends
  `--socket=/data/data/$PKG/files/tailscaled.sock`.
  Evidence: `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:962`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:963`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:964`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:966`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:969`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:972`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:974`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:982`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:988`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:993`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:997`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:1018`,
  `app/src/main/assets/scripts/tailscale_cli.sh:7`,
  `app/src/main/assets/scripts/tailscale_cli.sh:9`,
  `app/src/main/assets/scripts/tailscale_cli.sh:18`,
  `app/src/main/assets/scripts/tailscale_cli.sh:26`,
  `app/src/main/assets/scripts/tailscale_cli.sh:27`,
  `app/src/main/assets/scripts/tailscale_cli.sh:34`
- BootReceiver only REFRESHES an already-installed wrapper: it returns early unless Root
  Mode is on (:45-46), and calls `setTailscaleCliInstalled(context, true)` only when
  `isTailscaleCliInstalled()` is already true. It fires on ACTION_BOOT_COMPLETED,
  QUICKBOOT_POWERON and ACTION_MY_PACKAGE_REPLACED (:17-20). The failure mode after a
  full -> CLI-less upgrade is therefore a stale wrapper re-pointed at a missing binary,
  which then prints 'TailSocks CLI binary not found' and exits 1.
  Evidence: `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:17`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:45`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:53`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:54`
- The Settings Root tab exposes the wrapper as a switch bound to `cliInstalled` (probed
  off-thread at :1309), with strings settings_root_cli_title/desc (EN and RU) and two
  hardcoded English toasts at :1554 and :1556. `SettingsSwitchItem` already accepts
  `enabled: Boolean = true` (declared at :2301) and greys out title, subtitle, icon and
  switch when it is false; the CLI switch currently passes `enabled = !isRootBusy`.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1297`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1309`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1539`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1544`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1549`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1554`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:2296`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:2301`,
  `app/src/main/res/values/strings.xml:759`, `app/src/main/res/values/strings.xml:760`,
  `app/src/main/res/values-ru/strings.xml:759`,
  `app/src/main/res/values-ru/strings.xml:760`
- Nothing else uses the CLI. Daemon lifecycle, login and prefs are LocalAPI-only
  (`registerMachineWithAuthKey` is documented as CLI-free; agents.md mandates CLI-less
  management; RETROSPECTIVE records '100% independence from CLI binary calls for
  lifecycle management'). The working LocalAPI replacements for the three Console
  presets are all Go-side and already exercised by the app: `DoLocalAPIRequest` (returns
  'Error: ...' strings, never throws), `PingTarget` (POST /localapi/v0/ping, used at
  UIComponents.kt:321), `GetNetcheckFromAPI` (in-process netcheck, used at
  NetcheckActivity.kt:194), plus the Console's own '/GET /localapi/v0/status' preset.
  Evidence: `appctr/auth.go:104`, `appctr/auth.go:105`, `agents.md:18`,
  `docs/RETROSPECTIVE.md:29`, `appctr/localapi.go:284`, `appctr/localapi.go:286`,
  `appctr/api.go:165`, `appctr/api.go:178`, `appctr/netcheck.go:25`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/NetcheckActivity.kt:194`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/ConsoleActivity.kt:246`
- Do NOT reach for LocalApiClient.kt for this work: only `getStatus` is called anywhere
  in the app (TailscaledService.kt:637). `LocalApiClient.getNetcheck` targets GET
  /localapi/v0/netcheck, which appctr/api.go:158-160 states does not exist in the
  daemon, and `LocalApiClient.ping` posts a JSON body where the Go path uses query
  parameters — both are unused code paths.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/core/LocalApiClient.kt:105`,
  `app/src/main/java/io/github/bropines/tailscaled/core/LocalApiClient.kt:110`,
  `app/src/main/java/io/github/bropines/tailscaled/core/LocalApiClient.kt:111`,
  `app/src/main/java/io/github/bropines/tailscaled/core/LocalApiClient.kt:112`,
  `appctr/api.go:158`, `appctr/api.go:161`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:637`
- Gradle today: no productFlavors anywhere (`grep -n productFlavors
  app/build.gradle.kts` is empty). `splits.abi` is enabled with
  reset()/include(armeabi-v7a, arm64-v8a, x86, x86_64) and `isUniversalApk = true`;
  `ndk.abiFilters` lists the same four. targetSdk = 35, applicationId
  io.github.bropines.tailscaled, debug adds suffix .dev. No output-renaming block
  exists, so APKs are named app-<abi>-<buildType>.apk. AGP 8.13.2 / Gradle 9.2.1.
  Evidence: `app/build.gradle.kts:47`, `app/build.gradle.kts:49`,
  `app/build.gradle.kts:53`, `app/build.gradle.kts:54`, `app/build.gradle.kts:68`,
  `app/build.gradle.kts:71`, `app/build.gradle.kts:72`, `app/build.gradle.kts:73`,
  `app/build.gradle.kts:87`, `gradle/libs.versions.toml:2`,
  `gradle/wrapper/gradle-wrapper.properties:3`
- CI (android.yml): build-go runs `bash build.sh` and uploads app/src/main/jniLibs/ plus
  appctr/tmp/appctr.aar as artifact go-core-artifacts; a gofmt gate fails the job on any
  unformatted appctr/*.go. build-apk renames every APK by substring of its filename
  (arm64-v8a, armeabi-v7a, x86_64, x86, universal, else 'apk') and by 'debug' in its
  path into `TailSocks-v${BASE_VER}-${abi}-${GIT_HASH}-{dev,release}.apk` under
  `apks-separated/${abi}-${type}/`, then uploads it through TEN hardcoded
  per-ABI/per-type steps (:232-:299) named tailsocks-<abi>-<type>. The release job
  collects `pattern: tailsocks-*` and attaches only `release-artifacts/**/*.apk`. A
  second flavor with the same ABI collapses into the same out_dir and filename and is
  overwritten; a non-APK CLI asset is never published. `workflow_dispatch` offers
  build_type debug/release/all.
  Evidence: `.github/workflows/android.yml:23`, `.github/workflows/android.yml:61`,
  `.github/workflows/android.yml:76`, `.github/workflows/android.yml:81`,
  `.github/workflows/android.yml:83`, `.github/workflows/android.yml:84`,
  `.github/workflows/android.yml:200`, `.github/workflows/android.yml:202`,
  `.github/workflows/android.yml:208`, `.github/workflows/android.yml:216`,
  `.github/workflows/android.yml:222`, `.github/workflows/android.yml:226`,
  `.github/workflows/android.yml:228`, `.github/workflows/android.yml:232`,
  `.github/workflows/android.yml:299`, `.github/workflows/android.yml:318`,
  `.github/workflows/android.yml:348`
- In-app updater expectations: the manual check reads releases/latest, lowercases each
  asset name and picks the FIRST .apk whose name contains `Build.SUPPORTED_ABIS[0]`
  (breaking out of the loop), else the first .apk seen; the silent check only compares
  tag_name. Download is a plain HttpURLConnection loop with progress into
  getExternalFilesDir(DIRECTORY_DOWNLOADS)/tailsocks-update-<ver>.apk, validated only by
  `getPackageArchiveInfo().packageName == packageName`; the FileProvider exposes that
  directory. There is NO SHA-256 or signature verification anywhere in the app (the only
  MessageDigest use is a constant-time token compare in TaskerReceiver). OkHttp 4.12.0
  is already a dependency and could be reused for a download.
  Evidence: `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:340`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:352`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1462`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1464`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1471`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1495`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1499`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1523`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1524`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1571`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1576`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1580`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt:1589`,
  `app/src/main/res/xml/file_paths.xml:8`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TaskerReceiver.kt:176`,
  `app/build.gradle.kts:140`
- Build guards the implementer will hit: `verifyGoBridgeFresh` fails a release (warns a
  debug) when appctr/tmp/appctr.aar is older than any appctr/*.go or patch, so any Go
  change (auth.go / daemon.go / paths.go) needs `appctr/build.sh` first; release
  packaging refuses to run without KEYSTORE_FILE. Debug builds need no keystore and
  install alongside the real app (.dev). Note appctr/auth.go does not currently import
  "os" — adding an os.Stat guard means adding the import, and CI runs `gofmt -l` over
  appctr/*.go.
  Evidence: `app/build.gradle.kts:253`, `app/build.gradle.kts:257`,
  `app/build.gradle.kts:270`, `app/build.gradle.kts:288`, `app/build.gradle.kts:290`,
  `appctr/auth.go:3`, `appctr/daemon.go:11`, `docs/BUILDING.md:36`,
  `.github/workflows/android.yml:64`
- Documentation that describes the bundled CLI and would need updating whichever option
  is chosen: docs/ROOT.md and ROOT_RU.md (wrapper section at :17-24, auto-refresh at
  :28, settings step 5 at ROOT.md:178, CLI usage examples at ROOT.md:183-202),
  docs/BUILDING.md:11 and :22 (two PIEs; BUILDING_RU.md:43 mentions only
  libtailscale.so), docs/ROADMAP.md:30-32 and ROADMAP_RU.md:34-36 (open item 'Make the
  bundled tailscale CLI binary optional' plus the useLegacyPackaging note), CHANGELOG.md
  top section `[4.0.0] - Unreleased`. The repo readme is lowercase `readme.md` and does
  not mention libtailscale_cli; there is no README_RU.md at the repo root.
  Evidence: `docs/ROOT.md:17`, `docs/ROOT.md:23`, `docs/ROOT.md:28`, `docs/ROOT.md:178`,
  `docs/ROOT.md:183`, `docs/ROOT_RU.md:17`, `docs/ROOT_RU.md:23`, `docs/ROOT_RU.md:28`,
  `docs/BUILDING.md:11`, `docs/BUILDING.md:22`, `docs/BUILDING_RU.md:43`,
  `docs/ROADMAP.md:30`, `docs/ROADMAP.md:31`, `docs/ROADMAP.md:32`,
  `docs/ROADMAP_RU.md:34`, `docs/ROADMAP_RU.md:35`, `docs/ROADMAP_RU.md:36`,
  `CHANGELOG.md:5`, `readme.md:224`

## Tasks

### - [ ] B1. T1 - Runtime presence check and graceful degradation when libtailscale_cli.so is absent (prerequisite for options b, c, d; harmless under a)

**Effort** S &nbsp;·&nbsp; **Files** `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt`, `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt`, `app/src/main/java/io/github/bropines/tailscaled/ui/ConsoleActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`, `appctr/auth.go`, `appctr/daemon.go`, `CHANGELOG.md`

**Steps**

1. Kotlin: add `fun isCliBinaryBundled(context: Context): Boolean =
   File(context.applicationInfo.nativeLibraryDir, "libtailscale_cli.so").exists()` to
   RootUtils, next to `setTailscaleCliInstalled` (RootUtils.kt:966). RootUtils already
   imports java.io.File (used at :969).
2. SettingsActivity.kt:1539-1559: when !isCliBinaryBundled, pass `enabled = false` to
   the CLI SettingsSwitchItem (the parameter already exists, SettingsActivity.kt:2301,
   and already greys out the row) with a new subtitle string (EN + RU, e.g. 'Not
   included in this build') and never call setTailscaleCliInstalled(install = true).
   Move the hardcoded English toasts at :1554 and :1556 into strings.xml while you are
   there.
3. RootUtils.setTailscaleCliInstalled (RootUtils.kt:966): if `install &&
   !isCliBinaryBundled(context)` return false early after a `rootLog("WARN", ...)`, so
   the BootReceiver refresh (BootReceiver.kt:53-54) cannot re-point an installed wrapper
   at a missing binary after a full -> CLI-less upgrade. Decide with the author whether
   that case should instead REMOVE the wrapper (call the install=false branch) rather
   than leave a stale one that prints 'TailSocks CLI binary not found'.
4. ConsoleActivity.kt:185-197: `val context = LocalContext.current` is already in scope
   (ConsoleActivity.kt:105) and executeCmd is nested inside that composable. Before
   calling Appctr.runTailscaleCmd at :195, check isCliBinaryBundled; if false return a
   fixed message such as 'tailscale CLI is not bundled in this build; use LocalAPI
   commands (start with /)'. Leave the '/' LocalAPI branch untouched.
5. Go appctr/auth.go `RunTailscaleArgs` (:55): after the IsRunning check at :56, add `if
   _, err := os.Stat(pc.TailscaleCliSo()); err != nil { return "Error: tailscale CLI
   binary is not bundled in this build" }` before the exec.Command at :61. auth.go does
   NOT import "os" yet — add it, and keep the file gofmt-clean (CI gate at
   .github/workflows/android.yml:61-69).
6. Go appctr/daemon.go:37: call `ln(p.TailscaleCliSo(), p.Tailscale())` only when that
   .so exists, so no dangling `<dataDir>/tailscale` symlink is created. daemon.go
   already imports "os" (:11).
7. Run `cd /home/pinus/projects/tailsocks/appctr && ./build.sh` (needs ANDROID_NDK_HOME;
   exact command template at docs/HANDOFF_4.0_NATIVE_TUN.md:16-18), then `./gradlew
   app:assembleDebug`.
8. CHANGELOG.md, under `## [4.0.0] - Unreleased` -> Changed: the app now detects whether
   the CLI binary is bundled and disables the Root CLI wrapper and the Console's CLI
   commands with an explanation instead of failing silently.

**Done when**

- With libtailscale_cli.so present nothing changes: Console `status` prints CLI output;
  the Root tab CLI switch works as before.
- With the binary absent, Console `status` prints the 'not bundled' message instead of a
  blank line, the Root CLI switch is disabled with the explanatory subtitle,
  setTailscaleCliInstalled(install=true) returns false, and no `<dataDir>/tailscale`
  symlink is created.
- `./gradlew app:assembleDebug` succeeds; a release build passes verifyGoBridgeFresh
  because build.sh was re-run.

**How to test**

- cd /home/pinus/projects/tailsocks/appctr && ANDROID_NDK_HOME=<ndk> GOTOOLCHAIN=auto
  ./build.sh # expected final line: '✅ Done! Ready to assemble APK.'
  (appctr/build.sh:185)
- cd /home/pinus/projects/tailsocks && ./gradlew app:assembleDebug # expected BUILD
  SUCCESSFUL and '-> Go bridge check: appctr.aar is newer than every Go source and
  patch'
- Root-free negative build (preferred, works on any device): temporarily move
  app/src/main/jniLibs/<abi>/libtailscale_cli.so aside, run ./gradlew app:assembleDebug,
  install that APK, then restore the file (build.sh regenerates it anyway; jniLibs is
  gitignored).
- WSA (127.0.0.1:58526, Android 13 x86_64, Magisk): adb -s 127.0.0.1:58526 install -r
  app/build/outputs/apk/debug/app-x86_64-debug.apk; open Menu -> Console, run `status`
  -> expected the exact 'not bundled' message; run `/GET /localapi/v0/status` ->
  expected JSON. Settings -> Root tab -> CLI switch shown disabled.
- Alternative in-place negative test on WSA if you prefer not to rebuild: adb -s
  127.0.0.1:58526 shell su -c 'find /data/app -path
  "*io.github.bropines.tailscaled.dev*" -name libtailscale_cli.so -delete', force-stop
  the .dev app, then repeat the Console checks and reinstall the APK afterwards to
  restore the file.
- Redmi (192.168.1.83:5555, Android 16 arm64, APatch): same sequence with
  app-arm64-v8a-debug.apk.
- POCO (192.168.1.70:5555, no root, daily phone): do not run the deletion test; at most
  install the root-free negative build if the author asks.

**Risks**

- The wrapper install paths are system-wide (RootUtils.kt:962-964 plus /product/bin at
  :993): exercising the Root CLI switch from the .dev build overwrites the production
  wrapper on that device — acceptable on WSA/Redmi, never on POCO.
- Forgetting appctr/build.sh after the Go edits ships an APK without the Go part;
  verifyGoBridgeFresh only fails releases, a debug build merely warns
  (app/build.gradle.kts:257, :270).
- auth.go currently has no "os" import; a missing import or unformatted file fails the
  CI gofmt gate before anything else.

### - [ ] B2. T2 - Move the in-app CLI uses to LocalAPI so the CLI becomes a Root-Mode-only feature

**Effort** M &nbsp;·&nbsp; **Files** `app/src/main/java/io/github/bropines/tailscaled/ui/ConsoleActivity.kt`, `app/src/main/java/io/github/bropines/tailscaled/ui/UIComponents.kt`, `CHANGELOG.md`

**Steps**

1. ConsoleActivity.kt `executeCmd` (:169-207): intercept the three CLI presets before
   the runTailscaleCmd call at :195. `status` -> `Appctr.doLocalAPIRequest("GET",
   "/localapi/v0/status", "")` (the same call the '/GET /localapi/v0/status' preset
   already makes via :193); `netcheck` -> `Appctr.getNetcheckFromAPI()`
   (appctr/netcheck.go:25, already used at NetcheckActivity.kt:194); `ping <target>` ->
   `Appctr.pingTarget(target, "disco")` (appctr/api.go:165). Any other non-'/' command
   still goes to runTailscaleCmd, or to the T1 message when the binary is absent.
2. Error shapes differ and the Console must not swallow them: doLocalAPIRequest returns
   'Error: ...' as a plain string (appctr/localapi.go:286, :295), getNetcheckFromAPI
   returns a JSON object with an "Error" key when the daemon is down
   (appctr/netcheck.go:27), and PingTarget returns (string, error) so the gomobile
   binding throws — keep the existing try/catch around the call.
3. Update the basePresets list at ConsoleActivity.kt:246 so the labels match the new
   routing (keep 'status', 'netcheck', but replace 'ping 8.8.8.8' — see risks — or
   switch the presets to their '/'-form equivalents).
4. UIComponents.kt:315-327: drop the `Appctr.runTailscaleCmd("ping $targetIp")` fallback
   at :323 and show the error text when `Appctr.pingTarget` returns blank or an Error.
   The 'pong from' / 'LatencyMs' extraction at :325 already handles the LocalAPI JSON
   (it matches LatencyMs).
5. CHANGELOG.md `[4.0.0] - Unreleased` -> Changed: Console status/netcheck/ping and the
   peer Ping button use LocalAPI; the bundled CLI is only needed for the Root-Mode shell
   wrapper.

**Done when**

- Console `status`, `netcheck` and `ping <tailnet-ip>` return output with
  libtailscale_cli.so absent.
- Peer details -> Ping shows a latency line without exec'ing the CLI.
- No Kotlin call to Appctr.runTailscaleCmd remains except the generic Console
  passthrough.

**How to test**

- grep -rn 'runTailscaleCmd' /home/pinus/projects/tailsocks/app/src # expected: exactly
  one hit, ConsoleActivity.kt (it is two hits today: ConsoleActivity.kt:195 and
  UIComponents.kt:323)
- ./gradlew app:assembleDebug; install on WSA (adb -s 127.0.0.1:58526 install -r
  app/build/outputs/apk/debug/app-x86_64-debug.apk); start the proxy; Console: `status`
  -> JSON containing BackendState; `netcheck` -> report JSON; `ping 100.x.y.z` (a peer
  from the Peers screen) -> a line containing LatencyMs.
- Redmi (192.168.1.83:5555): repeat with app-arm64-v8a-debug.apk and press Ping on a
  peer in Peers -> a latency value appears. Root Mode is the case that matters here: the
  `<dataDir>/tailscale` symlink is never created in Root Mode (appctr/appctr.go:596), so
  these commands must now work where they previously could not.

**Risks**

- LocalAPI ping is a disco/DERP ping of a tailnet peer, not ICMP to an arbitrary host
  like the current `ping 8.8.8.8` preset; the preset must change to a tailnet target or
  the behaviour change must be documented.
- Console output changes from CLI text to JSON for these presets; users of the presets
  will notice.

### - [ ] B3. T3 - Option (c)/(d): `lite` (no CLI) and `full` variants via a Gradle product flavor, with CI naming and updater awareness

**Effort** M &nbsp;·&nbsp; **Files** `app/build.gradle.kts`, `appctr/build.sh`, `.github/workflows/android.yml`, `app/src/main/java/io/github/bropines/tailscaled/ui/MainActivity.kt`, `docs/BUILDING.md`, `docs/BUILDING_RU.md`, `docs/ROOT.md`, `docs/ROOT_RU.md`, `docs/ROADMAP.md`, `docs/ROADMAP_RU.md`, `CHANGELOG.md`

**Steps**

1. app/build.gradle.kts inside android {}: add `flavorDimensions += "cli"` and
   `productFlavors { create("full") { dimension = "cli" }; create("lite") { dimension =
   "cli" } }`. Do NOT change applicationId or signing per flavor, so a user can switch
   by installing over the top (the updater's package-name check at MainActivity.kt:1471
   and :1524 keeps working).
2. Two ways to keep the CLI out of `lite`, in order of confidence: (1) source sets —
   have appctr/build.sh copy libtailscale_cli.so into `app/src/full/jniLibs/<abi>/`
   instead of `app/src/main/jniLibs/<abi>/` (build.sh:171, :175, :179, :183), leaving
   the daemon in main; `lite` then simply never sees the file and no packaging DSL is
   involved. (2) the Variant API: `androidComponents {
   onVariants(selector().withFlavor("cli" to "lite")) {
   it.packaging.jniLibs.excludes.add("**/libtailscale_cli.so") } }` — verify this DSL
   against AGP 8.13.2 before relying on it (see unverified). Either way keep
   `jniLibs.useLegacyPackaging = true` (app/build.gradle.kts:132) untouched.
3. Runtime code must key on T1's filesystem presence check, not on a BuildConfig flag,
   so one code path serves both flavors and a hand-stripped APK behaves correctly too.
4. For option (d) (CLI only in the universal APK): the splits DSL has no per-output
   packaging exclusion. Either let `lite` carry splits + universal and publish `full` as
   universal only by filtering in CI, or accept publishing both full sets.
5. CI .github/workflows/android.yml:194-230: the rename loop keys only on ABI substring
   and 'debug' in the path, so
   app/build/outputs/apk/lite/release/app-lite-arm64-v8a-release.apk and the full one
   collapse onto the same out_dir and filename and one overwrites the other. Detect the
   flavor from the filename and put it in both `out_dir` and the output name (e.g.
   TailSocks-v${BASE_VER}-${abi}-lite-${GIT_HASH}-release.apk). The ten hardcoded upload
   steps at :232-:299 each point at `apks-separated/<abi>-<type>/*.apk`; either add
   matching steps per flavor or replace them with one wildcard upload whose artifact
   name still matches `pattern: tailsocks-*` at :318.
6. Updater MainActivity.kt:1571-1589: the loop takes the FIRST .apk whose lowercased
   name contains the primary ABI and breaks. Prefer an asset containing both the ABI and
   `BuildConfig.FLAVOR` (lowercased), then fall back to ABI-only, then to any .apk. Also
   fix the dead fallback URL at :1495 (`app-release.apk` does not exist in the split
   layout).
7. Docs: docs/BUILDING.md:22 (explain lite/full and the assembleLiteDebug /
   assembleFullRelease task names) and BUILDING_RU.md:43; docs/ROOT.md:17-28 and
   ROOT_RU.md:17-28 (the CLI wrapper requires the full build); docs/ROADMAP.md:30-32 and
   ROADMAP_RU.md:34-36 (tick the item, keep the useLegacyPackaging note);
   readme.md:224-228 if the download section needs the distinction. CHANGELOG `[4.0.0] -
   Unreleased` -> Added: lite build without the 16 MB CLI.
8. Tell testers the task names change: `app:assembleLiteDebug` /
   `app:assembleFullDebug`, and `app:assembleLiteRelease` etc. (still needs the
   KEYSTORE_* variables).

**Done when**

- `unzip -l app/build/outputs/apk/lite/debug/app-lite-arm64-v8a-debug.apk | grep -c
  libtailscale_cli.so` prints 0; the full flavor prints 1.
- The lite arm64 debug APK is roughly 5.8 MB smaller than the full one; a lite universal
  is roughly 25 MB smaller than a full universal.
- libtailscale.so, libgojni.so, libhev-socks5-tunnel.so and libbyedpi.so are all still
  present in lite.
- On device, lite: proxy mode connects; Console `/GET /localapi/v0/status` works; the
  Root CLI switch is disabled (T1). Full: the Root CLI switch enables and `su -c
  tailscale status` works.
- A workflow_dispatch dry run produces distinct filenames for lite and full for every
  ABI and no APK is overwritten.

**How to test**

- cd /home/pinus/projects/tailsocks && ./gradlew app:assembleLiteDebug
  app:assembleFullDebug
- unzip -l app/build/outputs/apk/lite/debug/app-lite-arm64-v8a-debug.apk | grep -c
  libtailscale_cli.so # expected 0
- unzip -l app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk | grep -c
  libtailscale_cli.so # expected 1
- unzip -l app/build/outputs/apk/lite/debug/app-lite-arm64-v8a-debug.apk | grep
  'lib/arm64-v8a/' # expected libtailscale.so, libgojni.so, libhev-socks5-tunnel.so,
  libbyedpi.so
- ls -l app/build/outputs/apk/*/debug/app-*-arm64-v8a-debug.apk # lite smaller by
  roughly 5.8 MB
- WSA (127.0.0.1:58526): adb -s 127.0.0.1:58526 install -r
  app/build/outputs/apk/lite/debug/app-lite-x86_64-debug.apk; adb -s 127.0.0.1:58526
  shell su -c 'ls /data/app/*/io.github.bropines.tailscaled.dev-*/lib/x86_64/' #
  expected libtailscale.so present, libtailscale_cli.so absent; start the proxy from the
  UI; Console `/GET /localapi/v0/status` returns JSON; Settings -> Root tab: CLI switch
  disabled. Then install app-full-x86_64-debug.apk over it, enable Root Mode + the CLI
  switch, and run adb -s 127.0.0.1:58526 shell su -c 'tailscale status' # expected peer
  list
- Redmi (192.168.1.83:5555, APatch): same with app-lite-arm64-v8a-debug.apk then
  app-full-arm64-v8a-debug.apk; `su -c tailscale ip` prints the 100.x address.
- POCO (192.168.1.70:5555): no test planned (no root); the author may install a lite
  arm64 release himself.

**Risks**

- Doubling the variant matrix doubles CI time and release assets (10 -> 20 APKs) and
  makes the release page harder to read — hence the (d) variant of publishing full as
  universal only.
- The updater's first-match asset selection (MainActivity.kt:1580-1583) would silently
  move a lite user to a full APK or the reverse unless the flavor preference is added.
- The per-flavor jniLibs exclude DSL was not compiled in this session; the source-set
  approach (option 1 above) avoids the uncertainty but changes appctr/build.sh, which CI
  also runs.
- Do not give the flavors different applicationIds — Android would treat them as
  separate apps, and the Root wrapper paths (RootUtils.kt:962-964) and the BootReceiver
  logic assume a single package.

### - [ ] B4. T4 - Option (b): download the CLI on demand from the app's own GitHub release (only if the author picks it)

**Effort** L &nbsp;·&nbsp; **Files** `appctr/build.sh`, `.github/workflows/android.yml`, `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt`, `app/src/main/assets/scripts/tailscale_cli.sh`, `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt`, `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt`, `appctr/auth.go`, `appctr/daemon.go`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`, `docs/ROOT.md`, `docs/ROOT_RU.md`, `docs/BUILDING.md`, `CHANGELOG.md`

**Steps**

1. appctr/build.sh: keep compiling the four CLIs (:101, :117, :133, :149) but stop
   copying them into jniLibs (delete :171, :175, :179, :183); place them as
   appctr/tmp/cli/tailscale-<abi> (arm64-v8a, armeabi-v7a, x86, x86_64) plus a
   SHA256SUMS file.
2. CI android.yml: add appctr/tmp/cli/ to the go-core-artifacts upload (:78-84) and to
   the release job's `files:` (:348) so each release carries one CLI asset per ABI plus
   SHA256SUMS. Asset naming is a decision.
3. Kotlin: new core/CliDownloader.kt that (1) picks the ABI from
   `applicationInfo.nativeLibraryDir`'s last segment or Build.SUPPORTED_ABIS[0]; (2)
   downloads from the app's OWN release tag, not releases/latest, so the CLI matches the
   daemon version pinned in appctr/TAILSCALE_VERSION (v1.102.1, docs/BUILDING.md:8); (3)
   verifies SHA-256 before moving the file into `context.filesDir/bin/tailscale` — note
   the app has no hashing code today
   (app/src/main/java/io/github/bropines/tailscaled/core/TaskerReceiver.kt:176 is a
   constant-time compare, not a digest); (4) chmod 755; reuse the HttpURLConnection +
   progress pattern at MainActivity.kt:1499-1521 or the existing OkHttp dependency
   (app/build.gradle.kts:140).
4. RootUtils.setTailscaleCliInstalled (RootUtils.kt:969): point %CLI_BIN% at the
   downloaded file instead of nativeLibraryDir; the `pm path` fallbacks in
   tailscale_cli.sh:8-19 become meaningless and should go, but keep the 'not found'
   guard at :26-29. Trigger the download from the Settings CLI switch
   (SettingsActivity.kt:1539) with a progress indicator and a size warning (~16 MB).
5. In-app (non-root) use cannot survive this option if Android's W^X restriction holds
   (targetSdk 35, app/build.gradle.kts:49 — verify on device, see unverified): Console
   non-'/' commands must be handled by T2 (LocalAPI) and T1 (clear message); remove the
   ln at appctr/daemon.go:37 and either make RunTailscaleArgs (appctr/auth.go:55) always
   return the T1 message or delete RunTailscaleCmd/RunTailscaleArgs together with their
   two Kotlin callers.
6. Handle absence after 'Clear data' or a restore: BootReceiver.kt:53-54 must not
   refresh a wrapper when the downloaded binary is missing (T1's guard covers it); offer
   a re-download in Settings.
7. Consider routing the download through the app's own SOCKS proxy when the tunnel is up
   (a decision).
8. Docs and CHANGELOG as in T3; docs/ROOT.md:23 and ROOT_RU.md:23 must describe the new
   binary location instead of 'extracted from the app package'.

**Done when**

- No libtailscale_cli.so in any APK; every release carries one CLI asset per ABI plus
  SHA256SUMS.
- Enabling the Root CLI switch downloads the ABI-matching binary, verifies its hash,
  installs the wrapper, and `su -c tailscale status` works; a corrupted download is
  rejected with a visible error and nothing is installed.
- With no network the switch fails with an explanatory message and the previous state is
  preserved.
- Console commands and the peer Ping keep working via LocalAPI (T2).

**How to test**

- cd /home/pinus/projects/tailsocks/appctr && ./build.sh && ls -la tmp/cli/ # expected
  four tailscale-<abi> files + SHA256SUMS, and no libtailscale_cli.so under
  ../app/src/main/jniLibs/*/
- ./gradlew app:assembleDebug && unzip -l
  app/build/outputs/apk/debug/app-x86_64-debug.apk | grep -c libtailscale_cli.so #
  expected 0
- W^X check on WSA BEFORE committing to this design: adb -s 127.0.0.1:58526 shell run-as
  io.github.bropines.tailscaled.dev
  /data/data/io.github.bropines.tailscaled.dev/files/bin/tailscale version # expected:
  permission denied for the app uid; the same command under `su -c` should succeed.
- WSA (127.0.0.1:58526): install the debug APK; Settings -> Root -> Grant root -> enable
  the CLI switch -> progress -> success; adb -s 127.0.0.1:58526 shell su -c 'sha256sum
  /data/data/io.github.bropines.tailscaled.dev/files/bin/tailscale' # matches
  SHA256SUMS; then su -c 'tailscale status' # peer list
- Redmi (192.168.1.83:5555, APatch): repeat the download and `su -c tailscale ip` to
  confirm the APatch root shell can exec from app storage.
- Negative: replace the downloaded file with garbage of the same name, re-toggle the
  switch -> hash mismatch error and no wrapper installed.

**Risks**

- Android W^X (targetSdk >= 29) blocks exec() of files in the app's own data directory
  from the app process; only a root shell could run the downloaded CLI. If the author
  wants the in-app Console CLI to keep working, option (b) cannot deliver it.
- Integrity: with no hash verification a MITM or a wrong asset becomes a root-executed
  binary. A hash embedded at build time is safer than trusting a downloaded SHA256SUMS.
  The app has no digest code at all today.
- Version skew: the CLI must match the daemon (LocalAPI is versioned); downloading from
  releases/latest from an old app would fetch a newer CLI — pin to the app's own tag.
- GitHub reachability behind censorship; a 16 MB download on mobile data; the daemon may
  not be running when the user wants to install the CLI.
- Some root solutions run su in an SELinux domain that denies executing app_data_file;
  not verified for Magisk (WSA) or APatch (Redmi).
- This option has the most moving parts of all four: CI asset publishing, a downloader,
  hashing, and storage lifecycle.

### - [ ] B5. T5 - Documentation and changelog for the chosen option

**Effort** S &nbsp;·&nbsp; **Files** `docs/ROOT.md`, `docs/ROOT_RU.md`, `docs/BUILDING.md`, `docs/BUILDING_RU.md`, `docs/ROADMAP.md`, `docs/ROADMAP_RU.md`, `CHANGELOG.md`

**Steps**

1. Whatever option is chosen, update: docs/ROOT.md:17-28 and :178-202 and
   docs/ROOT_RU.md:17-28 (where the binary comes from and which build or download
   provides it); docs/BUILDING.md:11 and :22 plus docs/BUILDING_RU.md:43 (the two-PIE
   explanation and the new artifact layout); docs/ROADMAP.md:30-32 and
   docs/ROADMAP_RU.md:34-36 (mark the item done, keep the useLegacyPackaging note); and
   the CHANGELOG `[4.0.0] - Unreleased` section in the existing user-facing style.
2. If option (a) is chosen, record the decision and the measured numbers from this brief
   in docs/ROADMAP.md so the item is not researched a third time.

**Done when**

- `grep -rn libtailscale_cli docs/ readme.md` returns only statements that are true for
  the chosen option.
- The CHANGELOG top section describes the change in the same style as the existing
  entries.
- Both the EN and RU copy of every touched doc are updated.

**How to test**

- grep -rn 'libtailscale_cli' /home/pinus/projects/tailsocks/docs
  /home/pinus/projects/tailsocks/readme.md # note: the repo readme is lowercase
  readme.md and there is no README_RU.md at the root

**Risks**

- Docs drift between the EN and RU copies; edit both in the same commit.

## Needs the author's decision

Stop and ask rather than guessing; each of these changes what gets built.

- Which option: (a) keep as is — zero work, about 6 MB more per split download and about
  22 MB more on device, Console CLI commands keep working for proxy-mode users; (b)
  download on demand — smallest APK for everyone, but adds a downloader, hash
  verification, CI asset publishing, version pinning and censorship/reachability
  concerns, and the downloaded CLI can likely only be run by root (W^X), so in-app CLI
  commands must move to LocalAPI anyway; (c) lite/full product flavor — a contained
  Gradle change with no new runtime subsystem, but it doubles the variant matrix and
  needs CI-rename and updater changes; (d) CLI only in the universal APK — the same
  mechanism as (c) with full published as universal only, keeping the release page at 6
  assets. RECOMMENDATION (not decided): do T1 + T2 regardless — they cost little and
  remove the in-app dependence on the CLI (and T2 fixes Console status/netcheck/ping in
  Root Mode, where the CLI symlink is never created) — then (c)/(d) with `lite` as the
  default for the per-ABI splits and a `full` universal for Root-Mode users who want `su
  -c tailscale`.
- If (c)/(d): flavor names and asset suffixes, whether both flavors get all five outputs
  or full is universal-only, and confirmation that both flavors keep applicationId
  io.github.bropines.tailscaled (recommended, so switching is an install-over).
- If (b): asset naming on the GitHub release, the integrity mechanism (hash embedded in
  BuildConfig at build time vs a SHA256SUMS asset), pinning to the app's own tag
  (recommended) vs releases/latest, whether the download may go through the app's SOCKS
  proxy, and whether RunTailscaleCmd/RunTailscaleArgs and the Console's non-'/' commands
  are removed outright.
- Whether the Console presets status/netcheck/'ping 8.8.8.8' should be re-pointed to
  LocalAPI (T2) even under option (a); LocalAPI ping targets tailnet peers, not
  arbitrary hosts, so the 'ping 8.8.8.8' preset changes meaning.
- Under T1, whether a CLI-less build that finds a previously installed wrapper should
  merely refuse to refresh it or actively remove it (BootReceiver.kt:53-54 currently
  refreshes any wrapper that is already installed).
- Whether appctr/build.sh and CI should keep compiling the CLI for all four ABIs when it
  is no longer shipped in the splits (it costs CI time; under (d) only the universal
  needs it, under (b) the release assets do).

## Unverified

Leads, not facts. Confirm on the device or in the code before acting.

- The exact AGP 8.13.2 Variant API DSL for excluding a jniLibs file per product flavor
  (`onVariants { it.packaging.jniLibs.excludes.add(...) }`) was not compiled — this
  session was read-only and Gradle was not run. The alternative in T3 (moving the CLI
  copy into a `full` source set, app/src/full/jniLibs/) uses standard AGP source-set
  merging but was likewise not built here. No DSL for per-split (per-output) packaging
  exclusions was found, so option (d) assumes a flavor or CI-side filtering of which
  APKs are published.
- The Android W^X restriction (apps with targetSdk >= 29 cannot exec() files under their
  own data directory) is documented platform behaviour but was not exercised on any
  device here (no adb was used). It must be confirmed before committing to option (b),
  together with whether a Magisk (WSA) or APatch (Redmi) su shell may exec a binary in
  /data/data/<pkg>/files.
- The order in which the GitHub Releases API returns assets, which decides what the
  updater's first-match loop (MainActivity.kt:1571-1589) picks when two assets contain
  the same ABI string.
- The on-device footprint (about 22 MB per split) is arithmetic on the compressed and
  uncompressed sizes, not a `du` measurement on a device.
- Whether additional ts_omit_* build tags or adding -trimpath would shrink cmd/tailscale
  itself was not investigated. What is verified: the TAGS list at appctr/build.sh:90 is
  shared by the daemon and the CLI, and neither PIE build passes -trimpath while the
  gomobile bind at :166 does.
- The APK size figures come from the local outputs in app/build/outputs/apk/release/
  dated 2026-09-05 16:54 (jniLibs 16:03); they are assumed to correspond to HEAD 3800f43
  but were not rebuilt to confirm.
- docs/HANDOFF_BACKLOG.md and docs/handoff/*.md appeared in the working tree during this
  verification, and the uncommitted edits to agents.md:158 and CLAUDE.md:16-21 that
  reference HANDOFF_BACKLOG.md are part of the same in-flight handoff effort — not a
  pre-existing dangling reference in the repository, and not something this task should
  'fix'.
- Whether AGP 8.13.2 accepts `androidComponents { onVariants(selector().withFlavor(...))
  { it.packaging.jniLibs.excludes.add(...) } }`, and whether the app/src/full/jniLibs
  source-set alternative packages correctly — no Gradle run was permitted (read-only
  task).
- Whether Android's W^X restriction blocks exec() of a downloaded binary under filesDir
  on these devices, and whether a Magisk (WSA) or APatch (Redmi) su shell can exec from
  app_data_file — no adb was permitted.
- Whether the local release APKs in app/build/outputs/apk/release (16:54) were built
  from HEAD 3800f43's Go sources; the jniLibs they came from are dated 16:03 but nothing
  records the commit.
- The order in which the GitHub Releases API returns assets, which decides the updater's
  first-match behaviour when two assets share an ABI string.
- The real per-device footprint of the CLI (about 22 MB is arithmetic on compressed +
  extracted sizes, not a `du` on a device).
- Whether extra ts_omit_* tags or -trimpath would meaningfully shrink cmd/tailscale —
  not investigated.

## Out of scope

- Shrinking libtailscale.so (the daemon, 24.1 MB uncompressed on arm64) or libgojni.so
  (the gomobile bridge, 11.9 MB) — different levers (build tags, moving LocalAPI to
  Kotlin per docs/ROADMAP.md:27).
- Turning off jniLibs.useLegacyPackaging / extractNativeLibs — impossible while the
  daemon is exec()'d from nativeLibraryDir (app/build.gradle.kts:132,
  TailscaledService.kt:837, docs/ROADMAP.md:32).
- The 4.0 native-TUN rebuild (docs/HANDOFF_4.0_NATIVE_TUN.md) and any Root-Mode routing
  behaviour.
- Play Store / Android App Bundle asset-pack delivery — the project distributes APKs
  through GitHub releases and its own updater.
- Dex and resource size (R8 and resource shrinking are already on,
  app/build.gradle.kts:92-93).
- Pushing commits or building a release APK (author-only; the KEYSTORE_* variables are
  required).
