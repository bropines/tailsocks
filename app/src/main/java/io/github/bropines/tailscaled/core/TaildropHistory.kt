package io.github.bropines.tailscaled.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Taildrop history: every send attempt from the three send sites (Files, the Share
 * sheet, a peer's sheet), arrived or not, and every file received, with what later became
 * of it (saved, deleted). Newest first, at most [MAX_ENTRIES].
 *
 * One JSON file, read and rewritten whole under one lock: the writers are a send on an IO
 * coroutine, the receive thread of [TaildropEvents] and the Files screen, and two of them
 * at once must not each write back the list as it was before the other. The write goes
 * through a temporary file and a rename, so a reader never sees half a list. Blocking I/O
 * throughout — never call it on the main thread.
 */
object TaildropHistory {
    private const val TAG = "TaildropHistory"

    /** Named when the file held sends only; kept, since backups carry it under this name. */
    const val FILE_NAME = "sent_history.json"
    const val MAX_ENTRIES = 500

    /** A resend of the same file to the same device this soon counts as another attempt. */
    private const val RETRY_WINDOW_MS = 60_000L

    /** Where an export is written: the FileProvider's "exports" root (res/xml/file_paths.xml). */
    private const val EXPORT_DIR = "exports"

    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** The history as a list, newest first; empty when there is none or it is unreadable. */
    fun decode(text: String): List<TaildropHistoryEntry> =
        if (text.isBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<TaildropHistoryEntry>>(text) }
            .onFailure { Log.w(TAG, "History unreadable: ${it.message}") }
            .getOrDefault(emptyList())

    fun read(context: Context): List<TaildropHistoryEntry> = synchronized(lock) { readLocked(context) }

    /**
     * Adds one transfer. A send gets its attempt number here, where the earlier attempts
     * are: the same file name to the same device, started within [RETRY_WINDOW_MS] of the
     * previous attempt's end.
     */
    fun record(context: Context, entry: TaildropHistoryEntry) = edit(context) { list ->
        val numbered = if (entry.direction != TaildropDirection.SENT) entry else {
            val startedAt = entry.timestamp - (entry.durationMs ?: 0L)
            val previous = list.firstOrNull {
                it.direction == TaildropDirection.SENT && it.name == entry.name && samePeer(it, entry) &&
                    startedAt - it.timestamp in 0..RETRY_WINDOW_MS
            }
            if (previous == null) entry else entry.copy(attempt = (previous.attempt ?: 1) + 1)
        }
        insertByTime(list, numbered)
    }

    /**
     * A received file was saved; [savedTo] says where, in the words the user would know it by.
     * [savedUri] is the copy's document when it went into the default folder, which the app
     * can open again later; a copy saved through the system's Save dialog has none.
     */
    fun markSaved(context: Context, file: TaildropFile, savedTo: String, savedUri: String? = null) = edit(context) { list ->
        val now = System.currentTimeMillis()
        updateReceived(list, file) { it.copy(savedTo = savedTo, savedAt = now, savedUri = savedUri, saveError = null) }
    }

    /** The automatic save of a received file failed, for [reason]; the file stayed in the inbox. */
    fun markSaveFailed(context: Context, file: TaildropFile, reason: String) = edit(context) { list ->
        updateReceived(list, file) { it.copy(saveError = reason) }
    }

    /** A saved file was hidden from the inbox. Found by value, as [remove] does; the file is not touched. */
    fun dismiss(context: Context, entry: TaildropHistoryEntry) = edit(context) { list ->
        val i = list.indexOf(entry)
        if (i >= 0) list[i] = list[i].copy(dismissedAt = System.currentTimeMillis())
    }

    /** A received file was deleted from the inbox. */
    fun markDeleted(context: Context, file: TaildropFile) = edit(context) { list ->
        val now = System.currentTimeMillis()
        updateReceived(list, file) { it.copy(deletedAt = now) }
    }

    /** Removes one entry, found by value: old entries have no id, and a value is all they have. */
    fun remove(context: Context, entry: TaildropHistoryEntry) = edit(context) { list ->
        val i = list.indexOf(entry)
        if (i >= 0) list.removeAt(i)
    }

    fun clear(context: Context) = edit(context) { it.clear() }

    /**
     * The whole history as a CSV file in the cache, ready for [exportIntent]. One row per
     * entry, newest first; times in the device's zone with the offset written out, sizes
     * in bytes, durations in milliseconds.
     */
    fun exportCsv(context: Context): File {
        val entries = read(context)
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        // One export at a time: the previous one has been handed over already.
        dir.listFiles()?.forEach { it.delete() }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val out = File(dir, "tailsocks-taildrop-history-$day.csv")
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        fun time(ms: Long?) = ms?.let { stamp.format(Date(it)) }
        out.bufferedWriter().use { w ->
            w.write(CSV_COLUMNS.joinToString(","))
            w.write("\r\n")
            entries.forEach { e ->
                val row = listOf(
                    time(e.timestamp),
                    e.direction.name.lowercase(Locale.US),
                    if (e.ok) "ok" else "failed",
                    e.name,
                    e.size?.toString(),
                    e.mime,
                    e.sha256,
                    e.peerName,
                    e.peerId,
                    e.peerIp,
                    e.peerOs,
                    e.route?.name?.lowercase(Locale.US),
                    e.routeAddress,
                    e.durationMs?.toString(),
                    e.httpStatus?.toString(),
                    e.attempt?.toString(),
                    e.error,
                    e.source?.name?.lowercase(Locale.US),
                    e.savedTo,
                    time(e.savedAt),
                    time(e.deletedAt),
                    e.saveError
                )
                w.write(row.joinToString(",") { csvCell(it) })
                w.write("\r\n")
            }
        }
        return out
    }

    /** A share-sheet intent for an export made by [exportCsv]. */
    fun exportIntent(context: Context, csv: File, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csv)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, csv.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the grant on only for a URI it can see in ClipData.
        send.clipData = ClipData.newRawUri(csv.name, uri)
        return Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private val CSV_COLUMNS = listOf(
        "time", "direction", "result", "file", "size_bytes", "mime", "sha256",
        "device", "device_id", "device_ip", "device_os", "route", "route_address",
        "duration_ms", "http_status", "attempt", "error", "source",
        "saved_to", "saved_at", "deleted_at", "save_error"
    )

    /**
     * One CSV field. Quoted when it holds a separator, a quote or a line break; and a text
     * that a spreadsheet would run as a formula — a file name is whatever the sending peer
     * chose — is prefixed with an apostrophe, which spreadsheets read as "this is text".
     */
    private fun csvCell(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val safe = if (value[0] in "=+-@\t\r") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun samePeer(a: TaildropHistoryEntry, b: TaildropHistoryEntry): Boolean =
        if (a.peerId != null && b.peerId != null) a.peerId == b.peerId else a.peerName == b.peerName

    /** Keeps the list newest first: a file saved long after it came in is logged under its arrival. */
    private fun insertByTime(list: MutableList<TaildropHistoryEntry>, entry: TaildropHistoryEntry) {
        val at = list.indexOfFirst { it.timestamp <= entry.timestamp }
        if (at < 0) list.add(entry) else list.add(at, entry)
    }

    /**
     * Applies [change] to the received entry for [file]: by path, then — for a file that
     * moved with the pre-4.0 directory migration — by name. A file that has no entry (it
     * came in before received files were logged) gets one from what the inbox knows.
     */
    private fun updateReceived(
        list: MutableList<TaildropHistoryEntry>,
        file: TaildropFile,
        change: (TaildropHistoryEntry) -> TaildropHistoryEntry
    ) {
        fun live(e: TaildropHistoryEntry) = e.direction == TaildropDirection.RECEIVED && e.deletedAt == null
        val i = list.indexOfFirst { live(it) && it.path == file.Path }
            .takeIf { it >= 0 } ?: list.indexOfFirst { live(it) && it.name == file.Name }
        if (i >= 0) {
            list[i] = change(list[i])
        } else {
            insertByTime(list, change(
                TaildropHistoryEntry(
                    name = file.Name,
                    peerName = "",
                    timestamp = if (file.ModTime > 0) file.ModTime * 1000 else System.currentTimeMillis(),
                    direction = TaildropDirection.RECEIVED,
                    size = file.Size.takeIf { it > 0 },
                    mime = mimeTypeOf(file.Name),
                    path = file.Path.takeIf { it.isNotEmpty() }
                )
            ))
        }
    }

    private fun readLocked(context: Context): List<TaildropHistoryEntry> {
        val f = file(context)
        return if (f.exists()) decode(f.readText()) else emptyList()
    }

    private fun edit(context: Context, change: (MutableList<TaildropHistoryEntry>) -> Unit) {
        synchronized(lock) {
            try {
                val list = readLocked(context).toMutableList()
                change(list)
                while (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
                val target = file(context)
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(AppJson.encodeToString<List<TaildropHistoryEntry>>(list))
                if (!tmp.renameTo(target)) {
                    target.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "History not written: ${e.message}")
            }
        }
    }
}

/** A MIME type from a file name's extension, for the history; null when the name has none. */
fun mimeTypeOf(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    if (ext.isEmpty()) return null
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}
