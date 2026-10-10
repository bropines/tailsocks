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
 */
class AdminAuditLog(private val file: File, private val maxEntries: Int = 500, private val box: SecretBox? = null) {

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
        file.parentFile?.mkdirs()
        file.appendText(encode(record) + "\n")
        val lines = file.readLines()
        if (lines.size > maxEntries + maxEntries / 5) file.writeText(lines.takeLast(maxEntries).joinToString("\n", postfix = "\n"))
    }

    /** Newest first; [profileId] narrows to one profile. Lines that do not parse or open are skipped. */
    @Synchronized
    fun records(profileId: String? = null): List<AuditRecord> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        val all = lines.mapNotNull(::decode)
        if (box != null && lines.any { it.startsWith("{") }) runCatching { write(all) }
        return all.asReversed().filter { profileId == null || it.profileId == profileId }.take(maxEntries)
    }

    @Synchronized
    fun clear(profileId: String? = null) {
        if (profileId == null) {
            file.delete()
            return
        }
        write(records().filter { it.profileId != profileId }.asReversed())
    }

    companion object {
        private const val AAD = "admin-audit-log"

        fun fileIn(filesDir: File) = File(File(filesDir, "admin"), "audit.jsonl")

        /** The log as the app keeps it: sealed under the at-rest Keystore key. */
        fun of(filesDir: File) = AdminAuditLog(fileIn(filesDir), box = KeystoreSecretBox())
    }
}
