# TailSocks on Google Play: feasibility and cost

*Русская версия: [GOOGLE_PLAY_RU.md](GOOGLE_PLAY_RU.md)*

Research as of 2026-09-30. Every policy claim links to a source; anything that couldn't be confirmed is flagged.

## Bottom line

**Technically doable, with caveats.** Russia is on the list of countries where a developer account can be registered, and free apps from Russia can be published. Three obstacles stand in the way: the $25 fee can't be paid with a Russian card; a separate, trimmed-down "play" build flavor is needed; and since 2026-08-31, Play only accepts targetSdk 36.

**The main risk isn't technical**: for a personal account, Play publicly shows the legal name, country, and email — right next to a DPI-circumvention feature. Russia has banned promoting circumvention tools since 2024-03-01 ([Kyiv Post](https://www.kyivpost.com/post/28993)). That's a personal legal risk; settle it before paying.

## 1. A developer account from Russia

- **Registration.** Russia is ✔ for both a developer account and a merchant account ([9306917](https://support.google.com/googleplay/android-developer/answer/9306917)). Documents: passport, driver's license, or residence permit; proof of address via a utility bill, bank statement, or insurance document ([15633622](https://support.google.com/googleplay/android-developer/answer/15633622?hl=en&co=GENIE.CountryCode%3DRU)).
- **Money.** Play billing for Russian users has been suspended since 2022-03-10; free apps remain available ([11950272](https://support.google.com/googleplay/android-developer/answer/11950272)). Since 2024-12-26, seller services are disabled for developers with a Russian billing account, but "You can still publish new free apps and update existing free apps" ([15685001](https://support.google.com/googleplay/android-developer/answer/15685001)).
- **The $25 fee, one-time.** Visa, Mastercard, Amex, Visa Electron; prepaid cards don't work ([9875040](https://support.google.com/googleplay/android-developer/answer/9875040)). Mir isn't accepted, and Russian Visa/Mastercard cards fail international authorization. If verification is denied, the fee isn't refunded ([vc.ru](https://vc.ru/services/3002328-oplata-google-play-console-v-rossii-i-belarusi)). Registering under a foreign country while holding Russian documents can get the account suspended ([skillmake](https://skillmake.ru/articles/google-play-iz-rf)).
- **The realistic path:** profile country Russia, Russian documents, payment via a foreign bank's card in your own name or through an intermediary. *Unconfirmed:* whether Google accepts a Russian billing profile paired with a foreign card — there's no official answer.

## 2. Requirements for a new personal account

- **Closed testing**: for personal accounts created after 2023-11-13 — at least **12 testers for 14 continuous days**, then a questionnaire and up to 7 days of review ([14151465](https://support.google.com/googleplay/android-developer/answer/14151465)).
- **Device check**: a physical, non-rooted Android 10+ device with the Play Console app installed ([14316361](https://support.google.com/googleplay/android-developer/answer/14316361)).
- **targetSdk 36** is required for new apps and updates as of 2026-08-31 ([11926878](https://support.google.com/googleplay/android-developer/answer/11926878)); currently 35.
- **16 KB page size** is required for 64-bit `.so` files as of 2025-11-01 ([blog](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)). *Fixed in `e18c621`*: `libbyedpi.so` was the only one still aligned to 4 KB.
- **Signing**: an AAB with Play App Signing; your own key can be uploaded via PEPK ([9842756](https://support.google.com/googleplay/android-developer/answer/9842756)).

## 3. Android Developer Verification — it reaches beyond Play too

- **Timeline** ([official](https://developer.android.com/developer-verification), [guides](https://developer.android.com/developer-verification/guides)): August 2026 — the API, limited distribution, and the "advanced flow"; **2026-09-30** — enforced in Brazil, Indonesia, Singapore, Thailand; **2027** — every certified device worldwide.
- **Right now** it only touches installs from 7 stores (Play, Galaxy Store, GetApps, HONOR, OPPO, vivo, Palm): "if users sideload your app directly, these new verification requirements won't apply to your app yet" ([FAQ](https://developer.android.com/developer-verification/guides/faq)). Installing the APK from GitHub is unaffected until 2027.
- **Cost**: Full Distribution — $25, passport, address, phone number ([full distribution](https://developer.android.com/developer-verification/guides/full-distribution)). A Play Console account also covers apps distributed outside Play — one fee covers both.
- **A free tier** exists, but only for **20 devices** via QR code or link ([limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)) — no good for a public release.
- **Advanced flow**: Developer options → "Apps from unverified developers" → set a screen lock → reboot → **a 24-hour wait** → grant permission for 7 days or permanently; then "Install anyway" on every install, including updates. Installing via adb is exempt ([Android Authority](https://www.androidauthority.com/google-android-advanced-flow-sideloading-rollout-begins-3700073/)).
- **Russia**: sanctioned countries are excluded from verification, but Russia is listed as supported for registration — verification will likely reach it too in 2027. *Inference, unconfirmed.*
- **Channels**: for GitHub, the package `io.github.bropines.tailscaled` and the SHA-256 of the `tailsocks.jks` key need registering by 2027 (ownership is proven with a token-carrying APK, [ADC](https://developer.android.com/developer-verification/guides/android-developer-console)); IzzyOnDroid distributes author-signed APKs, so registering the key covers it too; F-Droid signs with its own key, leaving only an appeal as an option, and F-Droid itself opposes the program ([letter](https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html)).

## 4. Play policies, point by point

| Item | Rule | For TailSocks |
|---|---|---|
| VpnService | "Network-related tools (for example, remote access)" are allowed; requires a declaration, a video up to 90s, a store-listing description, end-to-end tunnel encryption ([12564964](https://support.google.com/googleplay/android-developer/answer/12564964)) | ✅ all traffic only enters the tunnel with an exit node selected, i.e. over WireGuard |
| FGS type | `specialUse` is checked against `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; `systemExempted` is allowed for VPN ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)); each type needs a description and a video ([13392821](https://support.google.com/googleplay/android-developer/answer/13392821)) | ⚠️ the official Tailscale app uses `systemExempted` ([manifest](https://github.com/tailscale/tailscale-android/blob/main/android/src/main/AndroidManifest.xml)) — same plan for `TunVpnService` |
| QUERY_ALL_PACKAGES | the allowed list covers search, antivirus, file managers, browsers; VPN isn't mentioned ([10158779](https://support.google.com/googleplay/android-developer/answer/10158779)) | ⚠️ there's a precedent (Tailscale), but it isn't a rule; a `<queries>` filter with LAUNCHER is safer |
| MANAGE_EXTERNAL_STORAGE | limited to file managers, backup apps, antivirus, search, encryption, migration tools ([10467955](https://support.google.com/googleplay/android-developer/answer/10467955)) | ❌ sharing all of storage through Taildrive doesn't qualify; Syncthing left Play over exactly this ([It's FOSS](https://itsfoss.com/news/syncthing-android-app-no-more/)) |
| Self-updating | "may not modify, replace, or update itself using any method other than Google Play's update mechanism" ([9888379](https://support.google.com/googleplay/android-developer/answer/9888379)) | ❌ the built-in GitHub updater has to go |
| Running your own binary as a child process | only downloading executable code from outside Play is banned ([9888379](https://support.google.com/googleplay/android-developer/answer/9888379)) | ✅ exec'ing from `nativeLibraryDir` is fine (syncthing-android did the same) |
| DPI circumvention | no specific ban exists; Psiphon and Intra (ClientHello fragmentation) are both on Play | ✅ ByeDPI runs in-process and only for the control channel |
| Root | no ban; AFWall+ is on Play | ✅ / ⚠️ disable writing the CLI to `/system/bin` |
| Proxying for third parties | allowed only if it's the app's primary function ([9888379](https://support.google.com/googleplay/android-developer/answer/9888379)) | ✅ |
| Trademark | can't imply a relationship with another company ([9888374](https://support.google.com/googleplay/android-developer/answer/9888374)) | ⚠️ "Tailscale" stays out of the title; the description reads "unofficial client for Tailscale" |
| Exact alarms | only `USE_EXACT_ALARM` is restricted ([16558241](https://support.google.com/googleplay/android-developer/answer/16558241)) | ✅ we use `SCHEDULE_EXACT_ALARM` |

## 5. What the "play" flavor needs, in priority order

1. ~~16 KB alignment for `libbyedpi.so`~~ — done.
2. targetSdk 36 and an AAB build with Play App Signing; verify on the internal track that `libtailscale.so` extracts into `nativeLibraryDir` (`useLegacyPackaging = true`).
3. Remove the updater: `REQUEST_INSTALL_PACKAGES`, the GitHub check and download, and the "unknown sources" permission entry.
4. Drop `MANAGE_EXTERNAL_STORAGE`: Taildrive limited to app-private folders or ones picked through SAF (*whether SAF works with the daemon is unverified*).
5. `QUERY_ALL_PACKAGES` → a `<queries>` filter with LAUNCHER.
6. `TunVpnService` → `systemExempted`; VPN and FGS declarations with video, prominent disclosure, a privacy policy, and the Data safety form.
7. A separate `applicationId` (e.g. `.play`), so the trimmed Play build doesn't try to update the full GitHub build; register it for developer verification too.

**Money**: $25 one-time (plus an intermediary's cut if there's no foreign card of your own). **Calendar time**: 4–7 weeks (verification, 14 days of testing, up to 7 days for production, declaration review). **Engineering time**: 1–2 weeks for targetSdk 36 and the flavor.
