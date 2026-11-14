package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Locale

class AppLanguagesTest {
    private val res: File = listOf(File("."), File("app")).first { File(it, "src/main/res/values/strings.xml").isFile }
        .let { File(it, "src/main/res") }

    /** values-zh-rTW → zh-TW: the tag a resource directory answers. */
    private fun tagOf(directory: String): String? {
        val qualifier = directory.removePrefix("values-")
        val match = Regex("^([a-z]{2,3})(?:-r([A-Z]{2}))?$").matchEntire(qualifier) ?: return null
        return listOfNotNull(match.groupValues[1], match.groupValues[2].ifEmpty { null }).joinToString("-")
    }

    /** Whether the directory holds at least one translated string — a language Weblate only opened holds none. */
    private fun translates(directory: File): Boolean =
        directory.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
            .any { Regex("<(string|plurals) name=").containsMatchIn(it.readText()) }

    @Test
    fun everyTranslatedLanguageIsOffered() {
        val translated = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .mapNotNull { dir -> tagOf(dir.name)?.takeIf { translates(dir) } }
            .sorted()
        assertEquals(
            "AppLanguages.TAGS must list English and every values-* language with a translated string",
            (translated + "en").sorted(),
            AppLanguages.TAGS.sorted()
        )
    }

    @Test
    fun androidOffersTheSameLanguages() {
        val config = File(res, "xml/locales_config.xml").readText()
        val listed = Regex("""<locale\s+android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals("res/xml/locales_config.xml must list exactly AppLanguages.TAGS", AppLanguages.TAGS.sorted(), listed.sorted())
    }

    @Test
    fun aPlatformTagFindsItsLanguage() {
        assertEquals("ru", AppLanguages.fromPlatform("ru-RU"))
        assertEquals("zh-TW", AppLanguages.fromPlatform("zh-Hant-TW"))
        assertEquals("zh-TW", AppLanguages.fromPlatform("zh-HK"))
        assertEquals("zh-CN", AppLanguages.fromPlatform("zh-Hans-CN"))
        assertEquals("zh-CN", AppLanguages.fromPlatform("zh"))
        assertEquals("ko", AppLanguages.fromPlatform("ko-KR"))
        assertEquals("de-DE", AppLanguages.fromPlatform("de-DE"))
    }

    @Test
    fun namesAreTheLanguagesOwn() {
        assertEquals("Русский", AppLanguages.nativeName("ru"))
        assertEquals("繁體中文", AppLanguages.nativeName("zh-TW"))
        assertEquals("한국어", AppLanguages.nativeName("ko"))
        assertNull(AppLanguages.nameFor("en", Locale.ENGLISH))
        assertEquals("Korean", AppLanguages.nameFor("ko", Locale.ENGLISH))
    }
}
