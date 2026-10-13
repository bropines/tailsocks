package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.Capabilities
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.search.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What people type, against the real catalogue with its real words in English and in Russian
 * (search/SearchEngineTest has the engine on a catalogue of its own). This is the test of
 * the keywords: a query here that stops finding its setting is a list in
 * strings_settings_search_keywords.xml that lost a word.
 */
class SettingsSearchQueriesTest {

    private val languages = listOf(language(englishStrings), language(russianStrings))
    private val engine = SearchEngine(searchEntries(SettingsCatalog.entries, languages), searchStopWords(languages))

    /** What a phone shows for [query]: the hits it has a place for. */
    private fun found(query: String): List<String> = engine.search(query).hits.map { it.id }.filter { id ->
        SettingsCatalog.place(SettingsCatalog.byId(id)!!, DeviceClass.HANDHELD, Capabilities.ALL) { true } != null
    }

    /** [id] is among the first [within] results of each query. */
    private fun assertFinds(within: Int, vararg cases: Pair<String, String>) {
        for ((query, id) in cases) {
            val ids = found(query)
            val scores = engine.search(query).hits.take(8).map { "${it.id} ${it.score} ${it.field}" }
            assertTrue("«$query» should find $id within $within, found ${ids.take(8)} — $scores", id in ids.take(within))
        }
    }

    @Test
    fun theEngineHasEveryEntryInBothLanguages() {
        assertEquals(SettingsCatalog.entries.size, searchEntries(SettingsCatalog.entries, languages).size)
        // Each entry is found by its own title, in English and in Russian.
        for (entry in SettingsCatalog.entries.filter { !it.page.aside }) {
            for (text in languages) assertTrue("${entry.id}: ${text(entry.title)}", entry.id in found(text(entry.title)).take(4))
        }
    }

    @Test
    fun aSettingIsFoundByItsNameInEitherLanguage() = assertFinds(
        1,
        "exit node" to "exit_node", "выходной узел" to "exit_node",
        "tunnel mode" to "tunnel", "режим туннеля" to "tunnel",
        "censorship bypass" to "bypass", "обход блокировок" to "bypass",
        "logs" to "logs", "логи" to "logs",
        "auth key" to "auth_key", "ключ авторизации" to "auth_key",
        "app language" to "language", "язык приложения" to "language",
        "magicdns" to "magicdns", "tailnet lock" to "tailnet_lock",
        "amoled" to "amoled", "serve" to "serve", "funnel" to "serve",
        "permissions" to "permissions", "разрешения" to "permissions",
        "remote management" to "remote_management", "удалённое управление" to "remote_management",
    )

    @Test
    fun theNamesPeopleUseInsteadOfOurs() = assertFinds(
        1,
        "vpn" to "mode_vpn", "впн" to "mode_vpn",
        "kill switch" to "mode_vpn", "always-on" to "mode_vpn", "always on vpn" to "mode_vpn",
        "блокировки" to "bypass", "цензура" to "bypass", "ркн" to "bypass", "обход" to "bypass",
        "ключ" to "auth_key", "логи" to "logs",
        "автозапуск" to "autostart", "autostart" to "autostart", "start on boot" to "autostart",
        "язык" to "language", "тема" to "theme_mode", "dark mode" to "theme_mode", "тёмная тема" to "theme_mode",
        "font size" to "ui_scale", "размер шрифта" to "ui_scale",
        "headscale" to "login_server", "hostname" to "device_name", "имя устройства" to "device_name",
        "wifi" to "lan_access", "вай фай" to "lan_access", "hotspot" to "lan_access",
        "log out" to "logout", "выйти" to "logout", "разлогиниться" to "logout",
        "doh" to "doh_fallback", "1053" to "dns_proxy_address", "443" to "control_port", "8.8.8.8" to "dns_fallbacks",
        "material you" to "dynamic_color", "чёрный фон" to "amoled",
        "tasker" to "automation_enable", "таскер" to "automation_enable",
        "taildrop" to "taildrop_folder", "куда сохраняются файлы" to "taildrop_folder",
        "qr" to "qr_style", "webdav" to "taildrive", "ngrok" to "serve",
        "nekobox" to "proxies", "termux" to "root_cli", "zapret" to "byedpi",
        "wake lock" to "keep_awake", "watchdog" to "watchdog", "xiaomi" to "watchdog",
        "tv mode" to "interface", "телевизор" to "interface",
        "netcheck" to "netcheck", "derp" to "netcheck", "пинг" to "netcheck",
    )

    @Test
    fun aWordSeveralSettingsShareOffersThemAll() {
        assertFinds(3, "vpn" to "tunnel", "dpi" to "byedpi", "дпи" to "byedpi", "бэкап" to "backup_full", "бэкап" to "backup")
        assertFinds(3, "port" to "control_port", "port" to "relay_port", "порт" to "control_port")
        assertFinds(8, "port" to "socks5_address", "порт" to "socks5_address", "порт" to "http_address")
        assertFinds(2, "battery" to "permissions", "battery" to "keep_awake", "батарея" to "keep_awake", "батарея" to "permissions")
        assertFinds(3, "ipv6" to "tun_ipv6", "ipv6" to "byedpi_ipv6", "lan" to "exit_node_lan")
        // "lan" also begins "language", and the beginning of a title comes before a keyword.
        assertFinds(4, "lan" to "lan_access", "lan" to "language")
        assertFinds(5, "proxy" to "proxies", "proxy" to "mode_proxy", "прокси" to "proxies", "прокси" to "mode_proxy")
        assertFinds(3, "dns" to "dns", "днс" to "dns", "magisk" to "mode_root", "рут" to "mode_root", "root" to "mode_root")
        assertFinds(2, "split tunneling" to "tun_excluded_apps", "split tunneling" to "tun_excluded_cidrs", "раздельное туннелирование" to "tun_excluded_apps")
    }

    @Test
    fun aQuestionIsAnsweredByItsSubject() = assertFinds(
        3,
        "как включить впн" to "mode_vpn",
        "how do i change the port" to "control_port",
        "how do i change the port" to "relay_port",
        "how to turn on the vpn" to "mode_vpn",
        "где поменять язык" to "language",
        "как сменить тему" to "theme_mode",
        "how can i set an exit node" to "exit_node",
        "как настроить обход блокировок" to "bypass",
        "where are the logs" to "logs",
        "как сделать бэкап" to "backup_full",
        "can i use my own headscale server" to "login_server",
    )

    @Test
    fun slipsOfTheFinger() = assertFinds(
        2,
        "tunel" to "tunnel", "exti node" to "exit_node", "autostrat" to "autostart", "автозапсук" to "autostart",
        "bakcup" to "backup", "censrship" to "bypass", "блкировки" to "bypass", "headscal" to "login_server",
        "langauge" to "language", "permisions" to "permissions", "разрещения" to "permissions",
        "выходнй узел" to "exit_node", "magicdsn" to "magicdns",
    )

    @Test
    fun theWrongKeyboardLayout() {
        assertFinds(
            2,
            "мзт" to "mode_vpn", "вты" to "dns", "учше тщву" to "exit_node", "ифслгз" to "backup",
            "ntvf" to "theme_mode", "zpsr" to "language", "fdnjpfgecr" to "autostart", "kjub" to "logs",
            "ds[jlyjq eptk" to "exit_node", "j,[jl ,kjrbhjdjr" to "bypass",
        )
        assertFinds(4, "ghjrcb" to "proxies", "ghjrcb" to "mode_proxy")
        assertEquals("прокси", engine.search("ghjrcb").readAs)
        assertEquals("vpn", engine.search("мзт").readAs)
    }

    @Test
    fun transliteration() {
        assertFinds(
            2,
            "obhod blokirovok" to "bypass", "vyhodnoi uzel" to "exit_node", "avtozapusk" to "autostart",
            "yazyk" to "language", "tema" to "theme_mode", "klyuch" to "auth_key", "logi" to "logs",
            "днс" to "dns", "впн" to "mode_vpn", "razresheniya" to "permissions",
        )
        assertFinds(4, "proksi" to "proxies", "proksi" to "mode_proxy", "bekap" to "backup_full")
        // The word for "settings" itself: whatever is named with it.
        assertNotNull(found("nastroiki").firstOrNull())
    }

    @Test
    fun whatIsInsideADialogIsFoundThroughTheRowThatOpensIt() = assertFinds(
        3,
        "proxy presets" to "control_proxy", "import proxy link" to "control_proxy",
        "battery optimization" to "permissions", "оптимизация батареи" to "permissions",
        "hev" to "tun_engine", "reinstall script" to "root_service", "check routing" to "root_daemon_info",
        "generate token" to "automation_token",
    )

    @Test
    fun nonsenseFindsNothing() {
        for (query in listOf("zzzzqqq", "qwfpgjluy", "ъъъъъ")) assertTrue(query + " " + found(query), found(query).isEmpty())
    }
}
