package io.github.bropines.tailscaled.core

import java.util.Locale

/**
 * The languages the app can be switched to, besides the system's (`app_locale`).
 *
 * English is the source and Russian is kept alongside it; every other language comes from
 * Weblate and is offered once someone has translated at least one string of it. A language
 * Weblate has only opened — empty files, as Japanese on 2026-10-28 — waits: picking it would
 * change nothing. AppLanguagesTest fails when `res/values-*` and this list disagree, so a
 * language Weblate brings is added here before a release.
 *
 * Tags are BCP 47, the form `app_locale` stores and Locale.forLanguageTag reads:
 * `values-zh-rTW` answers `zh-TW`.
 */
object AppLanguages {
    /** `app_locale` for "follow the system". */
    const val SYSTEM = "sys"

    val TAGS: List<String> = listOf("en", "ru", "zh-TW", "ko")

    /**
     * [tag]'s name in its own language — what someone who reads only that language looks for
     * in a list. Written out for the languages offered: the platform calls zh-TW "中文 (台灣)",
     * and the script is the point of that one.
     */
    fun nativeName(tag: String): String = NATIVE[tag] ?: Locale.forLanguageTag(tag).let { locale ->
        locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /**
     * [tag]'s name in [reader]'s language, or null where that says nothing [nativeName] has
     * not: English to an English reader.
     */
    fun nameFor(tag: String, reader: Locale): String? {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(reader).replaceFirstChar { it.titlecase(reader) }
        return name.takeIf { it.isNotBlank() && it != nativeName(tag) && it != tag }
    }

    private val NATIVE = mapOf(
        "en" to "English",
        "ru" to "Русский",
        "zh-TW" to "繁體中文",
        "ko" to "한국어",
        "ja" to "日本語",
    )
}
