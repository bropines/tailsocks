package io.github.bropines.tailscaled.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the search understands and the order it answers in, on a small catalogue in two
 * languages. The real catalogue's own queries are in ui/SettingsSearchQueriesTest.
 */
class SearchEngineTest {

    private class Sample(
        val id: String,
        val en: String,
        val ru: String,
        val keywordsEn: String = "",
        val keywordsRu: String = "",
        val place: String = "",
        val does: String = "",
    )

    private fun entry(s: Sample) = SearchEntry(
        s.id,
        buildList {
            add(SearchField.TITLE to s.en)
            add(SearchField.TITLE to s.ru)
            for (keyword in (s.keywordsEn + "," + s.keywordsRu).split(',')) {
                if (keyword.isNotBlank()) add(SearchField.KEYWORD to keyword.trim())
            }
            if (s.place.isNotEmpty()) add(SearchField.PLACE to s.place)
            if (s.does.isNotEmpty()) add(SearchField.DESCRIPTION to s.does)
        }
    )

    private val samples = listOf(
        Sample(
            "tunnel", "Tunnel mode", "Режим туннеля",
            "vpn, tun, kill switch, always-on, whole device", "впн, тун, весь трафик",
            does = "Proxy, VPN (TUN) or Root — how traffic leaves this device",
        ),
        Sample("proxies", "Proxies", "Прокси", "socks, http, listener", "сокс, порт прокси"),
        Sample("socks_address", "SOCKS5 Address", "Адрес SOCKS5", "bind, listen, port", "порт", place = "Local proxies"),
        Sample(
            "bypass", "Censorship bypass", "Обход блокировок",
            "dpi, blocked, unblock, rkn", "блокировки, цензура, ркн, dpi",
            does = "Control plane proxy and ByeByeDPI",
        ),
        Sample("exit_node", "Exit Node", "Выходной узел", "vpn country, location", "экзит нода, страна"),
        Sample("dns", "DNS", "DNS", "resolver, names", "днс, резолвер, имена"),
        Sample("dns_proxy", "DNS Proxy Address", "Адрес DNS-прокси", place = "DNS"),
        Sample("magicdns", "Use MagicDNS", "Использовать MagicDNS", place = "DNS"),
        Sample("autostart", "Auto-start on Boot", "Автозапуск при загрузке", "startup, launch at boot", "автостарт"),
        Sample("theme", "Interface Theme", "Тема оформления", "dark, light, night", "тёмная, светлая, ночная"),
        Sample("language", "App Language", "Язык приложения", "locale, translation", "перевод, локаль"),
        Sample("backup", "Backup & restore", "Резервные копии", "export, import, save settings", "бэкап, экспорт, импорт"),
        Sample("logs", "Logs", "Логи", "journal", "журнал"),
        Sample("auth_key", "Auth Key", "Ключ авторизации", "authkey, pre-auth", "ключ"),
        Sample(
            "keep_awake", "Keep the connection awake", "Держать соединение активным",
            "battery, wake lock, doze, sleep", "батарея, сон",
        ),
        Sample("control_port", "Port 443 only", "Только порт 443", "tls, https", place = "Censorship bypass"),
        Sample("tun_engine", "TUN engine", "Движок TUN", place = "Настройки режима TUN"),
        Sample("wifi", "Expose proxies to local network", "Открыть прокси в локальную сеть", "wi-fi, lan, hotspot", "вай-фай, локалка"),
    )

    private val stops = setOf(
        "how", "do", "i", "to", "the", "a", "change", "set", "enable", "turn", "on", "off", "where", "is",
        "как", "где", "включить", "выключить", "поменять", "изменить", "настроить", "мне", "в", "на",
    )

    private val engine = SearchEngine(samples.map(::entry), stops)

    private fun top(query: String): String? = engine.search(query).hits.firstOrNull()?.id
    private fun ids(query: String): List<String> = engine.search(query).hits.map { it.id }

    /** Each query finds its entry first. */
    private fun assertFinds(vararg cases: Pair<String, String>) {
        for ((query, id) in cases) assertEquals("«$query»", id, top(query))
    }

    // ─── How people type ─────────────────────────────────────────────────────────────────

    @Test
    fun aQueryInEitherLanguageFindsTheEntry() = assertFinds(
        "tunnel" to "tunnel", "туннель" to "tunnel",
        "proxies" to "proxies", "прокси" to "proxies",
        "exit node" to "exit_node", "выходной узел" to "exit_node",
        "language" to "language", "язык" to "language",
        "theme" to "theme", "тема" to "theme",
        "logs" to "logs", "логи" to "logs",
    )

    @Test
    fun aSlipOfTheFingerIsForgiven() = assertFinds(
        "tunel" to "tunnel", "tunnle" to "tunnel", "tunnell" to "tunnel",
        "тунель" to "tunnel", "тунннель" to "tunnel",
        "censrship" to "bypass", "cenzorship" to "bypass",
        "блкировки" to "bypass", "блокирвоки" to "bypass",
        "автзапуск" to "autostart", "автозапсук" to "autostart",
        "bakcup" to "backup", "bckup" to "backup",
        "langauge" to "language", "languag" to "language",
    )

    @Test
    fun theOrderOfTheWordsDoesNotMatter() = assertFinds(
        "node exit" to "exit_node", "узел выходной" to "exit_node",
        "address socks5" to "socks_address", "address dns proxy" to "dns_proxy",
        "блокировок обход" to "bypass",
    )

    @Test
    fun aPartOfAWordIsEnough() = assertFinds(
        "cens" to "bypass", "tunn" to "tunnel", "lang" to "language",
        "автоз" to "autostart", "блок" to "bypass", "выходн" to "exit_node",
        "magic" to "magicdns", "back" to "backup",
    )

    @Test
    fun yoAndYeAreOneLetter() {
        // The catalogue says «тёмная»; people type «темная», and the other way round.
        assertEquals("theme", top("темная"))
        assertEquals("theme", top("тёмная"))
        assertEquals("keep_awake", top("держать соединёние"))
    }

    @Test
    fun theWrongKeyboardLayoutIsReadAsTheOther() {
        val cases = listOf(
            Triple("ghjrcb", "proxies", "прокси"),
            Triple("мзт", "tunnel", "vpn"),
            Triple("j,[jl", "bypass", "обход"),
            Triple("ntvf", "theme", "тема"),
            Triple("вты", "dns", "dns"),
            Triple("ифслгз", "backup", "backup"),
            Triple("zpsr", "language", "язык"),
            Triple("учше тщву", "exit_node", "exit node"),
        )
        for ((typed, id, meant) in cases) {
            val result = engine.search(typed)
            assertEquals(typed, id, result.hits.firstOrNull()?.id)
            assertEquals(typed, meant, result.readAs)
        }
        // A query that is found as typed is not read any other way.
        assertNull(engine.search("proxy").readAs)
        assertNull(engine.search("прокси").readAs)
    }

    @Test
    fun aTransliterationFindsTheWordInItsOwnAlphabet() = assertFinds(
        "proksi" to "proxies", "tunnel" to "tunnel", "obhod" to "bypass", "blokirovki" to "bypass",
        "avtozapusk" to "autostart", "yazyk" to "language", "tema" to "theme", "klyuch" to "auth_key",
        "vyhodnoi uzel" to "exit_node", "zhurnal" to "logs", "bekap" to "backup",
        // English words in Russian letters.
        "днс" to "dns", "впн" to "tunnel", "сокс" to "socks_address", "порт 443" to "control_port",
        // A setting found through the name of the card it is on.
        "nastroiki tun" to "tun_engine",
    )

    @Test
    fun theNamesPeopleUseAreKeywords() = assertFinds(
        "vpn" to "tunnel", "kill switch" to "tunnel", "always-on" to "tunnel", "always on" to "tunnel",
        "ркн" to "bypass", "цензура" to "bypass", "dpi" to "bypass", "blocked" to "bypass",
        "battery" to "keep_awake", "батарея" to "keep_awake", "doze" to "keep_awake",
        "бэкап" to "backup", "export" to "backup", "журнал" to "logs",
        "wifi" to "wifi", "wi-fi" to "wifi", "вай фай" to "wifi",
    )

    @Test
    fun theWordsOfAQuestionDoNotHurt() = assertFinds(
        "как включить впн" to "tunnel",
        "how do i change the port" to "control_port",
        "how to turn on the vpn" to "tunnel",
        "где поменять язык" to "language",
        "как настроить обход блокировок" to "bypass",
        "where is the dark theme" to "theme",
    )

    @Test
    fun aQueryOfLittleWordsOnlyIsAskedAsItStands() {
        // "on" is a little word of a question, and all there is here.
        assertEquals("autostart", top("on boot"))
        assertTrue(ids("on").contains("autostart"))
    }

    // ─── The order of the answer ─────────────────────────────────────────────────────────

    private fun ranked(vararg entries: SearchEntry) = SearchEngine(entries.toList(), stops)

    @Test
    fun aTitleBeatsAKeywordBeatsThePlaceBeatsTheDescriptionBeatsASlip() {
        val engine = ranked(
            SearchEntry("slip", listOf(SearchField.TITLE to "Part of it")),
            SearchEntry("description", listOf(SearchField.TITLE to "Something", SearchField.DESCRIPTION to "Listens on a port of its own")),
            SearchEntry("keyword", listOf(SearchField.TITLE to "Listener", SearchField.KEYWORD to "port")),
            SearchEntry("prefix", listOf(SearchField.TITLE to "Portal")),
            SearchEntry("title", listOf(SearchField.TITLE to "Relay port")),
        )
        assertEquals(listOf("title", "prefix", "keyword", "description", "slip"), engine.search("port").hits.map { it.id })
        assertEquals(listOf(true, true, true, true, false), engine.search("port").hits.map { it.solid })
    }

    @Test
    fun thePlaceStandsBetweenAKeywordAndTheDescription() {
        val engine = ranked(
            SearchEntry("description", listOf(SearchField.TITLE to "Something", SearchField.DESCRIPTION to "Asks the DNS server")),
            SearchEntry("place", listOf(SearchField.TITLE to "Fallback servers", SearchField.PLACE to "DNS")),
            SearchEntry("keyword", listOf(SearchField.TITLE to "Resolver", SearchField.KEYWORD to "dns")),
        )
        assertEquals(listOf("keyword", "place", "description"), engine.search("dns").hits.map { it.id })
    }

    @Test
    fun theShorterMoreSpecificMatchComesFirst() {
        assertEquals(listOf("dns", "dns_proxy", "magicdns"), ids("dns").take(3))
        // Both words in one title, before one of them in a title and the other elsewhere.
        assertEquals("dns_proxy", top("dns proxy"))
        assertEquals("socks_address", top("socks address"))
    }

    @Test
    fun whatWasOpenedBeforeRisesALittleAndNoFurther() {
        val engine = ranked(
            SearchEntry("first", listOf(SearchField.TITLE to "Proxy address")),
            SearchEntry("second", listOf(SearchField.TITLE to "Proxy password")),
            SearchEntry("keyword", listOf(SearchField.TITLE to "Listener", SearchField.KEYWORD to "proxy")),
        )
        assertEquals(listOf("first", "second", "keyword"), engine.search("proxy").hits.map { it.id })
        val opened = mapOf("second" to 36, "keyword" to 36)
        // It passes its equal, and a keyword hit still stays under the titles.
        assertEquals(listOf("second", "first", "keyword"), engine.search("proxy", boost = { opened[it] ?: 0 }).hits.map { it.id })
    }

    @Test
    fun equalsKeepTheOrderOfTheCatalogue() {
        val engine = ranked(*(1..6).map { SearchEntry("e$it", listOf(SearchField.TITLE to "Same title")) }.toTypedArray())
        repeat(3) { assertEquals((1..6).map { "e$it" }, engine.search("same").hits.map { it.id }) }
    }

    @Test
    fun slipsAreLeftOutBesidePlentyOfSureHits() {
        val sure = (1..5).map { SearchEntry("sure$it", listOf(SearchField.TITLE to "Port $it")) }
        val engine = ranked(*(sure + SearchEntry("slip", listOf(SearchField.TITLE to "Part"))).toTypedArray())
        assertFalse(engine.search("port").hits.any { it.id == "slip" })
        // And kept when the typed word is found nowhere as it stands.
        assertEquals("slip", ranked(SearchEntry("slip", listOf(SearchField.TITLE to "Part"))).search("pary").hits.single().id)
    }

    @Test
    fun aDigitIsNeverASlip() {
        val engine = ranked(
            SearchEntry("v4", listOf(SearchField.TITLE to "Force IPv4")),
            SearchEntry("v6", listOf(SearchField.TITLE to "Route all IPv6")),
            SearchEntry("http", listOf(SearchField.TITLE to "Port 8080")),
        )
        assertEquals(listOf("v6"), engine.search("ipv6").hits.map { it.id })
        assertEquals(listOf("v4"), engine.search("ipv4").hits.map { it.id })
        // A slip in the letters around the digit is still a slip.
        assertEquals(listOf("v6"), engine.search("ivp6").hits.map { it.id })
        assertTrue(engine.search("8081").hits.isEmpty())
    }

    // ─── Nothing, and nearly ─────────────────────────────────────────────────────────────

    @Test
    fun aBlankQueryFindsNothing() {
        assertTrue(engine.search("").hits.isEmpty())
        assertTrue(engine.search("   ").hits.isEmpty())
        assertTrue(engine.search("…?!").hits.isEmpty())
    }

    @Test
    fun nonsenseFindsNothing() {
        for (query in listOf("zzzzqqq", "ъъъъ", "qwfpgj", "xkcd1234")) {
            val result = engine.search(query)
            assertTrue(query + " found " + result.hits.map { it.id + ":" + it.text }, result.hits.isEmpty())
            assertFalse(query, result.closest)
        }
    }

    @Test
    fun withOneWordUnknownTheNearestAreOffered() {
        val result = engine.search("exit node banana")
        assertTrue(result.closest)
        assertEquals("exit_node", result.hits.first().id)
        // All the words found: not "nearest" any more.
        assertFalse(engine.search("exit node").closest)
    }

    // ─── What is marked in a result ──────────────────────────────────────────────────────

    private fun marked(text: String, query: String): List<String> =
        engine.highlight(text, engine.search(query)).map { text.substring(it.first, it.last + 1) }

    @Test
    fun theMatchedPartIsMarked() {
        assertEquals(listOf("DNS", "Pro"), marked("DNS Proxy Address", "dns pro"))
        assertEquals(listOf("DNS"), marked("Use MagicDNS", "dns"))
        assertEquals(listOf("блокировк"), marked("блокировки, цензура", "блокировку"))
        assertEquals(listOf("Тунн"), marked("Туннель", "тунн"))
        // Reached by sound or by a slip: the whole word.
        assertEquals(listOf("прокси"), marked("Локальные прокси", "proksi"))
        assertEquals(listOf("Tunnel"), marked("Tunnel mode", "tunel"))
        // The little words of a question are not marked.
        assertEquals(listOf("port"), marked("How the port is set", "how do i change the port"))
        // The other layout: what is marked is what the keys meant.
        assertEquals(listOf("прокси"), marked("Локальные прокси", "ghjrcb"))
        assertEquals(emptyList<String>(), marked("Exit Node", "dns"))
    }

    @Test
    fun aHitSaysWhereItsWordsStood() {
        val keyword = engine.search("kill switch").hits.first()
        assertEquals(SearchField.KEYWORD, keyword.field)
        assertEquals("kill switch", keyword.text)
        val title = engine.search("tunnel").hits.first()
        assertEquals(SearchField.TITLE, title.field)
    }

    // ─── Speed ───────────────────────────────────────────────────────────────────────────

    /** A few hundred entries, a query retyped letter by letter: a keystroke costs well under a frame. */
    @Test
    fun aKeystrokeIsWellUnderAFrame() {
        val entries = (0 until 400).map { n ->
            val s = samples[n % samples.size]
            entry(
                Sample(
                    "${s.id}$n", "${s.en} $n", "${s.ru} $n", s.keywordsEn + ", extra$n, word$n", s.keywordsRu + ", слово$n",
                    s.place, s.does + " Entry number $n of the catalogue, with a sentence of its own to search in.",
                )
            )
        }
        val big = SearchEngine(entries, stops)
        val queries = listOf(
            "как включить впн", "how do i change the port", "ghjrcb", "censrship bypas", "выходной узел",
            "nastroiki tun", "автзапуск при загрузке", "zzzzqqq nothing here", "dns proxy address", "блкировки",
        )
        // Each query as it is typed: every beginning of it is a search of its own.
        val keystrokes = queries.flatMap { q -> (1..q.length).map { q.take(it) } }
        repeat(3) { keystrokes.forEach { big.search(it) } }     // let the JIT settle
        // A fresh engine each run: nothing remembered from a run before.
        val cold = List(5) { SearchEngine(entries, stops) }
        val started = System.nanoTime()
        for (fresh in cold) keystrokes.forEach { fresh.search(it) }
        val perKeystrokeMs = (System.nanoTime() - started) / 1e6 / (cold.size * keystrokes.size)
        // A frame is 16 ms. The bound leaves room for a loaded build machine.
        assertTrue("a keystroke took $perKeystrokeMs ms", perKeystrokeMs < 4.0)
    }
}
