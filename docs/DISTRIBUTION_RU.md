# Распространение: F-Droid, IzzyOnDroid, Obtainium

*English version: [DISTRIBUTION.md](DISTRIBUTION.md)*

Исследование от 2026-09-30; номера строк — по коммиту `730ff4e`. Что уже подготовлено:

- `fastlane/metadata/android/{en-US,ru}/` — название, короткое и полное описание, `changelogs/README.txt` (как назвать файл списка изменений), `images/README.txt` (откуда брать скриншоты). Их читают и F-Droid, и IzzyOnDroid.
- `fastlane/.gitignore` — корневой `.gitignore:68` игнорирует `*.txt`, без этого файла тексты не попадут в коммит.
- [`distribution/io.github.bropines.tailscaled.yml`](distribution/io.github.bropines.tailscaled.yml) — черновик рецепта для fdroiddata, все допущения помечены `ASSUMPTION` / `OPEN` / `BLOCKER`.

Полное описание честно говорит, что приложение при каждом запуске спрашивает GitHub о релизе. Когда проверка станет опциональной, эту фразу нужно убрать в обоих языках.

## Итог

| Канал | Вердикт | Что мешает | Работы |
|---|---|---|---|
| GitHub Releases | работает | — | — |
| Obtainium | работает уже сейчас | ничего; хэш в имени APK лучше убрать | 15 минут на бейдж |
| F-Droid, подпись F-Droid | реально | пять правок сборки и опциональная проверка обновлений | 1–2 дня + очередь ревью (недели) |
| F-Droid, воспроизводимая сборка (подпись автора) | реально, но дорого | CI недетерминирован: пути, время, NDK, gomobile | ещё 2–4 дня, исход не гарантирован |
| IzzyOnDroid | технически почти готов, по правилам — скорее отказ | политика против кода, написанного ИИ; AppFunctions | подавать ли — решение автора |

Порядок: бейдж Obtainium сейчас → мелкие правки, полезные всем каналам (ниже, пункты 1, 5, 6, 8 и `dependenciesInfo`) → MR в F-Droid → воспроизводимая сборка отдельным заходом.

## Obtainium

Добавляется по ссылке на репозиторий. Опция *Attempt to filter APKs by CPU architecture* (`autoApkFilterByArch`, для новых приложений включена) ищет имя ABI в имени файла, поэтому из пяти APK релиза выбирается `…-arm64-v8a-…`. На x86 под `x86` попадает и `x86_64` — пользователь выберет сам. Версию Obtainium сравнивает тег (`v4.5.2`) с `versionName`, который с 4.5.2 — просто версия (`4.5.2`).

Бейдж для README (картинку `assets/graphics/badge_obtainium.png` из репозитория Obtainium положить к себе — хотлинк они просят не делать):

```markdown
[<img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="48">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/bropines/tailsocks)
```

По желанию — добавить конфиг в общий каталог https://apps.obtainium.imranr.dev (PR/issue в `ImranR98/apps.obtainium.imranr.dev`).

## F-Droid (основной репозиторий)

**Совместимость с политикой.** Лицензия BSD-3-Clause подходит. Весь `releaseRuntimeClasspath` проверен: AndroidX, Material, OkHttp, kotlinx, Guava и их зависимости, ни GMS, ни Firebase; сигнатуры несвободных библиотек сканера F-Droid (suss) ни с чем не совпали. В git нет собранных бинарников, кроме `gradle-wrapper.jar` (разрешён) и `wintun.dll` в подмодуле hev-socks5-tunnel (сканер `.dll` без бита исполнения не трогает).

**Прецеденты.** Официальный Tailscale (`com.tailscale.ipn`) собирается в F-Droid: Go компилируется из исходников (srclib, bootstrap — `golang-go` из trixie-backports), модули Go качаются на шаге `build`, NDK закреплён. Анти-функция у него одна — *Tracking* за отправку отладочных логов; у нас она вырезана (`ts_omit_logtail`, `appctr/build.sh:111`; `TS_NO_LOGS_NO_SUPPORT=true`, `appctr/daemon.go:114`). sing-box (`io.nekohasekai.sfa`) — gomobile + Go из srclib + воспроизводимая сборка; SocksTun (`hev.sockstun`) — hev-socks5-tunnel, тоже воспроизводимый. Рецептов ByeDPI/ByeByeDPI в fdroiddata нет.

**Анти-функции, которые могут повесить.**

- *Tracking* — по определению F-Droid сюда входят «проверки обновлений без вашего ведома». Снято: в сборке для F-Droid (`-PselfUpdate=false`) проверки обновлений нет вовсе, а в остальных проверка при запуске — настройка.
- *NonFreeNet* — на усмотрение ревьюера: сервер по умолчанию и консоль Admin API — проприетарный сервис Tailscale; первый заменяется своим Headscale (поле login server), вторая — нет. У `com.tailscale.ipn` этой метки нет, это аргумент.
- Самообновление: политика запрещает скачивать исполняемый код. Снято так же: с `-PselfUpdate=false` в «О приложении» нет ни проверки, ни скачивания, ни установки, а релизный манифест убирает `REQUEST_INSTALL_PACKAGES`.

**Что мешало сборке** (номер — для ссылок). **С 4.5.2 исправлены все девять**; рецепту в [`distribution/io.github.bropines.tailscaled.yml`](distribution/io.github.bropines.tailscaled.yml) не нужен `sed`. Пункт 5 стал файлом `version.properties` (`VERSION_NAME=4.5.2`, `VERSION_CODE=4050200`; его читают Gradle и `UpdateCheckData` F-Droid, хэш переехал в `BuildConfig.GIT_HASH`); пункт 7 закреплён в `build.sh` на версии anet из `appctr/go.mod`.

| # | Где | Что | Как чинить |
|---|---|---|---|
| 1 | `app/build.gradle.kts:340-366` | релиз не упаковывается без keystore, а F-Droid собирает неподписанный — сборка падает | свойство Gradle, например `-PallowUnsignedRelease` (в рецепте — `gradleprops`); пока — `sed` в рецепте |
| 2 | `appctr/build.sh:64` | исходники Tailscale скачиваются `curl … \| tar` без проверки | sha256 рядом с `TAILSCALE_VERSION` или переменная `TS_TARBALL`; пока — `sed` + `sha256sum -c` в рецепте |
| 3 | `appctr/build.sh:100` (+ `appctr/go.mod:3`, `.github/workflows/android.yml:49`) | `GOTOOLCHAIN=auto` скачивает бинарный Go 1.26.6 | `GOTOOLCHAIN=${GOTOOLCHAIN:-auto}`; в рецепте — srclib `go@go1.26.6` и `local` |
| 4 | `.github/workflows/android.yml:74-75` | `gomobile@latest` и `gomobile init`, который ставит `gobind@latest` | ставить `gomobile` и `gobind` той версии, что в `appctr/go.mod`; `init` не нужен |
| 5 | `app/build.gradle.kts:11-18`, `:62`, `:115` | `versionCode` = число коммитов + 502, `versionName` с хэшем — F-Droid не может ни предсказать версию, ни обновлять рецепт сам | `version.properties` (`VERSION_NAME=4.5.0`, `VERSION_CODE=4050000` — больше нынешних ~1500, коллизий нет); хэш — в `BuildConfig` для экрана «О приложении» |
| 6 | `MainActivity.kt:230`, `:416-437` | тихая проверка обновлений | переключатель, по умолчанию выключен, с текстом «скачивается с github.com/bropines/tailsocks» |
| 7 | `appctr/build.sh:109` | `go mod tidy` в дереве Tailscale дописывает `github.com/wlynxg/anet` той версии, что свежая на день сборки | закрепить `require` в патче 06, убрать `tidy` |
| 8 | `app/src/main/jni/byedpi/` | вендорный ByeDPI (MIT) без LICENSE; `readme.md:298`, `:355` ссылаются на несуществующий `hufyhang/byedpi` | положить LICENSE из https://github.com/hufrea/byedpi, исправить ссылки |
| 9 | `app/build.gradle.kts` (нет `ndkVersion`), `android.yml:103` | NDK нигде не закреплён: локально 28.2, в CI — первый найденный | один NDK в `ndkVersion`, в CI и в `build.sh` |

Не блокер: из-за `jvmToolchain(17)` рецепт ставил JDK 17 из bookworm на сборочный сервер Debian trixie, как у SocksTun; ревьюер попросил JDK 21. С 4.6.0 Kotlin собирает байткод Java 17 на любом JDK, которым запущен Gradle, а CI работает на 21 — сборки на JDK 17 и JDK 21 дали побайтно одинаковый APK; рецепт 4.5.3 заменяет строку тулчейна `sed`-ом в `prebuild`. У каждого ABI свой `versionCode` с 4.5.3, так что F-Droid раздаёт APK по ABI.

**Шаги.**

1. ~~Исправить пункты выше и выпустить релиз~~ — сделано в 4.5.2.
2. Форкнуть https://gitlab.com/fdroid/fdroiddata, положить черновик в `metadata/io.github.bropines.tailscaled.yml`, поправить `versionName`/`versionCode`/`commit`, убрать ставшие ненужными `sed`.
3. `fdroid lint`, `fdroid rewritemeta`, `fdroid build -v -l io.github.bropines.tailscaled` — локально или в GitLab CI форка. `rewritemeta` выбрасывает комментарии черновика; CI fdroiddata требует его канонический вид.
4. Открыть MR — сделано: [fdroiddata!50707](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50707), 1 октября 2026, v4.5.3, зелёный pipeline, все четыре ABI воспроизвели релиз. Альтернатива — заявка в https://gitlab.com/fdroid/rfp/-/issues (медленнее). После слияния приложение появляется через 24–48 часов.

Подпись F-Droid отличается от подписи релизов на GitHub: перейти между каналами можно только через удаление, а удаление стирает профили и ключи (спасает резервная копия). Это стоит написать в README.

## Воспроизводимая сборка

**Сделано и доказано.** 1 октября 2026 собственная сборка F-Droid коммита 1c82d1c (arm64, в CI форка fdroiddata) с перенесённой на неё подписью нашей сборки из GitHub CI прошла проверку `apksigcopier compare`: APK идентичны. F-Droid публикует наш APK с нашей подписью — переходить между GitHub и F-Droid можно без переустановки, и копия из F-Droid проходит проверку разработчика Google. В рецепте есть `AllowedAPKSigningKeys` и `binary:` для каждого ABI.

Чего это стоило и что должно оставаться верным:

- **Один APK для всех каналов.** Сборка GitHub и сборка F-Droid ничем не должны отличаться: обновлятор GitHub отключается во время работы, когда приложение поставил магазин (`UpdateChannel`), а не флагом сборки.
- **Ядро на Go собирается в `/home/vagrant/build/io.github.bropines.tailscaled`** — пути сборки F-Droid, — потому что gomobile пишет абсолютный путь связываемого модуля в `libgojni.so` (директива replace в его собственном `go.mod`, до которой `-trimpath` не дотягивается). CI копирует туда checkout.
- **Закреплённый NDK и для ядра.** `ANDROID_NDK_HOME` раннера по умолчанию (r27) завалил первое сравнение ровно на трёх Go-библиотеках.
- **Ровно тот Go, что в `appctr/go.mod`** (`GOTOOLCHAIN=go1.27.1` в CI, srclib с `GOTOOLCHAIN=local` у F-Droid); `-buildvcs=false`, `-buildid=`, версия ядра со временем коммита.
- **C-библиотеки собирает ndkBuild Gradle** с `-ffile-prefix-map` — и в CI, и у F-Droid.
- **Только чистые сборки.** Инкрементальная сборка Kotlin отличается от чистой в `classes.dex`; CI и F-Droid всегда собирают начисто.
- **Имена файлов релиза без хэша** (`TailSocks-v<версия>-<abi>-release.apk`), которые находит `%v` F-Droid.

## IzzyOnDroid

Технически почти готов: лицензия, релизы с тегом и APK, подпись релизным ключом (схема v2), не debuggable. Лимит — «около 30 МБ на приложение» и на один APK; при нескольких ABI Izzy берёт arm64-v8a (`ApkMatch: arm64-v8a`, 24,4 МБ — проходит; универсальный 85 МБ — нет). fastlane теперь есть. Нужно ещё: проверку обновлений сделать опциональной (самообновление терпят, только если оно выключено по умолчанию и названо, откуда качается) и убрать `DEPENDENCY_INFO_BLOCK`.

Препятствие — политика. Цитаты из правил: «We are strongly opposed to apps which are fully or in part created by generative AI tools», «Vibe-coded apps will be rejected», «Apps acting as front-end for LLMs … or integrate with such services, will be rejected». Тексты документации допускаются, код — нет; о применении ИИ просят сообщить в заявке. В репозитории лежат `CLAUDE.md`, `agents.md`, `.skills/`, у 184 из 1000 коммитов трейлер `Co-Authored-By: Claude`, а 14 AppFunctions предназначены ассистентам вроде Gemini. Честная заявка, скорее всего, получит отказ; подавать ли — решать автору. Если подавать: issue в https://codeberg.org/IzzyOnDroid/repodata/issues, дальше проверка репозитория и APK на устройстве (VirusTotal, мониторинг сети), обновления подтягиваются ежедневно из релизов GitHub.

## Верификация разработчиков Google (2026)

- Август 2026 — API, аккаунты ограниченного распространения (до 20 устройств, без документа и взноса) и «продвинутый путь» для опытных пользователей. С 30 сентября 2026 (сегодня) на сертифицированных устройствах Бразилии, Индонезии, Сингапура и Таиланда ставятся только приложения проверенных разработчиков, в 2027 — по всему миру.
- Установка через ADB остаётся. Продвинутый путь: режим разработчика, ожидание 24 часа, повторная аутентификация.
- Регистрация — Android Developer Console: имя, адрес, возможно документ, взнос $25; владение приложением доказывается APK, подписанным своим ключом. Проверка привязана к имени пакета и ключу.
- F-Droid (открытое письмо от 24.02.2026): «We unequivocally advise against signing up for this program, now or ever». F-Droid регистрироваться не будет, поэтому подписанные им APK в этих странах ставятся только продвинутым путём или через ADB.
- IzzyOnDroid на главной: «The free Android world is under threat – and IzzyOnDroid with it» (ссылка на keepandroidopen.org). Izzy раздаёт APK, подписанные автором, так что их судьба — это регистрация автора.
- Для TailSocks: основная аудитория (русская документация, обход DPI) вне первой волны; вопрос встанет в 2027. Если автор зарегистрирует пакет и ключ — GitHub, Obtainium, Izzy и воспроизводимая сборка в F-Droid ставятся как обычно, сборка с подписью F-Droid — нет. Если нет — во всех каналах нужен продвинутый путь. F-Droid просит не регистрироваться; решение за автором.

## Источники

- F-Droid: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), [Anti-Features](https://f-droid.org/docs/Anti-Features/), [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/), [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/), [Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [описания и графика](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/), [форум: политика по ИИ (её нет)](https://forum.f-droid.org/t/does-f-droid-have-a-formal-policy-on-libre-ai/33279), [открытое письмо](https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html)
- Рецепты fdroiddata: [com.tailscale.ipn](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.tailscale.ipn.yml), [io.nekohasekai.sfa](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/io.nekohasekai.sfa.yml), [hev.sockstun](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/hev.sockstun.yml), [srclibs/go.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/srclibs/go.yml)
- IzzyOnDroid: [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), [New App Inclusions](https://izzyondroid.org/contributing/NewAppInclusions/), [Fastlane](https://izzyondroid.org/docs/general/Fastlane/), [YAML Metadata](https://izzyondroid.org/docs/general/YamlMetadata/), [FAQ](https://izzyondroid.org/faq/), [проверки APK](https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en)
- Obtainium: [Deep Links](https://wiki.obtainium.imranr.dev/deep_links/), [каталог конфигов](https://apps.obtainium.imranr.dev/), `lib/services/apk_filter_service.dart` в [репозитории](https://github.com/ImranR98/Obtainium)
- Google: [Android developer verification](https://developer.android.com/developer-verification), [The Hacker News, 2026-06](https://thehackernews.com/2026/06/google-sets-sept-30-deadline-for.html)
