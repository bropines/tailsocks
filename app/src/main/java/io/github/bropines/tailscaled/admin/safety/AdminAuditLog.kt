package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.secure.KeystoreSecretBox
import io.github.bropines.tailscaled.admin.secure.SecretBox
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import java.io.File

enum class AuditResult {
    /** Applied; the re-read confirmed it. */
    VERIFIED,
    /** Applied; nothing to re-read, or the re-read itself could not run. */
    APPLIED,
    /** Applied, but the re-read shows something else than what was asked. */
    MISMATCH,
    FAILED,
    /** Stopped by a gate before anything was sent. */
    REFUSED,
}

/** One write from this phone: what, on what, when, how it ended, and the server's request ids. */
@Serializable
data class AuditRecord(
    val time: Long,
    val profileId: String,
    val profileName: String = "",
    val kind: ChangeKind,
    val changeClass: ChangeClass,
    val targetType: TargetType,
    val targetId: String,
    val targetName: String,
    /** The change's one-line effect, in the language it was made in. */
    val effect: String,
    val result: AuditResult,
    /** Why it failed or was refused; never a credential. */
    val detail: String? = null,
    val requestIds: List<String> = emptyList(),
    /** Set on the entry of an undo. */
    val undo: Boolean = false,
)

/**
 * The console's own record of every write it attempted, kept on this phone only (`files/admin/
 * audit.jsonl`, outside backups) — the tailnet's audit log says what changed, this one says what
 * this phone asked for, including what failed or was refused. Newest last on disk, the newest
 * [maxEntries] kept. With a [box] every line is sealed on its own (names, users, effects stay
 * off the disk in the clear); lines an earlier build wrote in the clear are sealed on first read.
 *
 * Opening a line is a Keystore round trip, so an instance opens each line once: after the first
 * [records] it keeps what it read, [append] adds to that copy and counts lines from it instead of
 * reading the file again, and the next [records] is answered from memory. The copy is trusted
 * only while the file has the length and time this instance last left it with: a line another
 * instance wrote (a Serve publish, a notification's action) makes the next read a full one.
 */
class AdminAuditLog(private val file: File, private val maxEntries: Int = 500, private val box: SecretBox? = null) {

    /**
     * What this instance knows the file holds: its [lines] (blank or unreadable ones included,
     * as the trim counts them), the [records] they opened to, oldest first — null until a
     * [records] call opened them — and the file's [length] and [modified] time when it knew.
     */
    private class Known(var records: MutableList<AuditRecord>?, var lines: Int, var length: Long = 0, var modified: Long = 0)

    private var known: Known? = null

    private fun stillKnown(): Known? = known?.takeIf { it.length == file.length() && it.modified == file.lastModified() }

    private fun Known.stamped(): Known = apply {
        length = file.length()
        modified = file.lastModified()
    }

    private fun encode(record: AuditRecord): String {
        val json = AppJson.encodeToString(AuditRecord.serializer(), record)
        return box?.seal(json, AAD) ?: json
    }

    private fun decode(line: String): AuditRecord? = runCatching {
        val json = if (line.startsWith("{") || box == null) line else box.open(line, AAD)
        AppJson.decodeFromString(AuditRecord.serializer(), json)
    }.getOrNull()

    private fun write(oldestFirst: List<AuditRecord>) {
        file.parentFile?.mkdirs()
        file.writeText(oldestFirst.joinToString("") { encode(it) + "\n" })
    }

    @Synchronized
    fun append(record: AuditRecord) {
        // Counted before the write: a file someone else changed is counted afresh (without opening a line).
        val k = stillKnown() ?: Known(null, if (file.exists()) file.readLines().size else 0)
        file.parentFile?.mkdirs()
        file.appendText(encode(record) + "\n")
        k.lines++
        k.records?.add(record)
        known = k
        if (k.lines > maxEntries + maxEntries / 5) {
            val lines = file.readLines()
            file.writeText(lines.takeLast(maxEntries).joinToString("\n", postfix = "\n"))
            val kept = k.records
            // The copy follows the trim only when every line was a record; otherwise it is read again.
            k.records = if (kept != null && kept.size == lines.size) kept.takeLast(maxEntries).toMutableList() else null
            k.lines = minOf(lines.size, maxEntries)
        }
        k.stamped()
    }

    /** Newest first; [profileId] narrows to one profile. Lines that do not parse or open are skipped. */
    @Synchronized
    fun records(profileId: String? = null): List<AuditRecord> {
        val all = stillKnown()?.records ?: read()
        // A new list: the copy kept here grows with later appends.
        return all.asReversed().filter { profileId == null || it.profileId == profileId }.take(maxEntries)
    }

    /** Every line opened, once; lines an earlier build wrote in the clear sealed on the way. */
    private fun read(): MutableList<AuditRecord> {
        if (!file.exists()) return mutableListOf<AuditRecord>().also { known = Known(it, 0).stamped() }
        val raw = file.readLines()
        val lines = raw.filter { it.isNotBlank() }
        val all = lines.mapNotNull(::decode).toMutableList()
        var count = raw.size
        if (box != null && lines.any { it.startsWith("{") }) runCatching { write(all) }.onSuccess { count = all.size }
        known = Known(all, count).stamped()
        return all
    }

    @Synchronized
    fun clear(profileId: String? = null) {
        if (profileId == null) {
            file.delete()
            known = null
            return
        }
        val kept = records().filter { it.profileId != profileId }.asReversed()
        write(kept)
        known = Known(kept.toMutableList(), kept.size).stamped()
    }

    companion object {
        private const val AAD = "admin-audit-log"

        fun fileIn(filesDir: File) = File(File(filesDir, "admin"), "audit.jsonl")

        /** The log as the app keeps it: sealed under the at-rest Keystore key. */
        fun of(filesDir: File) = AdminAuditLog(fileIn(filesDir), box = KeystoreSecretBox())
    }
}
