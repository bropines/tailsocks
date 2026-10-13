package io.github.bropines.tailscaled.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The readings of a typed word: folded, in the other layout, in the other alphabet, without its ending. */
class SearchTextTest {

    @Test
    fun aTextIsCutIntoFoldedWordsThatKeepTheirPlace() {
        val words = SearchText.words("Use MagicDNS — Тёмная тема, v1.2")
        assertEquals(listOf("use", "magicdns", "темная", "тема", "v1", "2"), words.map { it.text })
        // A folded word is as long as the word it came from, so its place is the original's.
        val dark = words[2]
        assertEquals("Тёмная", "Use MagicDNS — Тёмная тема, v1.2".substring(dark.start, dark.end))
    }

    @Test
    fun anAccentedLatinLetterIsItsLetterAndCyrillicKeepsItsOwn() {
        assertEquals(listOf("cafe", "reseau"), SearchText.words("Café Réseau").map { it.text })
        // «й» is a letter of its own, not «и» with a mark to drop.
        assertEquals(listOf("выходной"), SearchText.words("Выходной").map { it.text })
    }

    @Test
    fun aHyphenatedPairIsAlsoOneWordForTheCatalogue() {
        assertEquals(listOf("wi", "fi", "wifi"), SearchText.words("Wi-Fi", compounds = true).map { it.text })
        assertEquals(listOf("always", "on", "alwayson", "vpn"), SearchText.words("always-on VPN", compounds = true).map { it.text })
        // Only a hyphen joins, and a query is taken as typed.
        assertEquals(listOf("wi", "fi"), SearchText.words("Wi-Fi").map { it.text })
        assertEquals(listOf("exit", "node"), SearchText.words("exit node", compounds = true).map { it.text })
    }

    @Test
    fun latinKeysReadInTheRussianLayout() {
        val cases = mapOf(
            "ghjrcb" to "прокси",
            "j,[jl" to "обход",
            "ntvf" to "тема",
            "zpsr" to "язык",
            ",kjrbhjdrb" to "блокировки",
            "GHJRCB" to "прокси",
            "ds[jlyjq eptk" to "выходной узел",
            "\"rcgjhn" to "экспорт",
        )
        for ((typed, meant) in cases) assertEquals(typed, meant, SearchText.inCyrillicLayout(typed))
        assertNull(SearchText.inCyrillicLayout("прокси"))
        assertNull(SearchText.inCyrillicLayout("443"))
    }

    @Test
    fun cyrillicKeysReadInTheLatinLayout() {
        val cases = mapOf(
            "мзт" to "vpn",
            "вты" to "dns",
            "зкщчн" to "proxy",
            "учше тщву" to "exit node",
            "ифслгз" to "backup",
            "МЗТ" to "vpn",
        )
        for ((typed, meant) in cases) assertEquals(typed, meant, SearchText.inLatinLayout(typed))
        assertNull(SearchText.inLatinLayout("vpn"))
    }

    @Test
    fun aWordAndItsTransliterationSoundTheSame() {
        val pairs = listOf(
            "прокси" to "proksi", "прокси" to "proxy",
            "настройки" to "nastroiki", "настройки" to "nastroyki",
            "днс" to "dns", "впн" to "vpn", "порт" to "port",
            "обход" to "obhod", "обход" to "obkhod",
            "автозапуск" to "avtozapusk",
            "язык" to "yazyk", "ключ" to "klyuch",
            "журнал" to "zhurnal", "режим" to "rezhim", "режим" to "rejim",
            "выходной" to "vyhodnoi", "выходной" to "vykhodnoy",
            "туннель" to "tunnel", "сокс" to "socks", "блокировка" to "blokirovka",
            "тема" to "tema", "логи" to "logi", "бэкап" to "bekap", "токен" to "token",
        )
        for ((a, b) in pairs) assertEquals("$a / $b", SearchText.skeleton(a), SearchText.skeleton(b))
    }

    @Test
    fun aFormOfAWordBeginsWithItsStem() {
        val forms = listOf(
            "блокировку" to "блокировки", "настройках" to "настройки", "прокси" to "прокси",
            "маршрутов" to "маршруты", "разрешениями" to "разрешения", "темы" to "тема", "сети" to "сеть",
            "ports" to "port", "routes" to "route", "logs" to "logs", "excluded" to "exclude",
            "routing" to "route",
        )
        for ((typed, word) in forms) {
            val stem = SearchText.stem(typed)
            assertEquals("$typed → $stem / $word", true, word.startsWith(stem))
        }
        assertEquals("proxy", SearchText.stem("proxies"))
        // Too short to cut, or nothing to cut.
        assertEquals("dns", SearchText.stem("dns"))
        assertEquals("узел", SearchText.stem("узел"))
        assertEquals("address", SearchText.stem("address"))
    }

    @Test
    fun slipsAreCountedUpToTheBound() {
        fun whole(a: String, b: String, max: Int = 2) = SearchText.slips(a, b, max).first
        fun beginning(a: String, b: String, max: Int = 2) = SearchText.slips(a, b, max).second
        assertEquals(0, whole("proxy", "proxy"))
        assertEquals(1, whole("prxy", "proxy"))       // a missing letter
        assertEquals(1, whole("proxyy", "proxy"))     // an extra one
        assertEquals(1, whole("proxi", "proxy"))      // a wrong one
        assertEquals(1, whole("porxy", "proxy"))      // two swapped
        assertEquals(2, whole("porxi", "proxy"))
        assertEquals(1, whole("блкировки", "блокировки"))
        assertEquals(SearchText.TOO_FAR, whole("tunnel", "proxy"))
        assertEquals(SearchText.TOO_FAR, whole("porxi", "proxy", max = 1))
        // A word still being typed, with a slip in it: "cesnor" for "censorship".
        assertEquals(1, beginning("cesnor", "censorship"))
        assertEquals(SearchText.TOO_FAR, whole("cesnor", "censorship"))
        assertEquals(0, beginning("cens", "censorship"))
    }
}
