# TailSocks в Google Play: можно ли и сколько стоит

*English version: [GOOGLE_PLAY.md](GOOGLE_PLAY.md)*

Исследование на 2026-09-30. Каждое утверждение о политике — со ссылкой на источник; то, что подтвердить не удалось, помечено.

## Итог

**Технически реализуемо, но с оговорками.** Россия есть в списке стран, где можно зарегистрировать аккаунт разработчика, и бесплатные приложения из России публиковать можно. Препятствий три: взнос $25 не оплатить российской картой; нужна отдельная урезанная сборка «play»; с 31.08.2026 Play принимает только targetSdk 36.

**Главный риск не технический**: для личного аккаунта Play публично показывает юридическое имя, страну и email — рядом с функцией обхода DPI. В РФ с 01.03.2024 запрещена популяризация средств обхода блокировок ([Kyiv Post](https://www.kyivpost.com/post/28993)). Это личный юридический риск; решить до оплаты.

## 1. Аккаунт разработчика из России

- **Регистрация.** У России ✔ и для аккаунта разработчика, и для merchant ([9306917](https://support.google.com/googleplay/android-developer/answer/9306917)). Документы: паспорт, права или ВНЖ; адрес — счёт за коммуналку, выписка или страховка ([15633622](https://support.google.com/googleplay/android-developer/answer/15633622?hl=en&co=GENIE.CountryCode%3DRU)).
- **Деньги.** Биллинг Play для пользователей в РФ приостановлен с 10.03.2022, бесплатные приложения доступны ([11950272](https://support.google.com/googleplay/android-developer/answer/11950272)). С 26.12.2024 отключены seller services для разработчиков с российским счётом, но «You can still publish new free apps and update existing free apps» ([15685001](https://support.google.com/googleplay/android-developer/answer/15685001)).
- **Взнос $25, разовый.** Visa, MC, Amex, Visa Electron; предоплаченные карты не подходят ([9875040](https://support.google.com/googleplay/android-developer/answer/9875040)). «Мир» не принимается, российские Visa/MC не проходят международную авторизацию. При отказе в верификации взнос не возвращают ([vc.ru](https://vc.ru/services/3002328-oplata-google-play-console-v-rossii-i-belarusi)). Чужая страна при российских документах может кончиться блокировкой аккаунта ([skillmake](https://skillmake.ru/articles/google-play-iz-rf)).
- **Реальный путь:** страна профиля — Россия, документы российские, оплата картой иностранного банка на своё имя или через посредника. *Не подтверждено:* примет ли Google российский платёжный профиль с иностранной картой — официального ответа нет.

## 2. Требования к новому личному аккаунту

- **Закрытый тест**: для личных аккаунтов после 13.11.2023 — не меньше **12 тестеров непрерывно 14 дней**, потом анкета и проверка до 7 дней ([14151465](https://support.google.com/googleplay/android-developer/answer/14151465)).
- **Проверка устройства**: физический не-рутованный Android 10+ с приложением Play Console ([14316361](https://support.google.com/googleplay/android-developer/answer/14316361)).
- **targetSdk 36** для новых приложений и обновлений с 31.08.2026 ([11926878](https://support.google.com/googleplay/android-developer/answer/11926878)); сейчас 35.
- **16 KB page size** обязателен для 64-битных `.so` с 01.11.2025 ([блог](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)). *Исправлено в `e18c621`*: `libbyedpi.so` была единственной с выравниванием 4 KB.
- **Подпись**: AAB с Play App Signing; свой ключ можно загрузить через PEPK ([9842756](https://support.google.com/googleplay/android-developer/answer/9842756)).

## 3. Верификация разработчиков Android — касается и тех, кто не в Play

- **Сроки** ([официально](https://developer.android.com/developer-verification), [guides](https://developer.android.com/developer-verification/guides)): август 2026 — API, limited distribution и «advanced flow»; **30.09.2026** — принудительно в Бразилии, Индонезии, Сингапуре, Таиланде; **2027** — все сертифицированные устройства в мире.
- **Сейчас** касается только установок из 7 магазинов (Play, Galaxy Store, GetApps, HONOR, OPPO, vivo, Palm): «if users sideload your app directly, these new verification requirements won't apply to your app yet» ([FAQ](https://developer.android.com/developer-verification/guides/faq)). До 2027 установку APK с GitHub это не затрагивает.
- **Стоимость**: Full Distribution — $25, паспорт, адрес, телефон ([full distribution](https://developer.android.com/developer-verification/guides/full-distribution)). Аккаунт Play Console покрывает и приложения вне Play — один взнос.
- **Бесплатный тариф** есть, но на **20 устройств** по QR или ссылке ([limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)) — для публичных релизов не годится.
- **Advanced flow**: Developer options → «Apps from unverified developers» → блокировка экрана → перезагрузка → **24 часа ожидания** → разрешение на 7 дней или навсегда; затем «Install anyway» при установке, в том числе обновлений. Установка через adb освобождена ([Android Authority](https://www.androidauthority.com/google-android-advanced-flow-sideloading-rollout-begins-3700073/)).
- **Россия**: страны под санкциями из проверки исключены, но Россия числится поддерживаемой для регистрации — вероятно, в 2027 проверка затронет и её. *Вывод, не подтверждено.*
- **Каналы**: для GitHub нужно до 2027 зарегистрировать пакет `io.github.bropines.tailscaled` и SHA-256 ключа `tailsocks.jks` (владение доказывается APK с токеном, [ADC](https://developer.android.com/developer-verification/guides/android-developer-console)); IzzyOnDroid раздаёт APK с подписью автора — регистрация ключа покроет и его; F-Droid подписывает своим ключом, для него только апелляция, и сам F-Droid против программы ([письмо](https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html)).

## 4. Политики Play, пункт за пунктом

| Пункт | Правило | Для TailSocks |
|---|---|---|
| VpnService | «Network-related tools (for example, remote access)» разрешены; декларация, видео до 90 с, описание в карточке, шифрование до конца туннеля ([12564964](https://support.google.com/googleplay/android-developer/answer/12564964)) | ✅ весь трафик в туннель только при выбранной exit node, то есть через WireGuard |
| Тип FGS | `specialUse` проверяют по `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; `systemExempted` разрешён VPN ([типы FGS](https://developer.android.com/develop/background-work/services/fgs/service-types)); описание и видео на каждый тип ([13392821](https://support.google.com/googleplay/android-developer/answer/13392821)) | ⚠️ официальный Tailscale использует `systemExempted` ([манифест](https://github.com/tailscale/tailscale-android/blob/main/android/src/main/AndroidManifest.xml)) — для `TunVpnService` так же |
| QUERY_ALL_PACKAGES | в списке поиск, антивирусы, файловые менеджеры, браузеры; VPN не упомянут ([10158779](https://support.google.com/googleplay/android-developer/answer/10158779)) | ⚠️ прецедент есть (Tailscale), но правилом не является; надёжнее `<queries>` с LAUNCHER |
| MANAGE_EXTERNAL_STORAGE | только файловые менеджеры, бэкапы, антивирусы, поиск, шифрование, миграция ([10467955](https://support.google.com/googleplay/android-developer/answer/10467955)) | ❌ раздача всей памяти через Taildrive не подходит; Syncthing ушёл из Play как раз из-за этого ([It's FOSS](https://itsfoss.com/news/syncthing-android-app-no-more/)) |
| Самообновление | «may not modify, replace, or update itself using any method other than Google Play's update mechanism» ([9888379](https://support.google.com/googleplay/android-developer/answer/9888379)) | ❌ встроенный апдейтер с GitHub убрать |
| Свой бинарник дочерним процессом | запрещено только скачивать исполняемый код не из Play ([9888379](https://support.google.com/googleplay/android-developer/answer/9888379)) | ✅ exec из `nativeLibraryDir` допустим (так делал syncthing-android) |
| Обход DPI | отдельного запрета нет; Psiphon и Intra (фрагментация ClientHello) в Play есть | ✅ ByeDPI в процессе и только для управляющего канала |
| Root | запрета нет; AFWall+ в Play | ✅ / ⚠️ запись CLI в `/system/bin` отключить |
| Прокси для третьих лиц | только если это основная функция ([9888379](https://support.google.com/googleplay/android-developer/answer/9888379)) | ✅ |
| Товарный знак | нельзя намекать на связь с другой компанией ([9888374](https://support.google.com/googleplay/android-developer/answer/9888374)) | ⚠️ «Tailscale» не в заголовке; в описании «unofficial client for Tailscale» |
| Точные будильники | ограничен только `USE_EXACT_ALARM` ([16558241](https://support.google.com/googleplay/android-developer/answer/16558241)) | ✅ используется `SCHEDULE_EXACT_ALARM` |

## 5. Что нужно для флейвора «play», по приоритету

1. ~~16 KB выравнивание `libbyedpi.so`~~ — сделано.
2. targetSdk 36 и сборка AAB с Play App Signing; проверить на internal-треке, что `libtailscale.so` распаковывается в `nativeLibraryDir` (`useLegacyPackaging = true`).
3. Убрать апдейтер: `REQUEST_INSTALL_PACKAGES`, проверку и скачивание с GitHub, пункт «неизвестные источники» в разрешениях.
4. Убрать `MANAGE_EXTERNAL_STORAGE`: Taildrive — только папки приложения или выбранные через SAF (*работает ли SAF с демоном — не проверено*).
5. `QUERY_ALL_PACKAGES` → `<queries>` с LAUNCHER.
6. `TunVpnService` → `systemExempted`; декларации VPN и FGS с видео, prominent disclosure, политика конфиденциальности, Data safety.
7. Отдельный `applicationId` (например `.play`), чтобы урезанная сборка из Play не обновляла полную с GitHub; его тоже регистрировать в верификации.

**Деньги**: $25 один раз (плюс посредник, если нет своей иностранной карты). **Время по календарю**: 4–7 недель (верификация, 14 дней теста, до 7 дней на production, проверка деклараций). **Работа**: 1–2 недели на targetSdk 36 и флейвор.
