package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.AppPermission
import io.github.bropines.tailscaled.core.RemoteDeviceKeys
import io.github.bropines.tailscaled.core.RemotePromptRules
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * One line of "recent remote changes" in words — the managed page's log, and the same line
 * said on the device's screen when a paired device changes something.
 *
 * No key of the bridge's may reach a person: every name it can put in a line comes from its own
 * list, appctr/testdata/remote_allowlist.json, which its tests hold against the code
 * (remote_server_test.go). A name added there fails here until the app has words for it, in
 * English and in Russian.
 */
class RemoteLogLineTest {

    @Serializable
    private data class ListedSetting(val key: String = "", val writable: Boolean = false)

    @Serializable
    private data class ListedOp(val op: String = "", val full: Boolean = false)

    @Serializable
    private data class Allowlist(
        val settings: List<ListedSetting> = emptyList(),
        val actions: List<String> = emptyList(),
        val askable: List<String> = emptyList(),
        val askResults: List<String> = emptyList(),
        val identifyResults: List<String> = emptyList(),
        /** The lines the device also says on its own screen. */
        val notices: List<String> = emptyList(),
        /** Every operation, and whether it takes full control. */
        val ops: List<ListedOp> = emptyList(),
        /** Every reason a refusal is logged with. */
        val denials: List<String> = emptyList(),
    )

    private val listed: Allowlist = AppJson.decodeFromString(File("../appctr/testdata/remote_allowlist.json").readText())

    /** What a line can say was changed: every setting a paired device may write, and the exit node. */
    private val changeable = listed.settings.filter { it.writable }.map { it.key } + RemoteDeviceKeys.EXIT_NODE

    // ─── The app's strings, as the two languages have them ───────────────────────────────────

    private class Strings(dir: String) {
        val text = HashMap<String, String>()
        val untranslatable = HashSet<String>()

        init {
            val entry = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.forEach { file ->
                entry.findAll(file.readText()).forEach { m ->
                    val (name, attrs, raw) = m.destructured
                    text[name] = raw.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("\\n", "\n")
                    if ("translatable=\"false\"" in attrs) untranslatable += name
                }
            }
        }
    }

    private val english = Strings("values")
    private val russian = Strings("values-ru")
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    /** Every string a line was built from in this test, by name. */
    private val used = HashSet<String>()

    private fun reader(of: Strings): (Int) -> String = { id ->
        val name = names.getValue(id)
        used += name
        // A string kept out of translation is the same in every language.
        of.text[name] ?: english.text.getValue(name).also { assertTrue("$name has no Russian", name in english.untranslatable) }
    }

    private fun en(op: String, detail: String, ok: Boolean = true) = remoteEventLine(op, detail, ok, reader(english))
    private fun ru(op: String, detail: String, ok: Boolean = true) = remoteEventLine(op, detail, ok, reader(russian))

    /**
     * A string is its number in brackets — digits, so nothing of the bridge's can pass for
     * one — and a pattern keeps its places. Whatever letter is in a line built with this was
     * not read from a string: it is the bridge's own key.
     */
    private fun marker(id: Int): String {
        val places = Regex("""%\d\${'$'}s""").findAll(english.text.getValue(names.getValue(id))).map { it.value }.toList()
        return "[$id${places.joinToString("") { " $it" }}]"
    }

    private fun assertInWords(op: String, detail: String) {
        val line = remoteEventLine(op, detail, true, ::marker)
        assertFalse("\"$op\" with \"$detail\" shows the bridge's own word: $line", line.contains(Regex("[A-Za-z_]")))
        // And in both languages it is a sentence with no key in it.
        for (said in listOf(en(op, detail), ru(op, detail))) {
            assertFalse("\"$op\" with \"$detail\": $said", said.contains('_') || said.contains('=') || said.isBlank())
        }
    }

    // ─── No key reaches the screen ───────────────────────────────────────────────────────────

    @Test
    fun everyChangeSaidOnTheScreenIsInWords() {
        assertEquals(
            "the lines the bridge has the device say on its screen",
            setOf("action", "exit_node", "file_deleted", "file_sent", "reverted", "setting", "share_added", "share_removed"),
            listed.notices.toSet()
        )
        for (op in listed.notices) {
            when (op) {
                "setting" -> for (key in changeable - RemoteDeviceKeys.EXIT_NODE) {
                    for (value in listOf("true", "false", "", "10.0.0.0/8,192.168.5.0/24")) assertInWords(op, "$key=$value")
                    assertInWords(op, "$key=true (applied late)")
                }
                "action" -> for (action in listed.actions) {
                    assertInWords(op, action)
                    assertInWords(op, "$action (applied late)")
                }
                "reverted" -> for (key in changeable) {
                    assertInWords(op, "$key: not confirmed")
                    assertInWords(op, "$key: not confirmed; the one before is gone, so none")
                    assertInWords(op, "$key: not confirmed (the app refused to put it back)")
                }
                // The rest name a node, a file or a share: the name is theirs, the words around it the app's.
                else -> {
                    assertInWords(op, "42")
                    assertInWords(op, "42 (applied late)")
                }
            }
        }
    }

    @Test
    fun everyOtherLineOfTheLogIsInWords() {
        for (key in changeable) assertInWords("confirmed", if (key == RemoteDeviceKeys.EXIT_NODE) key else "$key=true")
        for (id in listed.askable) for (result in listed.askResults) assertInWords("permission", "$id: $result")
        for (result in listed.identifyResults) assertInWords("identify", result)
        for (what in listOf("log", "report")) assertInWords("diag", what)
        for (op in listOf("paired", "pair_failed", "pair_locked", "unpaired", "revoked", "revoked_all", "expired", "reset", "browse")) {
            assertInWords(op, "")
        }
        // What the bridge may add when the app did not simply do as asked.
        for (remark in RemoteAccessWords.remarks.keys) assertInWords("action", "${listed.actions.first()} $remark")
    }

    /**
     * A refusal is logged with a reason's name and what it was about, and nothing of the
     * bridge's own is left in the line: «Отказано: the device no longer qualifies as guest»
     * was one, when the reason was a sentence.
     */
    @Test
    fun everyReasonForARefusalIsInWords() {
        assertTrue("the reasons the bridge refuses with", listed.denials.size >= 11 && listed.ops.any { it.full })
        for (why in listed.denials) {
            when (why) {
                "wrong_token", "user_changed" -> assertInWords("denied", why)
                "class_lost" -> for (was in listOf("guest", "owner")) assertInWords("denied", "$why: $was")
                // An operation, which has words here: any of them may be refused, those that take full control for that as well.
                "op_not_allowed" -> for (op in listed.ops) assertInWords("denied", "$why: ${op.op}")
                "op_needs_full" -> for (op in listed.ops.filter { it.full }) assertInWords("denied", "$why: ${op.op}")
                // A setting with the value asked for.
                "setting_only_at_device", "setting_needs_full" -> for (key in changeable - RemoteDeviceKeys.EXIT_NODE) {
                    for (value in listOf("true", "false", "", "10.0.0.0/8,192.168.5.0/24")) assertInWords("denied", "$why: $key=$value")
                }
                // The rest name what the other device sent, as it sent it: the name is its own, the words around it the app's.
                "setting_unknown", "action_unknown", "permission_unknown", "file_outside_storage" -> assertInWords("denied", "$why: 42")
                else -> fail("the bridge refuses with \"$why\", and nothing here says what that is about")
            }
        }
    }

    @Test
    fun aRefusalSaysWhy() {
        assertEquals("Refused: guests are not let in now", en("denied", "class_lost: guest", ok = false))
        assertEquals("Отказано: гостей сейчас не пускают", ru("denied", "class_lost: guest", ok = false))
        assertEquals(
            "Отказано: оно сопряжено как владелец, но владельцем больше не считается — нужно сопрячь заново",
            ru("denied", "class_lost: owner")
        )
        assertEquals("Refused: its pairing key is not the one this device gave it", en("denied", "wrong_token"))
        assertEquals("Отказано: устройство теперь в другом аккаунте", ru("denied", "user_changed"))
        // What was asked for is named the way the page's own list of what a device may do names it.
        assertEquals("Refused: it is not allowed to change settings", en("denied", "op_not_allowed: settings.set"))
        assertEquals("Отказано: ему нельзя менять настройки", ru("denied", "op_not_allowed: settings.set"))
        assertEquals("Отказано: ему нельзя посмотреть отчёт диагностики", ru("denied", "op_not_allowed: diag.report"))
        assertEquals(
            "Refused: full control is off, and it takes that to see the name of every file in this device's storage",
            en("denied", "op_needs_full: storage.list")
        )
        assertEquals(
            "Отказано: полный доступ выключен, а без него нельзя открыть общий доступ к новой папке",
            ru("denied", "op_needs_full: shares.add")
        )
        assertEquals(
            "Refused: starting after a reboot (off) — this way round it is changed only at the device itself",
            en("denied", "setting_only_at_device: auto_start=false")
        )
        assertEquals(
            "Отказано: раздаваемые подсети (10.0.0.0/8) — полный доступ выключен",
            ru("denied", "setting_needs_full: advertise_routes=10.0.0.0/8")
        )
        // A name the other device made up is shown as it came, signs and all.
        assertEquals("Refused: no such setting to change remotely: login_server", en("denied", "setting_unknown: login_server"))
        assertEquals("Отказано: файл вне памяти устройства: a: b (1).txt", ru("denied", "file_outside_storage: a: b (1).txt"))
    }

    @Test
    fun aRequestsCardAndNotificationNameEveryPermissionThatCanBeAskedFor() {
        for (id in listed.askable) {
            val permission = assertNotNullOf(AppPermission.of(id), id)
            // Android 9 and Android 11: access to files is two different things.
            for (sdk in listOf(28, 30)) {
                val title = assertNotNullOf(RemotePromptRules.title(permission, sdk), "$id on API $sdk")
                assertTrue(reader(english)(title).isNotBlank() && reader(russian)(title).isNotBlank())
            }
        }
    }

    @Test
    fun aPermissionHasOneNameWhereverItIsSaid() {
        // The page's list and its log take the name a request's card, notification and line use.
        for (permission in AppPermission.entries) {
            val title = RemotePromptRules.title(permission) ?: continue
            assertEquals(permission.id, title, RemoteAccessWords.permissions[permission.id])
        }
        assertEquals(AppPermission.entries.map { it.id }.toSet(), RemoteAccessWords.permissions.keys)
        // Access to files is one thing up to Android 10 and another from Android 11: two wordings, by version, and no third.
        assertEquals(R.string.remote_prompt_perm_storage, RemotePromptRules.title(AppPermission.STORAGE, 28))
        assertEquals(R.string.remote_prompt_perm_storage, RemotePromptRules.title(AppPermission.STORAGE, 29))
        assertEquals(R.string.remote_prompt_perm_all_files, RemotePromptRules.title(AppPermission.STORAGE, 30))
        assertEquals(R.string.remote_prompt_perm_all_files, RemotePromptRules.title(AppPermission.STORAGE, 36))
        // A unit test's Android is version 0: the older wording, in the log as on the card.
        assertEquals("Asked for a permission: Storage (shown on screen)", en("permission", "storage: shown"))
        assertEquals("Запрошено разрешение: Хранилище (показано уведомление)", ru("permission", "storage: notified"))
    }

    private fun <T : Any> assertNotNullOf(value: T?, what: String): T {
        assertNotNull(what, value)
        return value!!
    }

    @Test
    fun theWordsAreInBothLanguages() {
        everyChangeSaidOnTheScreenIsInWords()
        everyOtherLineOfTheLogIsInWords()
        everyReasonForARefusalIsInWords()
        assertTrue("strings read: ${used.size}", used.size > 40)
        for (name in used) {
            assertTrue("$name has no English", name in english.text)
            assertTrue("$name has no Russian", name in russian.text || name in english.untranslatable)
        }
    }

    // ─── What the lines say ──────────────────────────────────────────────────────────────────

    @Test
    fun aSwitchIsSaidByItsNameAndWhichWayItWent() {
        assertEquals("Setting: MagicDNS (off)", en("setting", "accept_dns=false"))
        assertEquals("Настройка: MagicDNS (выкл.)", ru("setting", "accept_dns=false"))
        assertEquals("Setting: accepting subnet routes (on)", en("setting", "accept_routes=true"))
        assertEquals("Настройка: приём маршрутов подсетей (вкл.)", ru("setting", "accept_routes=true"))
        assertEquals("Setting: starting after a reboot (on)", en("setting", "auto_start=true"))
        assertEquals("Setting: TUN mode (on) (applied late)", en("setting", "tun_mode=true (applied late)"))
    }

    @Test
    fun aListIsSaidWithWhatItHoldsNow() {
        assertEquals("Setting: the subnet routes it offers (192.168.5.0/24,fd00::/64)", en("setting", "advertise_routes=192.168.5.0/24,fd00::/64"))
        assertEquals("Setting: the subnet routes it offers (none)", en("setting", "advertise_routes="))
        assertEquals("Настройка: раздаваемые подсети (нет)", ru("setting", "advertise_routes="))
    }

    @Test
    fun anActionAndAChangePutBackAreNamed() {
        assertEquals("Action: redial the relay servers", en("action", "reconnect_relays"))
        assertEquals("Действие: заново подключиться к реле", ru("action", "reconnect_relays"))
        assertEquals("Put back: TUN mode (not confirmed)", en("reverted", "tun_mode: not confirmed"))
        assertEquals("Возвращено как было: MagicDNS (не подтверждено)", ru("reverted", "accept_dns: not confirmed"))
        assertEquals(
            "Exit node removed: the change was not confirmed, and the node before it is gone",
            en("reverted", "exit_node: not confirmed; the one before is gone, so none")
        )
        // Not put back after all: the line says so twice over, and never "done".
        assertEquals(
            "Put back: MagicDNS (not confirmed) (the app refused to put it back) — not done",
            en("reverted", "accept_dns: not confirmed (the app refused to put it back)", ok = false)
        )
    }

    @Test
    fun aNameIsNeverTakenApart() {
        // A node, a file, a share: the detail is theirs, with whatever signs are in it.
        assertEquals("Exit node: exit-frankfurt", en("exit_node", "exit-frankfurt"))
        assertEquals("File deleted: a=b: notes (1).txt", en("file_deleted", "a=b: notes (1).txt"))
        assertEquals("File sent to it: a=b: notes.txt", en("file_sent", "a=b: notes.txt"))
        assertEquals("Folder shared: Movies", en("share_added", "Movies"))
        assertEquals("Закрыт доступ к папке: Movies", ru("share_removed", "Movies"))
        // Only the bridge's own remark at the end is said in words.
        assertEquals("Folder shared: Movies (applied late)", en("share_added", "Movies (applied late)"))
        assertEquals("Открыт доступ к папке: Movies (применено с опозданием)", ru("share_added", "Movies (applied late)"))
    }

    @Test
    fun aNameTheAppHasNoWordsForIsShownAsTheBridgeNamesIt() {
        // It cannot come from this build's bridge — the tests above hold its lists — and is not hidden if it does.
        assertEquals("Setting: something_new (on)", en("setting", "something_new=true"))
        assertEquals("Action: polish_the_screen", en("action", "polish_the_screen"))
        assertEquals("something_new", en("something_new", "accept_routes=true"))
        // The same for a refusal: a reason, or an operation, that this build has no words for.
        assertEquals("Refused: polish_the_screen: 42", en("denied", "polish_the_screen: 42"))
        assertEquals("Refused: it is not allowed screen.polish", en("denied", "op_not_allowed: screen.polish"))
    }
}
