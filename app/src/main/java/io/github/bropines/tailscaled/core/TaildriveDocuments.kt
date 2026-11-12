package io.github.bropines.tailscaled.core

import android.provider.DocumentsContract
import java.io.FileNotFoundException

/*
 * What the documents provider says about one file or folder of a share, and how it opens one:
 * the decisions, with no cursor and no network in them, so that a unit test runs all of it.
 * TailsocksFileProvider puts the answer into a cursor.
 *
 * A player asks before it plays — name, size, type — and what it is told decides what it does.
 * So the answer is the file's, or says honestly what is not known: a size that is not known is
 * null, never 0, and a failure of the request is never a folder.
 */

/** `drive_path:<profile>:<path>`: a file or folder of a share as the provider names it. The path keeps its colons. */
internal data class DriveDocId(val accountId: String, val path: String) {
    /** The last name of the path. */
    val name: String get() = path.trimEnd('/').substringAfterLast('/')

    /** How many names the path has: a tailnet 1, a device 2, a share 3. */
    val depth: Int get() = path.split('/').count { it.isNotEmpty() }

    /**
     * Whether this document lies below [parent]: same profile, and the parent's names are the
     * first names of this path. Names are compared, not characters: `/a/sub` is no parent of
     * `/a/subway`, which a string prefix would have let through.
     */
    fun isUnder(parent: DriveDocId): Boolean {
        if (accountId != parent.accountId) return false
        val mine = DrivePaths.names(path)
        val theirs = DrivePaths.names(parent.path)
        return mine.size > theirs.size && mine.subList(0, theirs.size) == theirs
    }

    override fun toString(): String = "$PREFIX$accountId:$path"

    companion object {
        const val PREFIX = "drive_path:"

        /**
         * Null for anything that is not such an id, names no profile, or carries a path that is
         * not plain ([DrivePaths.isPlain]). The id comes from whatever app holds a grant, and the
         * grant is checked against the id as written: a path with `..` in it would pass as a child
         * of the granted folder and then be resolved by the file server to somewhere else.
         */
        fun parse(documentId: String?): DriveDocId? {
            if (documentId == null || !documentId.startsWith(PREFIX)) return null
            val parts = documentId.substring(PREFIX.length).split(":", limit = 2)
            if (parts.size != 2 || parts[0].isEmpty()) return null
            if (!DrivePaths.isPlain(parts[1])) return null
            return DriveDocId(parts[0], parts[1])
        }
    }
}

/** The paths and names a document id may carry: the ones that mean the same to us and to the file server. */
internal object DrivePaths {
    /** The names of [path], without the empty ones a leading or trailing slash leaves. */
    fun names(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    /**
     * Whether [path] is a list of names and nothing else: no empty name in the middle, no `.` or
     * `..`, no NUL. One leading and one trailing slash are allowed, and so is the empty path (the
     * top of a profile). A backslash is a separator to a Windows file server, so `..` between
     * backslashes is refused as well.
     */
    fun isPlain(path: String): Boolean {
        if ('\u0000' in path) return false
        val inner = path.removePrefix("/").removeSuffix("/")
        if (inner.isEmpty()) return true
        return inner.split('/').all { name -> name.isNotEmpty() && name.split('\\').none { it == "." || it == ".." } }
    }

    /**
     * Whether [name] is one name, as a new document or a rename may be called. The name comes from
     * the app that asks and is appended to the parent's path, so anything that could make it more
     * than one step down — a separator, `.`, `..` — is refused.
     */
    fun isName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
}

/** One document as a cursor row; [size] and [modified] are null where they are not known. */
internal class DriveDocument(
    val name: String,
    val mime: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modified: Long?,
    val flags: Int,
)

internal object DriveDocuments {
    /** The deepest level that is always a folder: `/<tailnet>/<device>/<share>`. */
    private const val SHARE_DEPTH = 3

    /** The flags of a document the server described: this device may write and delete in a share. */
    fun flagsOf(isDirectory: Boolean): Int {
        val flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        return if (isDirectory) flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else flags
    }

    /**
     * The document [id] names, asked for with [list] (a PROPFIND of the path). What the server
     * says is the answer; a request that failed is [fromFailure]'s. Throws [FileNotFoundException]
     * for a document that is not there or not ours to see, and nothing else.
     */
    @Throws(FileNotFoundException::class)
    fun resolve(id: DriveDocId, list: (String) -> List<WebDavFile>, mimeOf: (String) -> String): DriveDocument {
        val files = try {
            list(id.path)
        } catch (e: Exception) {
            return fromFailure(id, e, mimeOf)
        }
        return fromListing(id, files, mimeOf) ?: throw FileNotFoundException("no such document")
    }

    /** The entry of [files] for the path itself, or the first one — a PROPFIND answers with the path's own entry first. */
    fun fromListing(id: DriveDocId, files: List<WebDavFile>, mimeOf: (String) -> String): DriveDocument? {
        val wanted = id.path.trimEnd('/')
        val file = files.firstOrNull { it.href.trimEnd('/') == wanted } ?: files.firstOrNull() ?: return null
        val name = file.getDisplayName()
        return DriveDocument(
            name = name,
            mime = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(name),
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0 else file.size,
            modified = file.lastModified,
            flags = flagsOf(file.isDirectory),
        )
    }

    /**
     * What to say when the request for the document failed. A status that means the document is
     * not there for us is [FileNotFoundException]. Anything else — the daemon off, an answer too
     * slow, a file server that does not answer — still lets the document be named: its name, a
     * type from the name, a size that is not known, and nothing allowed. The failure is not
     * turned into a folder with no content: a player given that does not try the file.
     */
    @Throws(FileNotFoundException::class)
    fun fromFailure(id: DriveDocId, error: Exception, mimeOf: (String) -> String): DriveDocument {
        val status = (error as? WebDavHttpException)?.code
        if (status == 404 || status == 410) throw FileNotFoundException("no such document")
        if (status == 401 || status == 403 || status == 407) throw FileNotFoundException("access denied")
        val name = id.name
        val folder = looksLikeFolder(id, mimeOf(name))
        return DriveDocument(
            name = name,
            mime = if (folder) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(name),
            isDirectory = folder,
            size = if (folder) 0 else null,
            modified = null,
            flags = 0,
        )
    }

    /**
     * Whether a document nobody could describe is a folder: everything down to a share is, and
     * below it a name with no extension that [mime] knows. Only a guess, and only for a failure.
     */
    private fun looksLikeFolder(id: DriveDocId, mime: String): Boolean =
        id.depth <= SHARE_DEPTH || (mime == DriveMime.FALLBACK && '.' !in id.name.trimStart('.'))
}

internal object OpenModes {
    /** Whether [mode] asks to write: a "w" anywhere, as `ParcelFileDescriptor.parseMode` reads it. */
    fun writes(mode: String?): Boolean = mode?.contains('w') == true

    /** The modes `ParcelFileDescriptor.parseMode` takes; it throws IllegalArgumentException for the rest. */
    fun isStandard(mode: String): Boolean = mode in STANDARD

    private val STANDARD = setOf("r", "w", "wt", "wa", "rw", "rwt")
}
