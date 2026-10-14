package io.github.bropines.tailscaled.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/*
 * Where a Taildrive download is written: the two kinds of folder of ReceiveFolder, which the
 * download folder shares with Taildrop's. A SAF tree is written through its provider, a directory
 * with java.io.File. Every name is claimed by creating the thing, under the first free name
 * (DownloadNames): a folder that already holds "Movies" gets "Movies (1)", and nothing that was
 * there is ever written over. Blocking throughout.
 */

/** A folder made or a file created by a [DownloadSink]: [ref] is the sink's own handle, [name] what it is called there. */
data class SinkNode(val ref: String, val name: String)

/** The folder a download writes into. */
interface DownloadSink {
    /** A new folder in [parent] — the download folder itself when null — as [name] or the first free "name (n)". */
    fun newFolder(parent: SinkNode?, name: String): SinkNode

    /** A new, empty file in [parent], as [name] or the first free "name (n).ext". */
    fun newFile(parent: SinkNode?, name: String): SinkNode

    fun write(file: SinkNode): OutputStream

    /** Takes away a file that was not finished: half a file under the right name is worse than none. Quiet. */
    fun discard(file: SinkNode)

    companion object {
        fun of(context: Context, folder: ReceiveFolder): DownloadSink = when (folder) {
            is ReceiveFolder.Dir -> DirSink(folder.dir)
            is ReceiveFolder.Tree -> TreeSink(context, folder.treeUri)
        }
    }
}

/**
 * A directory, [root], made when it is missing. A name is claimed by creating it: from Android
 * 11, without access to all files, a listing holds only the app's own files, and a name another
 * app took shows up only when creating it fails. No android.* here, so it runs in a unit test.
 */
class DirSink(private val root: File) : DownloadSink {
    private fun dir(parent: SinkNode?): File {
        val dir = parent?.let { File(it.ref) } ?: root
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("the folder is not there and cannot be made")
        return dir
    }

    override fun newFolder(parent: SinkNode?, name: String): SinkNode = claim(dir(parent), name, DownloadNames::freeFolder) { it.mkdir() }

    override fun newFile(parent: SinkNode?, name: String): SinkNode = claim(dir(parent), name, DownloadNames::freeFile) { it.createNewFile() }

    private fun claim(dir: File, name: String, free: (String, Set<String>) -> String, create: (File) -> Boolean): SinkNode {
        val taken = dir.list()?.toHashSet() ?: throw IOException("the folder cannot be read")
        val wanted = TaildropSave.storableName(name)
        repeat(MAX_NAME_ATTEMPTS) {
            val candidate = File(dir, free(wanted, taken))
            if (create(candidate)) return SinkNode(candidate.path, candidate.name)
            taken += candidate.name
        }
        throw IOException("no free name in the folder")
    }

    override fun write(file: SinkNode): OutputStream = FileOutputStream(File(file.ref))

    override fun discard(file: SinkNode) {
        File(file.ref).delete()
    }

    private companion object {
        const val MAX_NAME_ATTEMPTS = 100
    }
}

/**
 * A folder of the Storage Access Framework, granted when it was chosen. The names a folder holds
 * are asked of its provider once and then kept up as this sink adds to it, so a folder of a
 * thousand files is not listed a thousand times.
 */
class TreeSink(context: Context, private val tree: Uri) : DownloadSink {
    private val resolver: ContentResolver = context.contentResolver
    private val top: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val names = HashMap<String, MutableSet<String>>()

    private fun folder(parent: SinkNode?): Uri = parent?.let { Uri.parse(it.ref) } ?: top

    private fun namesIn(folder: Uri): MutableSet<String> = names.getOrPut(folder.toString()) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val found = HashSet<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let(found::add)
        } ?: throw IOException("the folder cannot be read")
        found
    }

    override fun newFolder(parent: SinkNode?, name: String): SinkNode =
        create(folder(parent), DownloadNames.freeFolder(TaildropSave.storableName(name), namesIn(folder(parent))), DocumentsContract.Document.MIME_TYPE_DIR)

    override fun newFile(parent: SinkNode?, name: String): SinkNode {
        val wanted = DownloadNames.freeFile(TaildropSave.storableName(name), namesIn(folder(parent)))
        // The platform's type for the extension, or none: the device's storage provider fits a
        // name to its type, and one it does not know for the extension gets one appended.
        return create(folder(parent), wanted, mimeTypeOf(wanted) ?: "application/octet-stream")
    }

    private fun create(folder: Uri, name: String, mime: String): SinkNode {
        val made = DocumentsContract.createDocument(resolver, folder, mime, name) ?: throw IOException("the folder refused a new entry")
        // The provider may have named it otherwise, a name of its own on a clash it knew of and we did not.
        val given = displayName(made) ?: name
        namesIn(folder) += given
        return SinkNode(made.toString(), given)
    }

    override fun write(file: SinkNode): OutputStream =
        resolver.openOutputStream(Uri.parse(file.ref), "w") ?: throw IOException("the new file cannot be written")

    override fun discard(file: SinkNode) {
        runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(file.ref)) }
    }

    private fun displayName(doc: Uri): String? = runCatching {
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
