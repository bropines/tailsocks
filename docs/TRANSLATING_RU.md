# Перевод TailSocks

*English version: [TRANSLATING.md](TRANSLATING.md)*

TailSocks переводится на [Hosted Weblate](https://hosted.weblate.org/). Переводчики работают в браузере, с машинными подсказками для начала; Weblate присылает результат в этот репозиторий пулл-реквестами. Исходный язык — английский, русский ведётся вместе с ним, все остальные приходят из Weblate.

## Переводчикам

Откройте проект на Hosted Weblate, выберите язык (или начните новый) и переводите. Строки, которые являются названиями брендов или протоколов — Taildrop, Taildrive, MagicDNS, DNS, Serve, Funnel, — и технические заготовки вроде адресов помечены как непереводимые и не показываются.

Несколько договорённостей:
- Плейсхолдеры сохраняйте в точности: `%1$s`, `%2$d`, `\n`.
- Пишите коротко. Пояснения на экране свёрнуты до одной-двух строк; перевод вдвое длиннее английского обрежется.
- «Tailscale» и названия его продуктов не переводятся; приложение — неофициальный клиент и никогда не «приложение Tailscale».

## Настройка проекта (сопровождающему, один раз)

1. **Создать проект** на hosted.weblate.org → *Add new translation project*. Название `TailSocks`, слаг `tailsocks`, сайт — репозиторий на GitHub. Лицензия перевода: **BSD-3-Clause**, та же, что у кода, — стоящая по умолчанию Proprietary закрывает бесплатный план Libre. Проект начинается в пробном периоде; когда он создан, попросите план **Libre** (для открытых проектов со свободной лицензией) на его странице оплаты.
2. **Основной компонент**, *From version control*:
   - Репозиторий: `https://github.com/bropines/tailsocks.git`, ветка `main`.
   - Отправка: *GitHub pull request* (Hosted Weblate пушит в свой форк и открывает PR; прав на запись в этот репозиторий ему не нужно).
   - Формат файлов: **Android String Resource**.
   - Маска файлов: `app/src/main/res/values-*/strings.xml`
   - Базовый файл: `app/src/main/res/values/strings.xml`
   - Шаблон для новых переводов: тот же базовый файл. Стиль кодов языков: *Android*.
3. **Остальные файлы строк** — экраны держат свои строки в `strings_<область>.xml` рядом с `strings.xml`. Добавьте основному компоненту аддон *Component discovery*:
   - Регулярное выражение: `app/src/main/res/values-(?P<language>[^/]*)/(?P<component>strings_[^/]*)\.xml`
   - Имя компонента: `{{ component }}`
   - Базовый файл: `app/src/main/res/values/{{ component }}.xml`, он же шаблон новых переводов.
   - Формат: Android String Resource.
4. **Вебхук**, чтобы Weblate видел каждый пуш: *Settings → Webhooks → Add webhook* этого репозитория, адрес `https://hosted.weblate.org/hooks/github/`, тип `application/json`, только событие push.
5. **Аддоны**, которые стоит включить: *Cleanup translation files* (убирает строки, удалённые из исходника) и *Squash Git commits* (один коммит на язык в каждом PR).
6. **Бейдж** для README, когда проект появится:

   ```markdown
   [![Translation status](https://hosted.weblate.org/widget/tailsocks/svg-badge.svg)](https://hosted.weblate.org/engage/tailsocks/)
   ```

## Разработчикам, добавляющим строки

Каждую новую строку добавляйте на английском в `values/` и на русском в `values-ru/`; остальное придёт из Weblate. Экрану с большим числом строк заведите свой `strings_<область>.xml`, а не раздувайте `strings.xml`. Помечайте строку `translatable="false"` только если в ней нет языка: название бренда или протокола, адрес, формат без слов.
