package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Capabilities
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings catalogue against the settings themselves. The search finds what the catalogue
 * lists, so the catalogue must list what the pages show — and this is what holds it to that:
 * the sources of the settings pages are read, every title they hand to a row is looked up,
 * and a row without an entry fails the build with the name of the row.
 *
 * To add a setting: add its row to the page as usual, then its entry to ui/SettingsCatalog.kt
 * and its `search_kw_<id>` to both strings_settings_search_keywords.xml. To add a card in a
 * file of its own: nothing more — a file that draws settings rows is read here by itself.
 */
class SettingsCatalogTest {

    // ─── What the sources show ───────────────────────────────────────────────────────────

    /** The composables that draw a row of a settings page, and where each takes its title when it is not named. */
    private val rowFamily = mapOf(
        "SettingsSwitchItem" to 0,
        "SettingsClickableItem" to 0,
        "SettingsEditItem" to 0,
        "SettingsExitNodeItem" to 0,
        // NodeSettings.kt: (state, field, title, …).
        "NodeSwitchRow" to 2,
        // The tunnel modes of SettingsActivity.kt: always called by name.
        "ModeOption" to 2,
    )

    /**
     * Screens of their own that borrow the settings row. Settings links to each of them, and
     * the link is what the search finds; what is on them is not a row of Settings.
     */
    private val otherScreens = setOf(
        "ui/TaildriveActivity.kt",
        "ui/TailnetLockActivity.kt",
        "ui/PermissionsActivity.kt",
        "ui/FirstStartActivity.kt",
    )

    /**
     * Titles that are not written where the row is called. Each is accounted for elsewhere in
     * this test: the pages by [thePagesAreTheOnesSettingsHas], the Advanced page's links by
     * [theAdvancedPageLinksAreEntries], and a row that passes its own title on by its callers.
     */
    private val titlesFromElsewhere = setOf(
        "stringResource(category.titleRes)",
        "stringResource(page.titleRes)",
        "stringResource(tool.titleRes)",
        "stringResource(title)",
    )

    private class Shown(val title: String, val where: String)

    private val anchorCall = Regex("settingAnchor\\(stringResource\\(R\\.string\\.(\\w+)\\)")
    private val stringReference = Regex("R\\.string\\.(\\w+)")

    /** The sources that draw settings: every file with a row of the family or an anchor of its own, bar the other screens. */
    private val settingsSources: Map<String, String> by lazy {
        appSources.filter { (file, source) ->
            file !in otherScreens && (callsOf(file, source, rowFamily.keys.toList()).isNotEmpty() || anchorCall.containsMatchIn(source))
        }
    }

    /** Every title a settings source shows on a row, and every title it anchors by hand. */
    private fun shownTitles(): List<Shown> {
        val shown = ArrayList<Shown>()
        for ((file, source) in settingsSources) {
            for (call in callsOf(file, source, rowFamily.keys.toList())) {
                val title = call.argument("title", rowFamily.getValue(call.name)) ?: error("${call.file}:${call.line}: ${call.name} without a title")
                val resource = stringReference.find(title)?.groupValues?.get(1)
                if (resource != null) shown.add(Shown(resource, "${call.file}:${call.line}"))
                else assertTrue("${call.file}:${call.line}: the title «$title» of ${call.name} is not a string resource this test can read", title in titlesFromElsewhere)
            }
            for (match in anchorCall.findAll(source)) {
                shown.add(Shown(match.groupValues[1], "$file:${source.take(match.range.first).count { it == '\n' } + 1}"))
            }
        }
        return shown
    }

    /** What is wrong between the rows the sources show and the entries of [catalogue]; empty when they agree. */
    private fun disagreements(shown: List<Shown>, catalogue: List<SettingEntry>): List<String> {
        val anchors = catalogue.filter { !it.isPage }.map { stringName(it.anchor) }.toSet()
        val everywhere = settingsSources.values.flatMap { source -> stringReference.findAll(source).map { it.groupValues[1] } }.toSet()
        return shown.filter { it.title !in anchors }.map {
            "${it.where}: the row «${it.title}» has no entry in SettingsCatalog — the search cannot find it"
        }.distinct() + catalogue.filter { !it.isPage && stringName(it.anchor) !in everywhere }.map {
            "SettingsCatalog «${it.id}»: no settings page shows «${stringName(it.anchor)}» — a result would open a page without the row"
        }
    }

    @Test
    fun everyRowOfSettingsIsInTheCatalogueAndEveryEntryIsARow() {
        val shown = shownTitles()
        // Read something at all: a scanner that finds nothing would agree with anything.
        assertTrue("only ${shown.size} rows found in ${settingsSources.keys}", shown.size > 80)
        assertTrue("SettingsActivity.kt is the first of the sources", "ui/SettingsActivity.kt" in settingsSources)
        assertEquals("", disagreements(shown, SettingsCatalog.entries).joinToString("\n"))
    }

    /** The guard has teeth: take an entry out, and it names the row that lost it. */
    @Test
    fun aRowTakenOutOfTheCatalogueFailsTheGuard() {
        val shown = shownTitles()
        for (id in listOf("autostart", "tun_ipv6", "theme_mode", "netmap_cache_clear", "backup_full", "mode_vpn", "relay")) {
            val without = SettingsCatalog.entries.filter { it.id != id }
            val title = stringName(SettingsCatalog.byId(id)!!.anchor)
            val found = disagreements(shown, without)
            assertTrue("$id: $found", found.any { "«$title»" in it && "has no entry" in it })
        }
    }

    /** And the other way: an entry for a row that is gone is named too. */
    @Test
    fun anEntryWithoutItsRowFailsTheGuard() {
        val ghost = SettingEntry("ghost", SettingsPage.DNS, R.string.search_none, R.string.search_none, R.string.search_kw_dns)
        val found = disagreements(shownTitles(), SettingsCatalog.entries + ghost)
        assertTrue(found.toString(), found.single().startsWith("SettingsCatalog «ghost»"))
    }

    @Test
    fun aRowIsFoundHoweverItIsCalled() {
        val source = """
            fun SettingsSwitchItem(title: String) {}
            SettingsSwitchItem(stringResource(R.string.a_title), stringResource(R.string.a_desc), Icons.Default.Map, on) { on = it }
            SettingsEditItem(
                value = text("a, b"),
                title = stringResource(R.string.b_title),
                icon = Icons.Default.Map
            ) { save(it, 1) }
            NodeSwitchRow(state, Field.RELAY, R.string.c_title, R.string.c_desc, Icons.Default.Hub, save, status)
            SettingsClickableItem(title = stringResource(page.titleRes), subtitle = "")
        """.trimIndent()
        val calls = callsOf("x.kt", source, rowFamily.keys.toList())
        assertEquals(listOf("SettingsSwitchItem", "SettingsEditItem", "NodeSwitchRow", "SettingsClickableItem"), calls.map { it.name })
        assertEquals(
            listOf("stringResource(R.string.a_title)", "stringResource(R.string.b_title)", "R.string.c_title", "stringResource(page.titleRes)"),
            calls.map { it.argument("title", rowFamily.getValue(it.name)) }
        )
        assertEquals(listOf(2, 3, 8, 9), calls.map { it.line })
    }

    // ─── The pages ───────────────────────────────────────────────────────────────────────

    private val settingsActivity: String get() = appSources.getValue("ui/SettingsActivity.kt")

    @Test
    fun thePagesAreTheOnesSettingsHas() {
        val declared = Regex("SettingsCategory\\(\"(\\w+)\", R\\.string\\.(\\w+), R\\.string\\.(\\w+), Icons\\.Default\\.(\\w+)").findAll(settingsActivity).toList()
        assertEquals(SettingsPage.entries.map { it.id }.sorted(), declared.map { it.groupValues[1] }.sorted())
        for (match in declared) {
            val (id, title, summary, icon) = match.destructured
            val page = SettingsPage.entries.single { it.id == id }
            assertEquals(id, title, stringName(page.title))
            assertEquals(id, "Filled.$icon", page.icon.name)
            val entry = SettingsCatalog.byId(id)!!
            assertTrue("$id is a page", entry.isPage)
            // Advanced is described by what a handheld has on it, which the hub says in other words there.
            if (page != SettingsPage.ADVANCED) assertEquals(id, summary, stringName(entry.summary))
        }
        assertTrue(settingsActivity.contains("advancedCategory.copy(descRes = R.string.${stringName(SettingsCatalog.byId("advanced")!!.summary)}"))
        // A link may name every page, and nothing else is one.
        assertEquals(DeepLinks.SETTINGS_SECTIONS, SettingsPage.entries.map { it.id }.toSet())
    }

    @Test
    fun theAdvancedPageLinksAreEntries() {
        val tools = Regex("AdvancedTool\\(Feature\\.(\\w+), R\\.string\\.(\\w+)").findAll(settingsActivity).toList()
        assertEquals(4, tools.size)
        for (tool in tools) {
            val (feature, title) = tool.destructured
            val entry = SettingsCatalog.entries.singleOrNull { !it.isPage && stringName(it.title) == title }
            assertNotNull("the link «$title» of the Advanced page has no entry", entry)
            assertEquals(title, Feature.valueOf(feature), entry!!.feature)
            assertEquals(title, SettingsPage.ADVANCED, entry.aside)
        }
    }

    // ─── The entries ─────────────────────────────────────────────────────────────────────

    @Test
    fun idsAreUniqueAndFitALink() {
        val ids = SettingsCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertTrue(id, Regex("[a-z][a-z0-9_]*").matches(id))
        // No two entries of a page open on the same row.
        val rows = SettingsCatalog.entries.filter { !it.isPage }.groupBy { it.page to it.anchor }.filter { it.value.size > 1 }
        assertTrue(rows.values.map { group -> group.map { it.id } }.toString(), rows.isEmpty())
    }

    @Test
    fun everyEntryHasItsWordsInEnglishAndInRussian() {
        for (entry in SettingsCatalog.entries) {
            val keywords = stringName(entry.keywords)
            assertTrue("${entry.id}: $keywords", keywords.startsWith("search_kw_"))
            for ((language, strings) in listOf("values" to englishStrings, "values-ru" to russianStrings)) {
                val list = strings[keywords]
                assertNotNull("${entry.id}: $keywords is missing from $language", list)
                assertTrue("${entry.id}: $keywords in $language is too short a list: $list", keywordsOf(list!!).size >= 3)
            }
            // A title and a line of what it does, both real sentences and not a pattern to fill in.
            for (resource in listOf(entry.title, entry.summary)) {
                val text = englishStrings.getValue(stringName(resource))
                assertTrue("${entry.id}: ${stringName(resource)}", text.isNotBlank() && "%1$" !in text)
            }
        }
        // The Russian list is Russian: not a copy of the English one.
        val same = SettingsCatalog.entries.map { stringName(it.keywords) }.filter { englishStrings[it] == russianStrings[it] }
        assertTrue(same.toString(), same.isEmpty())
    }

    @Test
    fun theStopWordsAreInBothLanguages() {
        val english = keywordsOf(englishStrings.getValue("search_stop_words"))
        val russian = keywordsOf(russianStrings.getValue("search_stop_words"))
        assertTrue("how" in english && "change" in english)
        assertTrue("как" in russian && "включить" in russian)
        // A word a setting is named by is never a little word.
        for (word in listOf("port", "vpn", "proxy", "порт", "впн", "прокси", "обход")) assertFalse(word, word in english || word in russian)
    }

    @Test
    fun everyNeedLeadsToARowThatIsAlwaysThere() {
        for (need in Need.entries) {
            if (need.reason == 0) {
                assertNull("$need says nothing, so it brings nothing", need.broughtBy)
                continue
            }
            // Follow what brings what; a chain that does not end is a result that opens nothing.
            var entry = SettingsCatalog.byId(need.broughtBy)
            val seen = HashSet<String>()
            while (true) {
                assertNotNull("$need is brought by «${need.broughtBy}», which is no entry", entry)
                assertTrue("$need: ${entry!!.id} needs itself", seen.add(entry.id))
                // A row that needs what nothing brings is simply not offered then, and neither is what it brings.
                val bringer = entry.needs?.broughtBy ?: break
                entry = SettingsCatalog.byId(bringer)
            }
            assertTrue("$need has a reason to show", englishStrings.getValue(stringName(need.reason)).isNotBlank())
        }
    }

    // ─── Where an entry is on a device ───────────────────────────────────────────────────

    private val everything = SettingsFacts { true }
    private val freshInstall = SettingsFacts { it == Need.STATIC_COLORS || it == Need.ANDROID_12 || it == Need.SELF_UPDATE }

    private fun place(id: String, device: DeviceClass = DeviceClass.HANDHELD, facts: SettingsFacts = everything): SettingPlace? =
        SettingsCatalog.place(SettingsCatalog.byId(id)!!, device, Capabilities.NONE, facts)

    private fun trail(place: SettingPlace) = place.trail.map { stringName(it) }

    @Test
    fun aHandheldHasEveryEntryOnItsOwnPage() {
        for (entry in SettingsCatalog.entries) {
            val place = SettingsCatalog.place(entry, DeviceClass.HANDHELD, Capabilities.ALL, everything)
            // The two pages that exist only under a television's Advanced are not pages here.
            if (entry.page.aside) { assertNull(entry.id, place); continue }
            assertNotNull(entry.id, place)
            assertEquals(entry.id, entry.page.id, place!!.section)
            assertEquals(entry.id, entry.anchor, place.anchor)
            assertEquals(entry.id, 0, place.reason)
        }
        assertEquals(listOf("settings_cat_tunnel", "settings_sect_tun_mode"), trail(place("tun_ipv6")!!))
        assertEquals(listOf("settings_title"), trail(place("tunnel")!!))
    }

    @Test
    fun aRowThatIsNotOnItsPageOpensTheRowThatBringsIt() {
        // A fresh install runs as a proxy: the TUN card is not on the page.
        val ipv6 = place("tun_ipv6", facts = freshInstall)!!
        assertEquals("tunnel", ipv6.section)
        assertEquals(R.string.settings_tunnel_mode_vpn, ipv6.anchor)
        assertEquals(R.string.search_need_tun, ipv6.reason)
        // Two steps away — the hev engine's own rows — still lands on the first step that is there.
        val address = place("tun_address", facts = freshInstall)!!
        assertEquals(R.string.settings_tunnel_mode_vpn, address.anchor)
        assertEquals(R.string.search_need_tun_hev, address.reason)
        // On another page than the row that brings it: the result goes there.
        val dns = place("root_dns_redirect", facts = freshInstall)!!
        assertEquals("tunnel", dns.section)
        assertEquals(R.string.settings_tunnel_mode_root, dns.anchor)
        // With the switch on, the row is its own, and the switch stands by should the page disagree.
        val attempts = place("auto_reconnect_attempts")!!
        assertEquals(0, attempts.reason)
        assertEquals(R.string.settings_auto_reconnect_attempts_title, attempts.anchor)
        assertEquals(R.string.settings_auto_reconnect_title, attempts.fallback)
    }

    @Test
    fun whatTheDeviceCannotShowIsNotOffered() {
        // Not this build, not this Android: nothing to send the user to.
        assertNull(place("update_check", facts = freshInstall.let { f -> SettingsFacts { it != Need.SELF_UPDATE && f.met(it) } }))
        assertNull(place("dynamic_color", facts = SettingsFacts { false }))
        // A television: no clipboard, no admin console.
        assertNull(place("sagernet_link", DeviceClass.TV))
        assertNull(place("admin_console", DeviceClass.TV))
    }

    @Test
    fun aTelevisionFindsWhatItKeepsUnderAdvanced() {
        val backup = place("backup_full", DeviceClass.TV)!!
        assertEquals("backup", backup.section)
        assertEquals(listOf("tv_settings_advanced", "settings_cat_backup", "settings_sect_backup_files"), trail(backup))
        // The theme and Root mode have pages of their own there; the screens are links on Advanced itself.
        assertEquals("theme", place("amoled", DeviceClass.TV)!!.section)
        assertEquals("theme", place("theme_mode", DeviceClass.TV)!!.section)
        assertEquals("theme", place("theme", DeviceClass.TV)!!.section)
        assertEquals("root", place("mode_root", DeviceClass.TV)!!.section)
        assertEquals("root", place("root", DeviceClass.TV)!!.section)
        assertEquals("advanced", place("logs", DeviceClass.TV)!!.section)
        assertEquals("advanced", place("serve", DeviceClass.TV)!!.section)
        // What stayed in the hub is where it always was.
        assertEquals("appearance", place("language", DeviceClass.TV)!!.section)
        assertEquals(listOf("settings_cat_proxies", "settings_sect_lan"), trail(place("lan_access", DeviceClass.TV)!!))
        // The page a television opens must be one Settings can land on there.
        val hub = listOf("appearance", "account", "tunnel", "proxies", "dns", "bypass", "sharing", "background", "advanced")
        val advanced = listOf("theme", "root", "backup", "automation", "diagnostics")
        for (entry in SettingsCatalog.entries) {
            val place = SettingsCatalog.place(entry, DeviceClass.TV, Capabilities.NONE, everything) ?: continue
            assertEquals(entry.id, place.section, settingsLanding(place.section, hub, advanced))
        }
    }

    // ─── What was found before ───────────────────────────────────────────────────────────

    @Test
    fun theLastOpenedComesFirstAndIsCounted() {
        var stored = ""
        for (id in listOf("exit_node", "language", "exit_node", "autostart")) stored = SettingsSearchHistory.opened(stored, id)
        assertEquals(listOf("autostart" to 1, "exit_node" to 2, "language" to 1), SettingsSearchHistory.parse(stored))
        // Only so many are kept, and a damaged preference is no history.
        repeat(30) { stored = SettingsSearchHistory.opened(stored, "row$it") }
        assertEquals(12, SettingsSearchHistory.parse(stored).size)
        assertEquals(emptyList<Pair<String, Int>>(), SettingsSearchHistory.parse("nonsense;;:3;x:"))
        // A nudge among equals, and no more however often.
        assertEquals(0, SettingsSearchHistory.boost(0))
        assertEquals(SettingsSearchHistory.boost(3), SettingsSearchHistory.boost(30))
        assertTrue(SettingsSearchHistory.boost(30) < 50)
    }
}
