# Translating TailSocks

*Русская версия: [TRANSLATING_RU.md](TRANSLATING_RU.md)*

TailSocks is translated on [Hosted Weblate](https://hosted.weblate.org/). Translators work in the browser, with machine suggestions to start from; Weblate sends the result back to this repository as pull requests. English is the source, Russian is maintained alongside it, and every other language comes from Weblate.

## For translators

Open the project on Hosted Weblate, pick a language (or start a new one) and translate. Strings that are brand or protocol names — Taildrop, Taildrive, MagicDNS, DNS, Serve, Funnel — and technical placeholders such as addresses are marked non-translatable and do not appear.

A few conventions:
- Keep placeholders exactly: `%1$s`, `%2$d`, `\n`.
- Keep it short. Explanations on screen are folded to one or two lines; a translation twice the length of the English is cut off.
- "Tailscale" and its product names stay as they are; the app is an unofficial client, never "the Tailscale app".

## Setting the project up (maintainer, once)

1. **Create the project** on hosted.weblate.org → *Add new translation project*. Name `TailSocks`, slug `tailsocks`, website the GitHub repository. Translation licence: **BSD-3-Clause**, the code's own — the default, Proprietary, rules out the free Libre plan. The project starts in a trial; once it exists, ask for the **Libre** plan (public, libre-licensed projects) from its billing page.
2. **Main component**, *From version control*:
   - Repository: `https://github.com/bropines/tailsocks.git`, branch `main`.
   - Push: choose *GitHub pull request* (Hosted Weblate pushes to its own fork and opens PRs; nothing needs write access to this repository).
   - File format: **Android String Resource**.
   - File mask: `app/src/main/res/values-*/strings.xml`
   - Monolingual base language file: `app/src/main/res/values/strings.xml`
   - Template for new translations: the same base file. Language code style: *Android*.
3. **The other string files** — screens keep their strings in `strings_<area>.xml` next to `strings.xml`. Add the *Component discovery* add-on to the main component:
   - Regular expression: `app/src/main/res/values-(?P<language>[^/]*)/(?P<component>strings_[^/]*)\.xml`
   - Component name: `{{ component }}`
   - Base file: `app/src/main/res/values/{{ component }}.xml`, also as the template for new translations.
   - File format: Android String Resource.
4. **Webhook**, so Weblate sees every push: this repository's *Settings → Webhooks → Add webhook*, payload URL `https://hosted.weblate.org/hooks/github/`, content type `application/json`, just the push event.
5. **Add-ons** worth enabling: *Cleanup translation files* (drops strings the source removed) and *Squash Git commits* (one commit per language per PR).
6. **Badge** for the README once the project exists:

   ```markdown
   [![Translation status](https://hosted.weblate.org/widget/tailsocks/svg-badge.svg)](https://hosted.weblate.org/engage/tailsocks/)
   ```

## For contributors adding strings

Add every new string in English to `values/` and in Russian to `values-ru/`; Weblate carries the rest. Give a screen with many strings its own `strings_<area>.xml` rather than growing `strings.xml`. Mark a string `translatable="false"` only when it is not language at all: a brand or protocol name, an address, a format with no words.
