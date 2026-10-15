package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.RemoteAccess
import io.github.bropines.tailscaled.core.RemoteAccessClass
import io.github.bropines.tailscaled.core.RemoteAccessSetting
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "What a paired device may do" on the managed device's page: every name the bridge can send
 * has words, and the lines are the bridge's document and nothing else.
 *
 * The names come from the bridge's own list, appctr/testdata/remote_allowlist.json, which its
 * tests hold against the code (remote_server_test.go). A name added there fails here until
 * the page has words for it.
 */
class RemoteManagedAccessTest {

    @Serializable
    private data class ListedSetting(
        val key: String = "",
        val writable: Boolean = false,
        val only: String = "",
        val full: String = "",
        val guard: String = "",
        val files: Boolean = false,
    )

    @Serializable
    private data class ListedOp(
        val op: String = "",
        @kotlinx.serialization.SerialName("class") val role: String = "",
        val full: Boolean = false,
        val files: Boolean = false,
    )

    @Serializable
    private data class Allowlist(
        val settings: List<ListedSetting> = emptyList(),
        val actions: List<String> = emptyList(),
        val permissions: List<String> = emptyList(),
        val askable: List<String> = emptyList(),
        val askResults: List<String> = emptyList(),
        val identifyResults: List<String> = emptyList(),
        val ops: List<ListedOp> = emptyList(),
        val never: List<String> = emptyList(),
    )

    // The working directory of a unit test is the module's: app/.
    private val listed: Allowlist = AppJson.decodeFromString(File("../appctr/testdata/remote_allowlist.json").readText())

    /**
     * The document as the bridge makes it from those lists (appctr/remote_level.go, access):
     * what needs full control is the owner's at that level and listed apart below it; what the
     * device's network access to files closes is nobody's while it is off, and listed apart.
     */
    private fun document(full: Boolean, guests: Boolean = true, files: Boolean = true): RemoteAccess {
        val writable = listed.settings.filter { it.writable }
        fun open(op: ListedOp) = (full || !op.full) && (files || !op.files)
        return RemoteAccess(
            level = if (full) RemoteAccess.LEVEL_FULL else RemoteAccess.LEVEL_BASIC,
            guests = guests,
            elevate = guests,
            files = files,
            guest = RemoteAccessClass(ops = listed.ops.filter { it.role == "guest" && open(it) }.map { it.op }),
            owner = RemoteAccessClass(
                // Asking for full rights is a guest's: an owner has nothing to ask for.
                ops = listed.ops.filter { open(it) && it.op != RemoteAccessWords.OP_ELEVATE }.map { it.op },
                settings = writable.filter { files || !it.files }.map {
                    val needs = it.full.isNotEmpty() && !full
                    RemoteAccessSetting(it.key, only = if (needs && it.full == "true") "false" else it.only, needs = if (needs) "full" else "", guard = it.guard)
                },
                actions = listed.actions,
                permissions = listed.askable,
            ),
            full = if (full) RemoteAccessClass() else RemoteAccessClass(
                ops = listed.ops.filter { it.full && (files || !it.files) }.map { it.op },
                settings = writable.filter { it.full.isNotEmpty() && (files || !it.files) }.map { RemoteAccessSetting(it.key) },
            ),
            withFiles = if (files) RemoteAccessClass() else RemoteAccessClass(
                ops = listed.ops.filter { it.files }.map { it.op },
                settings = writable.filter { it.files }.map { RemoteAccessSetting(it.key) },
            ),
            never = listed.never,
        )
    }

    // The settings by the way they go from afar, as the bridge's list has them: the expectations below
    // follow the list, so a setting added there moves them with it.
    private val settable = listed.settings.filter { it.writable }
    private val needFull = settable.filter { it.full.isNotEmpty() }.map { it.key }
    private val onOnly = settable.filter { it.only == "true" }.map { it.key }
    private val offOnly = settable.filter { it.only == "false" }.map { it.key }
    private val guarded = settable.filter { it.guard.isNotEmpty() && it.only.isEmpty() && it.full.isEmpty() }.map { it.key }

    private fun List<RemoteAccessGroup>.group(title: Int) = firstOrNull { it.title == title }
    private fun RemoteAccessGroup.row(text: Int) = rows.firstOrNull { it.text == text }
    private fun RemoteAccessRow.said() = names.map { it.name }
    private fun List<RemoteAccessGroup>.names() = flatMap { it.rows }.flatMap { it.names }

    @Test
    fun theBridgesListIsThere() {
        // An empty list would pass everything below.
        assertTrue(listed.ops.size >= 19 && listed.settings.size >= 13 && listed.never.size >= 8)
        assertTrue(listed.actions.isNotEmpty() && listed.askable.isNotEmpty() && listed.askResults.isNotEmpty())
    }

    @Test
    fun everyNameTheBridgeCanSendHasWords() {
        val words = RemoteAccessWords
        fun missing(names: List<String>, known: Map<String, Int>) = names.filterNot { it in known }
        assertEquals("operations", emptyList<String>(), missing(listed.ops.map { it.op }, words.seen + words.done))
        assertEquals("settings", emptyList<String>(), missing(listed.settings.filter { it.writable }.map { it.key }, words.settings))
        assertEquals("actions", emptyList<String>(), missing(listed.actions, words.actions))
        assertEquals("permissions", emptyList<String>(), missing(listed.permissions + listed.askable, words.permissions))
        assertEquals("never", emptyList<String>(), missing(listed.never, words.never))
        assertEquals("what came of a request", emptyList<String>(), missing(listed.askResults + listed.identifyResults, words.results))
        // And the log names a guarded exit node by its own key.
        assertTrue(words.KEY_EXIT_NODE in words.changed && words.changed.keys.containsAll(words.settings.keys))
    }

    @Test
    fun noOperationIsBothSeenAndDone() {
        assertEquals(emptySet<String>(), RemoteAccessWords.seen.keys intersect RemoteAccessWords.done.keys)
    }

    @Test
    fun atEitherLevelEveryLineIsInWordsAndNothingIsLeftOut() {
        for (full in listOf(false, true)) for (files in listOf(false, true)) {
            val doc = document(full, files = files)
            val groups = remoteAccessGroups(doc)
            val names = groups.names()
            assertEquals("a name without words at full=$full files=$files", emptyList<String>(), names.filter { it.text == 0 }.map { it.name })
            val said = names.map { it.name }.toSet()
            // Every setting, action, permission and "never" of the document is on the card, and every operation
            // that is not said through its own list.
            val lists = setOf(RemoteAccessWords.OP_SET, RemoteAccessWords.OP_ACTION, RemoteAccessWords.OP_ASK)
            val expected = doc.owner.ops + doc.full.ops + doc.withFiles.ops - lists + doc.owner.settings.map { it.key } +
                doc.withFiles.settings.map { it.key } + doc.owner.actions + doc.owner.permissions + doc.never
            assertEquals("left out at full=$full files=$files", emptyList<String>(), expected.filterNot { it in said })
            // A dozen lines, not forty.
            val lines = groups.sumOf { it.rows.size }
            assertTrue("$lines lines at full=$full files=$files", lines in 8..16)
        }
    }

    /** With the files closed, what they hold is said apart, and nowhere among what an owner or full control has. */
    @Test
    fun withTheFilesClosedTheirLinesStandApart() {
        for (full in listOf(false, true)) {
            val groups = remoteAccessGroups(document(full, files = false))
            val closed = groups.group(R.string.remote_access_with_files)!!
            val filesOps = listed.ops.filter { it.files }.map { it.op }
            val filesSettings = listed.settings.filter { it.files }.map { it.key }
            assertEquals(listOf("folders", "storage.list", "diag.report", "diag.log"), closed.row(R.string.remote_access_see)!!.said())
            assertEquals(listOf("shares.remove", "shares.add", "storage.send"), closed.row(0)!!.said())
            // Closed both ways: a setting there is changed neither way, so it is said as "change".
            assertEquals(filesSettings, closed.row(R.string.remote_access_set)!!.said())
            val elsewhere = groups.filter { it.title != R.string.remote_access_with_files }.flatMap { it.rows }.flatMap { it.said() }
            assertEquals("full=$full", emptyList<String>(), elsewhere.filter { it in filesOps || it in filesSettings })
            // Switching them on from afar is among what nobody may.
            assertTrue("files_on" in groups.group(R.string.remote_access_never)!!.rows.single().said())
        }
        // Open, there is no such group.
        assertNull(remoteAccessGroups(document(full = true, files = true)).group(R.string.remote_access_with_files))
    }

    @Test
    fun theBasicLevelSaysWhatFullControlWouldAdd() {
        val groups = remoteAccessGroups(document(full = false))
        assertEquals(
            listOf(R.string.remote_access_guest, R.string.remote_access_owner, R.string.remote_access_full, R.string.remote_access_never),
            groups.map { it.title }
        )
        val guest = groups.group(R.string.remote_access_guest)!!
        assertEquals(listOf("status", "exit_nodes", "settings", "files"), guest.row(R.string.remote_access_see)!!.said())
        assertTrue(guest.row(R.string.remote_access_see)!!.and)
        assertEquals(listOf("exit_node.set", "unpair", "confirm", "identify"), guest.row(0)!!.said())

        val owner = groups.group(R.string.remote_access_owner)!!
        // An owner may all a guest may: said once, and only the rest is listed.
        assertEquals(R.string.remote_access_like_guest, owner.rows.first().text)
        assertEquals(listOf("folders"), owner.row(R.string.remote_access_see)!!.said())
        // Each setting on the line of the way it goes, and each of those ways has a setting today.
        assertTrue(guarded.containsAll(listOf("accept_dns", "tun_mode")) && onOnly.containsAll(listOf("auto_reconnect", "keep_awake")))
        assertTrue("taildrive_enabled" in needFull && "advertise_routes" in needFull && offOnly == listOf("full_control"))
        assertEquals(guarded, owner.row(R.string.remote_access_set_guarded)!!.said())
        assertEquals(onOnly, owner.row(R.string.remote_access_set_on_only)!!.said())
        // A list that may be cleared and not filled stands with the switches that may be turned off and not on.
        assertEquals(needFull, owner.row(R.string.remote_access_set_needs_full)!!.said())
        assertEquals(offOnly, owner.row(R.string.remote_access_set_off_only)!!.said())
        assertNull(owner.row(R.string.remote_access_set))
        // The actions stand where "action" stood, among what an owner does.
        assertEquals(listed.actions + listOf("files.delete", "shares.remove"), owner.row(0)!!.said())
        assertEquals(listed.askable, owner.row(R.string.remote_access_permissions)!!.said())
        assertFalse("vpn" in listed.askable)

        val full = groups.group(R.string.remote_access_full)!!
        assertEquals(listOf("storage.list", "diag.report", "diag.log"), full.row(R.string.remote_access_see)!!.said())
        assertEquals(listOf("shares.add", "storage.send"), full.row(0)!!.said())
        assertEquals(needFull, full.row(R.string.remote_access_set_with_full)!!.said())
        assertEquals(listed.never, groups.group(R.string.remote_access_never)!!.rows.single().said())
    }

    @Test
    fun withFullControlOnItsLinesAreTheOwners() {
        val groups = remoteAccessGroups(document(full = true))
        assertNull(groups.group(R.string.remote_access_full))
        assertNull(groups.group(R.string.remote_access_owner))
        val owner = groups.group(R.string.remote_access_owner_full)!!
        assertEquals(listOf("folders", "storage.list", "diag.report", "diag.log"), owner.row(R.string.remote_access_see)!!.said())
        assertTrue(owner.row(0)!!.said().containsAll(listOf("shares.add", "storage.send")))
        // What needed full control goes either way now; giving the level up stays one way.
        assertEquals(needFull, owner.row(R.string.remote_access_set)!!.said())
        assertNull(owner.row(R.string.remote_access_set_needs_full))
        assertEquals(offOnly, owner.row(R.string.remote_access_set_off_only)!!.said())
        // A guest gets none of it.
        val guest = groups.group(R.string.remote_access_guest)!!.rows.flatMap { it.said() }
        assertFalse(guest.any { it.startsWith("storage") || it.startsWith("diag") || it.startsWith("shares") })
    }

    @Test
    fun withGuestsShutOutAGuestIsNobodyAndTheOwnerIsSaidInFull() {
        val groups = remoteAccessGroups(document(full = false, guests = false))
        assertEquals(listOf(RemoteAccessRow(R.string.remote_access_guests_off)), groups.group(R.string.remote_access_guest)!!.rows)
        val owner = groups.group(R.string.remote_access_owner)!!
        assertNull(owner.row(R.string.remote_access_like_guest))
        assertEquals(listOf("status", "exit_nodes", "settings", "files", "folders"), owner.row(R.string.remote_access_see)!!.said())
        assertTrue(owner.row(0)!!.said().containsAll(listOf("exit_node.set", "unpair", "confirm", "identify")))
    }

    @Test
    fun fullRightsForAWhileAreAGuestsLineOfItsOwnAndNeverAnOwners() {
        val guest = remoteAccessGroups(document(full = false)).group(R.string.remote_access_guest)!!
        assertEquals(R.string.remote_access_guest_elevate, guest.rows.last().text)
        assertFalse(guest.rows.flatMap { it.said() }.contains(RemoteAccessWords.OP_ELEVATE))
        // An owner that the bridge lists it for all the same is not said to ask.
        val doc = document(full = false).let { it.copy(owner = it.owner.copy(ops = it.owner.ops + RemoteAccessWords.OP_ELEVATE)) }
        val groups = remoteAccessGroups(doc)
        assertEquals(R.string.remote_access_like_guest, groups.group(R.string.remote_access_owner)!!.rows.first().text)
        assertFalse(groups.group(R.string.remote_access_owner)!!.rows.any { it.text == R.string.remote_access_guest_elevate })
        // Where the device does not give them, the guest's list has no such line.
        val off = document(full = false).let { it.copy(elevate = false, guest = it.guest.copy(ops = it.guest.ops - RemoteAccessWords.OP_ELEVATE)) }
        assertFalse(remoteAccessGroups(off).group(R.string.remote_access_guest)!!.rows.any { it.text == R.string.remote_access_guest_elevate })
    }

    @Test
    fun aNameWithoutWordsIsShownAsItCame() {
        val doc = RemoteAccess(
            guest = RemoteAccessClass(ops = listOf("status", "teleport")),
            owner = RemoteAccessClass(
                // Not all a guest may: nothing is folded into "everything a guest may".
                ops = listOf("status", "settings.set", "action", "permission.request", "levitate"),
                settings = listOf(RemoteAccessSetting("warp_drive", only = "true")),
                actions = listOf("self_destruct"),
                permissions = listOf("telepathy"),
            ),
            full = RemoteAccessClass(ops = listOf("time.travel"), settings = listOf(RemoteAccessSetting("flux"))),
            never = listOf("stop", "unheard_of"),
        )
        val groups = remoteAccessGroups(doc)
        val unnamed = groups.names().filter { it.text == 0 }.map { it.name }
        assertEquals(listOf("teleport", "warp_drive", "self_destruct", "levitate", "telepathy", "flux", "time.travel", "unheard_of"), unnamed)
        assertNull(groups.group(R.string.remote_access_owner)!!.row(R.string.remote_access_like_guest))
    }

    @Test
    fun anOperationWithoutItsListKeepsItsOwnWords() {
        // A class the bridge sends no settings, actions or permissions for is still said to have the operation.
        val groups = remoteAccessGroups(RemoteAccess(owner = RemoteAccessClass(ops = listOf("settings.set", "action", "permission.request"))))
        val row = groups.single().rows.single()
        assertEquals(listOf("settings.set", "action", "permission.request"), row.said())
        assertTrue(row.names.all { it.text != 0 })
    }

    @Test
    fun anEmptyDocumentDrawsNothing() {
        assertEquals(emptyList<RemoteAccessGroup>(), remoteAccessGroups(RemoteAccess()))
        assertEquals(emptyList<RemoteAccessGroup>(), remoteAccessGroups(RemoteAccess.of("not a document")))
    }

    @Test
    fun theBridgesDocumentIsRead() {
        val doc = RemoteAccess.of(
            """{"level":"full","guests":false,"guest":{"ops":["status"]},
               "owner":{"ops":["status","settings.set"],"settings":[{"key":"tun_mode","guard":"always"},{"key":"advertise_routes","needs":"full"}],
                        "actions":["reconnect"],"permissions":["storage"]},
               "full":{"ops":[]},"never":["stop"]}"""
        )
        assertEquals(RemoteAccess.LEVEL_FULL, doc.level)
        assertFalse(doc.guests)
        assertEquals(RemoteAccessSetting("advertise_routes", needs = "full"), doc.owner.settings[1])
        assertEquals(listOf("stop"), doc.never)
    }

    @Test
    fun aLogLinesDetailIsTakenApartAsTheBridgePutsItTogether() {
        assertEquals(RemoteLogDetail("storage", "shown"), remoteLogDetail("storage: shown"))
        assertEquals(RemoteLogDetail("storage"), remoteLogDetail("storage"))
        assertEquals(RemoteLogDetail("accept_routes", "true"), remoteLogDetail("accept_routes=true"))
        assertEquals(RemoteLogDetail("advertise_routes", ""), remoteLogDetail("advertise_routes="))
        assertEquals(RemoteLogDetail("advertise_routes", "fd00::/8,10.0.0.0/8"), remoteLogDetail("advertise_routes=fd00::/8,10.0.0.0/8"))
        assertEquals(RemoteLogDetail("exit_node", "not confirmed"), remoteLogDetail("exit_node: not confirmed"))
        assertEquals(
            RemoteLogDetail("exit_node", "not confirmed; the one before is gone, so none", "(the app refused to put it back)"),
            remoteLogDetail("exit_node: not confirmed; the one before is gone, so none (the app refused to put it back)")
        )
        assertEquals(RemoteLogDetail("reconnect", null, "(no answer from the app yet)"), remoteLogDetail("reconnect (no answer from the app yet)"))
        assertEquals(RemoteLogDetail("", null, "(no answer from the app yet)"), remoteLogDetail(" (no answer from the app yet)"))
        assertEquals(RemoteLogDetail(""), remoteLogDetail(""))
    }
}
