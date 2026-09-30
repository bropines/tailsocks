# Distribution: F-Droid, IzzyOnDroid, Obtainium

*Русская версия: [DISTRIBUTION_RU.md](DISTRIBUTION_RU.md)*

Research from 2026-09-30; line numbers are as of commit `730ff4e`. What's already in place:

- `fastlane/metadata/android/{en-US,ru}/` — title, short and full description, `changelogs/README.txt` (how to name the changelog file), `images/README.txt` (where to source screenshots). Both F-Droid and IzzyOnDroid read these.
- `fastlane/.gitignore` — the root `.gitignore:68` ignores `*.txt`; without this file the texts would never make it into a commit.
- [`distribution/io.github.bropines.tailscaled.yml`](distribution/io.github.bropines.tailscaled.yml) — a draft recipe for fdroiddata; every assumption is tagged `ASSUMPTION` / `OPEN` / `BLOCKER`.

The full description is upfront about the app asking GitHub for the latest release on every launch. Once that check becomes optional, drop the sentence in both languages.

## Bottom line

| Channel | Verdict | Blocker | Effort |
|---|---|---|---|
| GitHub Releases | works | — | — |
| Obtainium | works right now | nothing; better to drop the hash from the APK filename | 15 minutes for a badge |
| F-Droid, F-Droid signing | feasible | five build fixes and an opt-in update check | 1–2 days + review queue (weeks) |
| F-Droid, reproducible build (author's signature) | feasible but costly | CI is non-deterministic: paths, timestamps, NDK, gomobile | another 2–4 days, outcome not guaranteed |
| IzzyOnDroid | technically almost ready, policy-wise likely a rejection | policy against AI-written code; AppFunctions | whether to submit is the author's call |

Order of work: the Obtainium badge now → small fixes useful to every channel (below, items 1, 5, 6, 8, and `dependenciesInfo`) → an F-Droid MR → a reproducible build as a separate effort.

## Obtainium

Added by pointing Obtainium at the repository. The *Attempt to filter APKs by CPU architecture* option (`autoApkFilterByArch`, on by default for new apps) looks for an ABI name in the filename, so it picks `…-arm64-v8a-…` out of the release's five APKs. On x86 devices, `x86_64` also matches the `x86` filter, so the user picks manually. Obtainium compares the tag (`v4.5.2`) against `versionName`, which since 4.5.2 is the plain version (`4.5.2`).

A badge for the README (save the image `assets/graphics/badge_obtainium.png` from the Obtainium repository locally — they ask that you not hotlink it):

```markdown
[<img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="48">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/bropines/tailsocks)
```

Optionally, add a config to the shared catalog at https://apps.obtainium.imranr.dev (a PR/issue against `ImranR98/apps.obtainium.imranr.dev`).

## F-Droid (main repository)

**Policy fit.** The BSD-3-Clause license qualifies. The whole `releaseRuntimeClasspath` has been checked: AndroidX, Material, OkHttp, kotlinx, Guava and their dependencies — no GMS, no Firebase; none of it matched the non-free-library signatures in F-Droid's scanner (suss). There are no prebuilt binaries in git besides `gradle-wrapper.jar` (allowed) and `wintun.dll` in the hev-socks5-tunnel submodule (the scanner leaves a non-executable `.dll` alone).

**Precedents.** The official Tailscale app (`com.tailscale.ipn`) already builds on F-Droid: Go is compiled from source (srclib, bootstrap via `golang-go` from trixie-backports), Go modules are fetched at the `build` step, and the NDK is pinned. It carries one anti-feature — *Tracking*, for uploading debug logs — which we've compiled out (`ts_omit_logtail`, `appctr/build.sh:111`; `TS_NO_LOGS_NO_SUPPORT=true`, `appctr/daemon.go:114`). sing-box (`io.nekohasekai.sfa`) uses gomobile + Go from srclib + a reproducible build; SocksTun (`hev.sockstun`) uses hev-socks5-tunnel and is also reproducible. There's no ByeDPI/ByeByeDPI recipe in fdroiddata.

**Anti-features that could stall this.**

- *Tracking* — F-Droid's definition includes "update checks the user doesn't know about." Cleared: the F-Droid build (`-PselfUpdate=false`) has no update check at all, and elsewhere the launch check is a setting.
- *NonFreeNet* — up to the reviewer: the default coordination server and the Admin API console are Tailscale's proprietary service; the former can be swapped for a self-hosted Headscale (the login-server field), the latter can't. `com.tailscale.ipn` doesn't carry this label, which is an argument in our favor.
- Self-update: policy bans downloading executable code. Cleared the same way: with `-PselfUpdate=false` the About screen has no check, download or install, and a release manifest removes `REQUEST_INSTALL_PACKAGES`.

**What blocked the build** (numbered for cross-referencing). **All nine are fixed as of 4.5.2**; the recipe in [`distribution/io.github.bropines.tailscaled.yml`](distribution/io.github.bropines.tailscaled.yml) needs no `sed`. Item 5 became `version.properties` (`VERSION_NAME=4.5.2`, `VERSION_CODE=4050200`, read by Gradle and by F-Droid's `UpdateCheckData`; the hash moved to `BuildConfig.GIT_HASH`); item 7 is pinned in `build.sh` to the anet version in `appctr/go.mod`.

| # | Where | What | Fix |
|---|---|---|---|
| 1 | `app/build.gradle.kts:340-366` | release won't package without a keystore, but F-Droid builds unsigned — the build fails | a Gradle property, e.g. `-PallowUnsignedRelease` (`gradleprops` in the recipe); for now, `sed` in the recipe |
| 2 | `appctr/build.sh:64` | Tailscale sources are fetched via `curl … \| tar` with no verification | a sha256 next to `TAILSCALE_VERSION`, or a `TS_TARBALL` variable; for now, `sed` + `sha256sum -c` in the recipe |
| 3 | `appctr/build.sh:100` (plus `appctr/go.mod:3`, `.github/workflows/android.yml:49`) | `GOTOOLCHAIN=auto` downloads a prebuilt Go 1.26.6 | `GOTOOLCHAIN=${GOTOOLCHAIN:-auto}`; in the recipe, srclib `go@go1.26.6` and `local` |
| 4 | `.github/workflows/android.yml:74-75` | `gomobile@latest` and `gomobile init`, which installs `gobind@latest` | install `gomobile` and `gobind` pinned to the version in `appctr/go.mod`; `init` isn't needed |
| 5 | `app/build.gradle.kts:11-18`, `:62`, `:115` | `versionCode` is commit count + 502, `versionName` carries a hash — F-Droid can neither predict the version nor update the recipe on its own | a `version.properties` file (`VERSION_NAME=4.5.0`, `VERSION_CODE=4050000` — well above the current ~1500, no collisions); keep the hash in `BuildConfig` for the "About" screen |
| 6 | `MainActivity.kt:230`, `:416-437` | silent update check | a toggle, off by default, labeled "downloads from github.com/bropines/tailsocks" |
| 7 | `appctr/build.sh:109` | `go mod tidy` inside the Tailscale tree pulls in whatever `github.com/wlynxg/anet` is latest on build day | pin the `require` in patch 06, drop `tidy` |
| 8 | `app/src/main/jni/byedpi/` | vendored ByeDPI (MIT) with no LICENSE file; `readme.md:298`, `:355` link to a nonexistent `hufyhang/byedpi` | add the LICENSE from https://github.com/hufrea/byedpi, fix the links |
| 9 | `app/build.gradle.kts` (no `ndkVersion`), `android.yml:103` | the NDK isn't pinned anywhere: 28.2 locally, whichever CI finds first | one NDK version, set in `ndkVersion`, in CI, and in `build.sh` |

Not a blocker, but the recipe needs a workaround: `jvmToolchain(17)` (`app/build.gradle.kts:136-138`) — on the Debian trixie build server running JDK 21, JDK 17 is installed from bookworm, the same as SocksTun does. ABI splits share one `versionCode`, so F-Droid will ship a universal APK (~85 MB); per-ABI APKs would need a `versionCode` per ABI.

**Steps.**

1. ~~Fix the items above and cut a release~~ — done in 4.5.2.
2. Fork https://gitlab.com/fdroid/fdroiddata, drop the draft into `metadata/io.github.bropines.tailscaled.yml`, update `versionName`/`versionCode`/`commit`, and remove any `sed` steps that are no longer needed.
3. Run `fdroid lint`, `fdroid rewritemeta`, `fdroid build -v -l io.github.bropines.tailscaled` — locally or in the fork's GitLab CI. `rewritemeta` drops the draft's comments; fdroiddata's CI wants its canonical form.
4. Open an MR. Alternative: file a request at https://gitlab.com/fdroid/rfp/-/issues (slower). After the merge, the app shows up in 24–48 hours.

F-Droid's signature differs from the GitHub release signature: switching channels means uninstalling first, and uninstalling wipes profiles and keys (a backup saves you). This is worth spelling out in the README.

## Reproducible build

F-Droid builds the app itself and diffs it against the author's APK; on a match, it publishes the author's APK. That gives one signature across every channel, and for Google's developer verification (below) it's the only way an F-Droid build would pass the check. What's currently non-deterministic — checked against `TailSocks-v4.4.4-arm64-v8a` from the release:

- `libtailscale.so` contains 1,859 absolute paths like `/home/runner/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.6…`: the daemon and CLI are built without `-trimpath -buildvcs=false` (`appctr/build.sh:116-175`).
- `libgojni.so` embeds the build timestamp (`v1.102.5-19dd52d-2026-09-29_174046`, `appctr/build.sh:181-182`, `:187`) and the path `/home/runner/work/tailsocks/tailsocks/appctr`: gomobile writes absolute `replace` directives into a temporary `go.mod`, and `-trimpath` doesn't strip them. The only fix is matching build paths between CI and F-Droid (F-Droid's server builds under `/home/vagrant/build/<appid>` — needs checking).
- `libhev-socks5-tunnel.so` contains 17 paths from lwip: CI builds the C side with a separate `ndk-build` (`android.yml:101-111`) that skips `-ffile-prefix-map`, which only Gradle passes (`app/build.gradle.kts:69`) — and Gradle skips its own `ndkBuild` step when the `.so` files already exist (`:96-102`). Simplest fix: let Gradle build the C side in CI too.
- The NDK isn't pinned (item 9).
- The APK carries a `DEPENDENCY_INFO_BLOCK` — a blob encrypted with a Google key; both F-Droid and IzzyOnDroid ask that it be disabled: `android { dependenciesInfo { includeInApk = false; includeInBundle = false } }`.
- `assets/dexopt/baseline.prof` — a known source of diffs; worth checking once everything else is gone.
- Release filenames embed a hash (`android.yml:226-228`), but the recipe's `Binaries:` only understands `%v` and `%c`: needs a pattern like `TailSocks-v%v-universal-release.apk`.

The certificate fingerprint for `AllowedAPKSigningKeys` has already been pulled from the 4.4.4 release and is sitting in the draft. Worth doing: yes, if F-Droid is wanted at all.

## IzzyOnDroid

Technically almost ready: a license, tagged releases with APKs, signed with the release key (scheme v2), not debuggable. The limit is "around 30 MB per app" and per APK; with several ABIs, Izzy picks arm64-v8a (`ApkMatch: arm64-v8a`, 24.4 MB — passes; the 85 MB universal one doesn't). fastlane metadata now exists. Still needed: make the update check opt-in (self-update is tolerated only when it's off by default and states where it downloads from) and drop the `DEPENDENCY_INFO_BLOCK`.

The obstacle is policy. Quoting the rules: "We are strongly opposed to apps which are fully or in part created by generative AI tools," "Vibe-coded apps will be rejected," "Apps acting as front-end for LLMs … or integrate with such services, will be rejected." Documentation text is fine, code isn't; the submission form asks whether AI was used. This repository has `CLAUDE.md`, `agents.md`, `.skills/`, a `Co-Authored-By: Claude` trailer on 184 of its 1,000 commits, and 14 AppFunctions built for assistants like Gemini. An honest submission would most likely be rejected; whether to submit at all is the author's call. If submitting: file an issue at https://codeberg.org/IzzyOnDroid/repodata/issues, then the repository and the on-device APK get reviewed (VirusTotal, network monitoring); updates are then pulled daily from GitHub releases.

## Google Developer Verification (2026)

- August 2026 — the API, limited-distribution accounts (up to 20 devices, no ID or fee), and an "advanced flow" for experienced users. As of September 30, 2026 (today), certified devices in Brazil, Indonesia, Singapore, and Thailand only accept apps from verified developers; worldwide rollout follows in 2027.
- Installing via ADB still works. The advanced flow: enable Developer options, a 24-hour wait, then re-authentication.
- Registration happens in the Android Developer Console: name, address, possibly an ID, a $25 fee; ownership of the app is proven with an APK signed by your own key. Verification is tied to the package name and the key.
- F-Droid (open letter, 2026-02-24): "We unequivocally advise against signing up for this program, now or ever." F-Droid will not register, so its own signed APKs can only be installed in these countries via the advanced flow or over ADB.
- IzzyOnDroid's front page reads: "The free Android world is under threat – and IzzyOnDroid with it" (linking to keepandroidopen.org). Izzy distributes author-signed APKs, so their fate hinges on the author's own registration.
- For TailSocks: the main audience (Russian documentation, DPI circumvention) is outside the first wave; the question lands in 2027. If the author registers the package and key, GitHub, Obtainium, Izzy, and a reproducible F-Droid build all install as usual — an F-Droid-signed build doesn't. If not, every channel needs the advanced flow. F-Droid asks that you don't register; the decision is the author's.

## Sources

- F-Droid: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), [Anti-Features](https://f-droid.org/docs/Anti-Features/), [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/), [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/), [Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [descriptions and graphics](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/), [forum: AI policy (there isn't one)](https://forum.f-droid.org/t/does-f-droid-have-a-formal-policy-on-libre-ai/33279), [open letter](https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html)
- fdroiddata recipes: [com.tailscale.ipn](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.tailscale.ipn.yml), [io.nekohasekai.sfa](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/io.nekohasekai.sfa.yml), [hev.sockstun](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/hev.sockstun.yml), [srclibs/go.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/srclibs/go.yml)
- IzzyOnDroid: [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), [New App Inclusions](https://izzyondroid.org/contributing/NewAppInclusions/), [Fastlane](https://izzyondroid.org/docs/general/Fastlane/), [YAML Metadata](https://izzyondroid.org/docs/general/YamlMetadata/), [FAQ](https://izzyondroid.org/faq/), [APK checks](https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en)
- Obtainium: [Deep Links](https://wiki.obtainium.imranr.dev/deep_links/), [config catalog](https://apps.obtainium.imranr.dev/), `lib/services/apk_filter_service.dart` in the [repository](https://github.com/ImranR98/Obtainium)
- Google: [Android developer verification](https://developer.android.com/developer-verification), [The Hacker News, 2026-06](https://thehackernews.com/2026/06/google-sets-sept-30-deadline-for.html)
