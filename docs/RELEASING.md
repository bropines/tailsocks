# Releasing TailSocks

*Русская версия: [RELEASING_RU.md](RELEASING_RU.md)*

A release is a tag. CI (`.github/workflows/android.yml`) builds every `v*` tag, signs the APKs and publishes them as a GitHub release; F-Droid rebuilds release tags and ships our APK when its own build matches byte for byte ([DISTRIBUTION.md](DISTRIBUTION.md)). Nothing is built on a push to `main`, and nobody but the author pushes.

## Two channels

One app, one package, one signing key. The tag says which channel a build belongs to.

| | Stable | Beta |
|---|---|---|
| Tag | `v5.1.0` | `v5.1.0-beta.1` … `v5.1.0-beta.9` |
| `VERSION_NAME` | `5.1.0` | `5.1.0-beta.1` |
| GitHub release | the latest release | a pre-release, never the latest; the title ends in "(beta)" |
| Release notes | the `## [5.1.0]` section of `CHANGELOG.md` | a fixed header (`scripts/release/beta-notes.md`), then `## [Unreleased]` as it stands at the tag |
| In-app updater | offered to everyone | offered only with *Receive beta versions* on |
| F-Droid | rebuilt and published | never seen: its update check matches release tags only |
| Obtainium | followed by default | only with *Include prereleases* on |

A beta tag builds exactly what a release tag builds — the same jobs, the same pinned tools, the same APK names (`TailSocks-v5.1.0-beta.1-arm64-v8a-release.apk`). Only the publishing step differs.

**What the updater offers** (Settings → Appearance & language → *Receive beta versions*, off by default):

| Installed | Switch | Offered |
|---|---|---|
| stable | off | the next stable release |
| stable | on | the newest of stable and beta, by version |
| beta | off | nothing, until a stable release newer than that beta is out — then that release |
| beta | on | the next beta, or the final release |

Versions are ordered `5.1.0-beta.2 < 5.1.0-beta.10 < 5.1.0 < 5.1.1-beta.1` (`core/AppVersion.kt`); a pre-release older than what is installed is never offered. A copy a store installed has no updater at all (`UpdateChannel`), so the switch is not shown there.

**Leaving the beta channel.** A beta's code is higher than that of every release before it, and Android does not install an older version over a newer one. So the way back is forward: switch *Receive beta versions* off and install the next stable APK over the beta when it is released — same signature, nothing is lost. Until then the beta stays; the only other way is to uninstall, which takes profiles and keys with it.

**On a beta build** the About dialog calls the app "TailSocks beta", the diagnostics report adds "beta" to its version line, and "What's new" shows the changelog's `[Unreleased]` section under the build's name — again after each beta, in English or Russian.

## Beta and experimental are different things

- A **beta** is a *build*: the next version before it is finished. Anything in it may break; only those who asked for betas get it.
- An **experimental** *feature* ships in a stable build, off by default or clearly marked, and its behaviour may still change. Its changelog line starts with "Experimental:" (remote management in 5.0.0 is one).

Neither replaces the other: a feature that is not ready for everyone stays experimental in the final release too, and a beta is not where unmarked half-finished features go.

## Version codes

`version.properties` holds both lines, and the code follows the name:

- a release: `major×1000000 + minor×10000 + patch×100`;
- beta N of it, N from 1 to 9: that code `− 100 + 10×N`.

Each APK then adds its ABI's offset: 0 for the universal one, 1–4 for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

| Version | `VERSION_CODE` | APKs |
|---|---|---|
| 5.0.0 | 5000000 | 5000000–5000004 |
| 5.0.1-beta.1 | 5000010 | 5000010–5000014 |
| 5.0.1 | 5000100 | 5000100–5000104 |
| 5.1.0-beta.1 | 5009910 | 5009910–5009914 |
| 5.1.0-beta.2 | 5009920 | 5009920–5009924 |
| 5.1.0 | 5010000 | 5010000–5010004 |
| 6.0.0-beta.1 | 5999910 | 5999910–5999914 |
| 6.0.0 | 6000000 | 6000000–6000004 |

Why it orders: a version and its betas use the codes from `code − 90` to `code + 4`, and two versions are at least 100 apart. So every beta is an update over every earlier release and beta, a release is an update over its own betas, and no code published so far changes.

The limits:

- **Nine betas per version.** The tenth would have the release's own code. If nine are not enough, the next one is beta 1 of the next patch — and that patch is then the version that gets released.
- **Minor and patch up to 99**, as before.
- **A beta's code looks like the version below.** The betas of `5.1.0` sit at the end of `5.0.99`'s hundred and those of `6.0.0` just under six million. Nothing collides — a release uses only the first five codes of its hundred, a `5.0.99` included — and nobody reads the code; it only has to order.

The rule is written once, in `buildSrc/src/main/java/io/github/bropines/tailscaled/release/ReleaseVersion.java`. Gradle refuses to configure a build whose code does not follow its name; CI runs the same file against the tag before it builds; `ReleaseVersionTest` holds it to the ordering above. To check a tag by hand, from the repository's root:

```bash
java buildSrc/src/main/java/io/github/bropines/tailscaled/release/ReleaseVersion.java v5.1.0-beta.1
```

## Cutting a beta

1. Leave the changelog alone: the work stays under `## [Unreleased]` in `CHANGELOG.md` and `CHANGELOG_RU.md`. A beta gets no section of its own.
2. In `version.properties` set `VERSION_NAME=5.1.0-beta.1` and `VERSION_CODE=5009910`.
3. Check: the command above prints `kind=beta`, then `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
4. Commit (`chore: release 5.1.0-beta.1`), tag `v5.1.0-beta.1`, push the commit and the tag.
5. CI publishes a pre-release. On the releases page it must carry the *Pre-release* badge, and *Latest* must still be the last stable release — every installed copy without the switch asks GitHub for exactly that one.

**The next beta** is the same with `5.1.0-beta.2` and `5009920`. `[Unreleased]` keeps growing, and each beta's notes are the whole section, not the difference from the beta before.

## The final release

1. Rename `## [Unreleased]` to `## [5.1.0] - YYYY-MM-DD` in both changelogs.
2. Set `VERSION_NAME=5.1.0` and `VERSION_CODE=5010000`.
3. Commit (`chore: release 5.1.0`), tag `v5.1.0`, push.
4. CI publishes it as the latest release, with that section as its notes. Beta users are offered it like everyone else.
5. Move the F-Droid recipe to the new version (below).

## What must not change for F-Droid

F-Droid publishes our APK only while its rebuild of a release tag is identical to ours, and finds new versions by itself. That rests on:

- **Release tags are `v<major>.<minor>.<patch>` and nothing else.** The recipe's `UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$` is what keeps betas out; with the `$` gone, a beta tag would be read as a release.
- **`version.properties` keeps its two lines**, `VERSION_NAME=` and `VERSION_CODE=`, read from the tagged commit, and a release's code stays `major×1000000 + minor×10000 + patch×100` with the ABI offsets 1–4 (`VercodeOperation`). F-Droid takes the first match of each in the file, so no comment above them may spell an assignment out.
- **A release's APKs are named `TailSocks-v<version>-<abi>-release.apk`** in a release tagged `v<version>` — the recipe's `binary:` address.
- **The build path of a release tag**: the Go core built in `/home/vagrant/build/io.github.bropines.tailscaled` with the pinned Go, gomobile and NDK, the C libraries by Gradle, a clean build, one APK for every channel. A beta goes down the same path so that it tests the build the release will get; nothing may be switched on the tag's kind before the publishing step.
- **`buildSrc` stays plain Java with no dependencies** — it is compiled before every build, F-Droid's included, and must not need a repository.

The recipe itself lives in the fdroiddata fork; [`distribution/io.github.bropines.tailscaled.yml`](distribution/io.github.bropines.tailscaled.yml) is its draft. A release changes `versionName`, `versionCode` and `commit` in each of the four entries and `CurrentVersion` / `CurrentVersionCode`; a beta changes nothing there.
