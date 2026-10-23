package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

class TailsocksFileProvider : DocumentsProvider() {
    private val DEFAULT_ROOT_ID = "tailsocks_root"

    private val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_MIME_TYPES,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID
    )

    private val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SUMMARY,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        
        // Root 1: Taildrop
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, DEFAULT_ROOT_ID)
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Taildrop files")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_TITLE, "TailSocks Taildrop")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
        
        // Root 2: Taildrive
        val activeAccount = AccountManager.getActiveAccount(context ?: return result)
        val driveDocId = "drive_root:${activeAccount.id}"
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "taildrive_root")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Taildrive shares")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_TITLE, "Taildrive")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, driveDocId)
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
        
        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val docId = documentId ?: "root"
        if (docId.startsWith("drive_root") || docId.startsWith("drive_account:") || docId.startsWith("drive_path:")) {
            return queryWebDavDocument(docId, projection)
        }

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(docId)
        if (!file.exists()) throw FileNotFoundException("no such document")

        val displayName = when {
            docId == "root" -> "TailSocks"
            docId.startsWith("account:") -> {
                val accId = docId.substring("account:".length)
                AccountManager.getAccounts(context!!).find { it.id == accId }?.name ?: file.name
            }
            else -> file.name
        }
        
        var flags = 0
        if (file.isDirectory) {
            // Do not allow creating files externally, only view/delete
        } else if (docId != "root" && !docId.startsWith("account:")) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        }

        result.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
            add(DocumentsContract.Document.COLUMN_SIZE, if (file.isDirectory) 0 else file.length())
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, getTypeForFile(file))
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
        return result
    }

    private fun queryWebDavDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        
        if (documentId.startsWith("drive_root")) {
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Taildrive")
                add(DocumentsContract.Document.COLUMN_SIZE, 0)
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)
                add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            }
            return result
        }
        
        if (documentId.startsWith(DriveDocId.PREFIX)) {
            val id = DriveDocId.parse(documentId) ?: throw FileNotFoundException("Invalid drive document ID")
            // On a thread of ours: this one carries the caller's StrictMode policy, and a caller on its
            // main thread — a player asking for the name and size of the file it is about to play — has
            // none of the network (see ProviderWorker).
            val client = TaildriveClient(context, id.accountId)
            val document = try {
                ProviderWorker.run { DriveDocuments.resolve(id, client::list, this::getMimeTypeForName) }
            } catch (e: FileNotFoundException) {
                throw e
            } catch (e: Exception) {
                // Only the class: a message may carry an address.
                android.util.Log.w("Taildrive", "document query: ${e.javaClass.simpleName}")
                throw FileNotFoundException("Taildrive is not available")
            }
            if (document.size == null) android.util.Log.i("Taildrive", "document answered from its name: the request did not")
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, document.name)
                add(DocumentsContract.Document.COLUMN_SIZE, document.size)
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, document.mime)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, document.modified)
                add(DocumentsContract.Document.COLUMN_FLAGS, document.flags)
            }
        }
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parentId = parentDocumentId ?: "root"
        if (parentId.startsWith("drive_root") || parentId.startsWith("drive_account:") || parentId.startsWith("drive_path:")) {
            // Off the binder thread, which carries the caller's StrictMode policy (see ProviderWorker).
            return try {
                ProviderWorker.run { queryWebDavChildDocuments(parentId, projection) }
            } catch (e: Exception) {
                android.util.Log.w("Taildrive", "listing: ${e.javaClass.simpleName}")
                MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
            }
        }

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        
        if (parentId == "root") {
            val accounts = AccountManager.getAccounts(context)
            for (account in accounts) {
                val accountDir = TaildropPaths.ensureDir(context, account.id)
                
                result.newRow().apply {
                    add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "account:${account.id}")
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, account.name)
                    add(DocumentsContract.Document.COLUMN_SIZE, 0)
                    add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, accountDir.lastModified())
                    add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                }
            }
        } else if (parentId.startsWith("account:")) {
            val accId = parentId.substring("account:".length)
            // The id arrives from the SAF client, so it is checked before it
            // becomes a path component.
            if (!TaildropPaths.isSafeAccountId(accId)) return result
            val dir = TaildropPaths.ensureDir(context, accId)
            dir.listFiles()?.forEach { file ->
                if (!file.name.startsWith(".")) {
                    result.newRow().apply {
                        add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "file:$accId/${file.name}")
                        add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
                        add(DocumentsContract.Document.COLUMN_SIZE, file.length())
                        add(DocumentsContract.Document.COLUMN_MIME_TYPE, getTypeForFile(file))
                        add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
                        add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
                    }
                }
            }
        }
        return result
    }

    private fun queryWebDavChildDocuments(parentDocumentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        
        if (parentDocumentId.startsWith("drive_root") || parentDocumentId.startsWith("drive_account:")) {
            val accId = if (parentDocumentId.startsWith("drive_root")) {
                if (parentDocumentId.contains(":")) {
                    parentDocumentId.substringAfter(":")
                } else {
                    AccountManager.getActiveAccount(context)?.id ?: return result
                }
            } else {
                parentDocumentId.substring("drive_account:".length)
            }
            // The daemon off, or a policy that gives this device no Taildrive: say so before the
            // request that would be closed without an answer.
            TaildriveBrowse.listingBlock(context)?.let { block ->
                result.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, unavailableReason(context, block)) }
                return result
            }
            val client = TaildriveClient(context, accId)
            try {
                val list = client.list("/")
                for (file in list) {
                    val cleanHref = file.href.trimEnd('/')
                    if (cleanHref.isNotEmpty() && cleanHref != "/") {
                        val isDir = file.isDirectory
                        val name = file.getDisplayName()
                        val childDocId = "drive_path:$accId:$cleanHref"
                        
                        var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        if (isDir) {
                            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                        }
                        
                        result.newRow().apply {
                            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, childDocId)
                            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
                            add(DocumentsContract.Document.COLUMN_SIZE, if (isDir) 0 else file.size)
                            add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else getMimeTypeForName(name))
                            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified)
                            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                        }
                    }
                }
            } catch (e: Exception) {
                // The class only: a message may carry an address.
                android.util.Log.e("Taildrive", "Error querying drive root children: ${e.javaClass.simpleName}")
                result.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, unavailableReason(context, e, depth = 0)) }
            }
            return result
        }

        if (parentDocumentId.startsWith("drive_path:")) {
            val parts = parentDocumentId.substring("drive_path:".length).split(":", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val parentPath = parts[1]
                TaildriveBrowse.listingBlock(context)?.let { block ->
                    result.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, unavailableReason(context, block)) }
                    return result
                }
                val client = TaildriveClient(context, accId)
                try {
                    val list = client.list(parentPath)
                    val normalizedParent = parentPath.trimEnd('/')
                    val entries = list.filter { file ->
                        val cleanHref = file.href.trimEnd('/')
                        cleanHref != normalizedParent && cleanHref.startsWith(normalizedParent)
                    }
                    // A node's share list comes from the node's daemon; the share's contents
                    // come from its file server, which on Windows and macOS lives in the
                    // Tailscale app. With that app closed the share still lists and then
                    // answers 500 to everything inside, so probe each share here and say so.
                    val summaries = if (isNodeLevel(normalizedParent)) shareSummaries(context, client, entries) else emptyMap()
                    // An empty folder says why when it can: nobody shares, or nothing is shared with us.
                    if (entries.isEmpty()) emptyInfo(context, depthOf(normalizedParent))?.let { info ->
                        result.extras = Bundle().apply { putString(DocumentsContract.EXTRA_INFO, info) }
                    }

                    for (file in entries) {
                        val cleanHref = file.href.trimEnd('/')
                        val isDir = file.isDirectory
                        val name = file.getDisplayName()
                        val childDocId = "drive_path:$accId:$cleanHref"

                        var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        if (isDir) {
                            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                        }

                        result.newRow().apply {
                            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, childDocId)
                            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
                            add(DocumentsContract.Document.COLUMN_SUMMARY, summaries[cleanHref])
                            add(DocumentsContract.Document.COLUMN_SIZE, if (isDir) 0 else file.size)
                            add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else getMimeTypeForName(name))
                            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified)
                            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Taildrive", "Error querying drive_path children: ${e.javaClass.simpleName}")
                    val name = parentPath.trimEnd('/').substringAfterLast("/")
                    result.extras = Bundle().apply {
                        putString(DocumentsContract.EXTRA_ERROR, "$name: ${unavailableReason(context, e, depthOf(parentPath))}")
                    }
                }
            }
        }
        return result
    }

    /** `/<tailnet>/<node>`: the level whose children are the node's shares. */
    private fun isNodeLevel(path: String): Boolean = depthOf(path) == 2

    /** How many names a path has: the root 0, a tailnet 1, a device 2 (see [DriveError.DEVICE_DEPTH]). */
    private fun depthOf(path: String): Int = path.split('/').count { it.isNotEmpty() }

    /** What an empty folder at [depth] means, for the picker to show; null where there is nothing to say. */
    private fun emptyInfo(context: Context, depth: Int): String? = when (depth) {
        1 -> wrapContextWithLocale(context).getString(R.string.taildrive_browser_empty_devices)
        2 -> wrapContextWithLocale(context).getString(R.string.taildrive_browser_empty_shares)
        else -> null
    }

    /**
     * One OPTIONS per share, in parallel. Returns a reason for every share that did not
     * answer 200, keyed by its href without the trailing slash; nothing for the healthy ones.
     */
    private fun shareSummaries(context: Context, client: TaildriveClient, shares: List<WebDavFile>): Map<String, String> {
        val dirs = shares.filter { it.isDirectory }
        if (dirs.isEmpty()) return emptyMap()
        return runBlocking {
            dirs.map { share ->
                async(Dispatchers.IO) {
                    val reason = try {
                        val code = client.probe(share.href)
                        if (code == 200) null else unavailableReason(context, WebDavHttpException("OPTIONS", code, ""), depthOf(share.href))
                    } catch (e: Exception) {
                        unavailableReason(context, e, depthOf(share.href))
                    }
                    reason?.let { share.href.trimEnd('/') to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /**
     * The reason a share or folder could not be listed, in the user's words: what [DriveError]
     * makes of the failure at [depth], never the exception's own text (it may name an address).
     */
    private fun unavailableReason(context: Context, e: Exception, depth: Int): String =
        unavailableReason(context, DriveError.from(e, depth))

    private fun unavailableReason(context: Context, error: DriveError): String =
        wrapContextWithLocale(context).getString(documentReasonRes(error))

    private fun getMimeTypeForName(name: String): String {
        // The app's own table first: the platform's lacks mkv, opus, m4v on older Android, and the
        // type is what decides which players a file is offered to.
        DriveMime.guess(name).takeIf { it != DriveMime.FALLBACK }?.let { return it }
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = name.substring(lastDot + 1).lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        val docId = documentId ?: throw FileNotFoundException("Document ID is null")
        if (docId.startsWith(DriveDocId.PREFIX)) {
            val id = DriveDocId.parse(docId) ?: throw FileNotFoundException("Invalid drive document ID")
            return if (OpenModes.writes(mode)) {
                openWebDavDocumentWrite(id.accountId, id.path)
            } else {
                openWebDavDocumentRead(id.accountId, id.path)
            }
        }

        val file = getFileForDocId(docId)
        if (file.isDirectory) {
            throw FileNotFoundException("Directory cannot be opened as a file")
        }
        // parseMode throws IllegalArgumentException for a mode it does not know, and a null one a
        // NullPointerException: both would cross the binder as they are.
        val standardMode = mode ?: "r"
        if (!OpenModes.isStandard(standardMode)) throw FileNotFoundException("Unsupported mode")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(standardMode))
    }

    private fun openWebDavDocumentRead(accountId: String, path: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("Context is null")
        // With the daemon off there is no tailnet to read from: say so now, not in the first read of a descriptor.
        if (!ProxyState.isActualRunning(context)) throw FileNotFoundException("Taildrive is not running")
        val client = TaildriveClient(context, accountId)
        // A descriptor the reader can seek in, where the platform has one (API 26) and the
        // share's server answers byte ranges; the pipe below otherwise, start to end. Only
        // reads come here: openDocument sends every mode with a "w" in it the other way.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            TaildriveDescriptor.open(context) { client.rangeUpstream(path) }?.let { return it }
        }

        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        val scope = CoroutineScope(Dispatchers.IO)
        
        scope.launch {
            var input: java.io.InputStream? = null
            var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
            try {
                input = client.getDownloadStream(path)
                output = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
                input.use { inStream ->
                    output.use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Taildrive", "Error reading file from WebDAV: ${e.javaClass.simpleName}")
                try {
                    writeFd.closeWithError("Read error")
                } catch (ex: Exception) {}
            } finally {
                try { input?.close() } catch (e: Exception) {}
                try { output?.close() } catch (e: Exception) {}
            }
        }
        return readFd
    }

    private fun openWebDavDocumentWrite(accountId: String, path: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        
        val client = TaildriveClient(context!!, accountId)
        val scope = CoroutineScope(Dispatchers.IO)
        
        scope.launch {
            var input: ParcelFileDescriptor.AutoCloseInputStream? = null
            var output: java.io.OutputStream? = null
            try {
                output = client.getUploadStream(path)
                input = ParcelFileDescriptor.AutoCloseInputStream(readFd)
                input.use { inStream ->
                    output.use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Taildrive", "Error writing file to WebDAV: ${e.javaClass.simpleName}")
                try {
                    readFd.closeWithError("Write error")
                } catch (ex: Exception) {}
            } finally {
                try { input?.close() } catch (e: Exception) {}
                try { output?.close() } catch (e: Exception) {}
            }
        }
        return writeFd
    }

    override fun deleteDocument(documentId: String?) {
        val docId = documentId ?: throw FileNotFoundException("Document ID is null")
        if (docId == "root" || docId.startsWith("drive_root") || docId.startsWith("account:") || docId.startsWith("drive_account:")) {
            throw FileNotFoundException("Cannot delete root or account folder")
        }
        
        if (docId.startsWith("drive_path:")) {
            val parts = docId.substring("drive_path:".length).split(":", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val path = parts[1]
                val client = TaildriveClient(context!!, accId)
                try {
                    ProviderWorker.run { client.delete(path) }
                } catch (e: Exception) {
                    throw FileNotFoundException("Failed to delete WebDAV document (${e.javaClass.simpleName})")
                }
            }
            return
        }
        
        val file = getFileForDocId(docId)
        if (file.exists() && !file.isDirectory) {
            file.delete()
        }
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String {
        val parentId = parentDocumentId ?: throw FileNotFoundException("Parent ID is null")
        val name = displayName ?: throw FileNotFoundException("Display name is null")
        
        if (parentId.startsWith("drive_root") || parentId.startsWith("drive_path:") || parentId.startsWith("drive_account:")) {
            val accId = if (parentId.startsWith("drive_root")) {
                if (parentId.contains(":")) {
                    parentId.substringAfter(":")
                } else {
                    AccountManager.getActiveAccount(context!!)?.id ?: throw FileNotFoundException("No active account")
                }
            } else if (parentId.startsWith("drive_account:")) {
                parentId.substring("drive_account:".length)
            } else {
                parentId.substring("drive_path:".length).split(":", limit = 2)[0]
            }
            val parentPath = if (parentId.startsWith("drive_root") || parentId.startsWith("drive_account:")) {
                ""
            } else {
                parentId.substring("drive_path:".length).split(":", limit = 2)[1]
            }
            
            val cleanParent = parentPath.trimEnd('/')
            val childPath = "$cleanParent/$name"
            val client = TaildriveClient(context!!, accId)
            
            try {
                ProviderWorker.run {
                    if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                        client.createFolder(childPath)
                    } else {
                        client.createEmptyFile(childPath)
                    }
                }
                return "drive_path:$accId:$childPath"
            } catch (e: Exception) {
                throw FileNotFoundException("Failed to create WebDAV document (${e.javaClass.simpleName})")
            }
        }
        throw FileNotFoundException("Create document not supported for Taildrop")
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        val docId = documentId ?: throw FileNotFoundException("Document ID is null")
        val name = displayName ?: throw FileNotFoundException("Display name is null")
        
        if (docId.startsWith("drive_path:")) {
            val parts = docId.substring("drive_path:".length).split(":", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val oldPath = parts[1]
                
                val idx = oldPath.trimEnd('/').lastIndexOf('/')
                val parentPath = if (idx >= 0) oldPath.substring(0, idx) else ""
                val newPath = "$parentPath/$name"
                
                val client = TaildriveClient(context!!, accId)
                try {
                    ProviderWorker.run { client.move(oldPath, newPath) }
                    return "drive_path:$accId:$newPath"
                } catch (e: Exception) {
                    throw FileNotFoundException("Failed to rename WebDAV document (${e.javaClass.simpleName})")
                }
            }
        }
        throw FileNotFoundException("Rename not supported for Taildrop")
    }

    override fun moveDocument(
        sourceDocumentId: String?,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String?
    ): String {
        val docId = sourceDocumentId ?: throw FileNotFoundException("Source Document ID is null")
        val targetParentId = targetParentDocumentId ?: throw FileNotFoundException("Target Parent ID is null")
        
        if (docId.startsWith("drive_path:") && targetParentId.startsWith("drive_path:")) {
            val parts = docId.substring("drive_path:".length).split(":", limit = 2)
            val targetParts = targetParentId.substring("drive_path:".length).split(":", limit = 2)
            if (parts.size == 2 && targetParts.size == 2) {
                val accId = parts[0]
                val oldPath = parts[1]
                val targetParentPath = targetParts[1]
                
                val name = oldPath.substringAfterLast("/")
                val newPath = "${targetParentPath.trimEnd('/')}/$name"
                
                val client = TaildriveClient(context!!, accId)
                try {
                    ProviderWorker.run { client.move(oldPath, newPath) }
                    return "drive_path:$accId:$newPath"
                } catch (e: Exception) {
                    throw FileNotFoundException("Failed to move WebDAV document (${e.javaClass.simpleName})")
                }
            }
        }
        throw FileNotFoundException("Move only supported within Taildrive")
    }

    private fun getFileForDocId(docId: String): File {
        val context = context ?: throw FileNotFoundException("Context is null")
        if (docId == "root") {
            return context.filesDir
        }
        if (docId.startsWith("account:")) {
            val accId = docId.substring("account:".length)
            if (!TaildropPaths.isSafeAccountId(accId)) {
                throw FileNotFoundException("Invalid account ID")
            }
            return TaildropPaths.ensureDir(context, accId)
        }
        if (docId.startsWith("file:")) {
            val path = docId.substring("file:".length)
            val parts = path.split("/", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val fileName = parts[1]
                if (!TaildropPaths.isSafeAccountId(accId) || fileName.contains("/") || fileName.contains("..")) {
                    throw FileNotFoundException("Invalid file path")
                }
                TaildropPaths.migrate(context, accId)
                return File(TaildropPaths.dir(context, accId), fileName)
            }
        }
        throw FileNotFoundException("Document ID not found: $docId")
    }

    // What this provider does not do. The framework's defaults for these throw
    // UnsupportedOperationException, which crosses the binder as it is, and any client with a grant
    // may call them whatever a document's flags say — a file manager asking for a thumbnail of every
    // row, a player for the metadata of the file it opens. Each gets the answer a missing file gets,
    // or an empty one.

    override fun openDocumentThumbnail(documentId: String?, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor? =
        throw FileNotFoundException("No thumbnails")

    override fun getDocumentMetadata(documentId: String): Bundle? = null

    override fun queryRecentDocuments(rootId: String?, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

    override fun querySearchDocuments(rootId: String?, query: String?, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

    override fun copyDocument(sourceDocumentId: String?, targetParentDocumentId: String?): String =
        throw FileNotFoundException("Copy not supported")

    override fun removeDocument(documentId: String?, parentDocumentId: String?) {
        throw FileNotFoundException("Remove not supported")
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        
        // Handle Taildrop
        if (parentDocumentId == "root") {
            return documentId.startsWith("account:") || documentId.startsWith("file:")
        }
        if (parentDocumentId.startsWith("account:")) {
            val accId = parentDocumentId.substring("account:".length)
            return documentId.startsWith("file:$accId/")
        }
        
        // Handle Taildrive
        if (parentDocumentId.startsWith("drive_root")) {
            val parentParts = parentDocumentId.split(":", limit = 2)
            val parentAccId = if (parentParts.size > 1) parentParts[1] else ""
            
            val childParts = documentId.split(":", limit = 3)
            if (childParts[0] == "drive_path" && childParts.size >= 2) {
                val childAccId = childParts[1]
                if (parentAccId.isNotEmpty() && parentAccId != childAccId) return false
                return true
            }
            return false
        }
        
        if (parentDocumentId.startsWith("drive_path:")) {
            val parentParts = parentDocumentId.substring("drive_path:".length).split(":", limit = 2)
            if (parentParts.size == 2) {
                val parentAccId = parentParts[0]
                val parentPath = parentParts[1].trimEnd('/')
                
                if (documentId.startsWith("drive_path:$parentAccId:")) {
                    val childPath = documentId.substring("drive_path:$parentAccId:".length).trimEnd('/')
                    if (parentPath.isEmpty()) {
                        return childPath.isNotEmpty()
                    }
                    val parentWithSlash = if (parentPath.endsWith("/")) parentPath else "$parentPath/"
                    return childPath.startsWith(parentWithSlash)
                }
            }
        }
        
        return false
    }

    private fun getTypeForFile(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val lastDot = file.name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = file.name.substring(lastDot + 1).lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }
}

data class WebDavFile(
    val href: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) {
    fun getDisplayName(): String {
        val clean = href.trimEnd('/')
        val idx = clean.lastIndexOf('/')
        return if (idx >= 0) clean.substring(idx + 1) else clean
    }
}

/** A WebDAV request the daemon answered with an error status; [body] is the first line it sent. */
class WebDavHttpException(method: String, val code: Int, val body: String) :
    Exception("WebDAV $method failed: HTTP $code" + if (body.isNotEmpty()) " ($body)" else "")

class TaildriveClient(private val context: Context, private val accountId: String) {
    private fun getSocksProxy(): Proxy? {
        val socksAddr = GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")
        if (socksAddr.isBlank()) return null
        val host = NetAddr.dialableHost(socksAddr)
        val port = NetAddr.port(socksAddr) ?: return null

        val user = GlobalSettings.getString(context, "socks5_user", "")
        val pass = GlobalSettings.getString(context, "socks5_pass", "")
        if (user.isNotEmpty() || pass.isNotEmpty()) {
            // Scoped to this proxy endpoint only. An unscoped default Authenticator
            // handed the SOCKS credentials to any host in the process that returned
            // a 401/407 — the admin API, DoH, the GitHub update check.
            //
            // The scope must be the SOCKS5 protocol plus host/port, NOT
            // RequestorType.PROXY: java.net.SocksSocketImpl asks through the
            // overload without a RequestorType, which always reports SERVER, so a
            // PROXY check refused every SOCKS request and the Files app saw
            // "SOCKS : authentication failed" whenever the proxy had a password.
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                    if (!requestingProtocol.equals("SOCKS5", ignoreCase = true)) return null
                    val site = requestingSite?.hostAddress
                    if ((requestingHost != host && site != host) || requestingPort != port) return null
                    return java.net.PasswordAuthentication(user, pass.toCharArray())
                }
            })
        }

        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

    /**
     * A connection to [path] on the daemon's Taildrive, not yet sent: through the bridge's
     * loopback endpoint ([TaildriveLocal]), or through the SOCKS5 port where there is none.
     */
    private fun open(path: String): HttpURLConnection {
        TaildriveLocal.endpoint()?.let { local ->
            val conn = getUrl(path).openConnection(local.proxy) as HttpURLConnection
            conn.setRequestProperty(TaildriveLocal.HEADER, local.token)
            return conn
        }
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        return getUrl(path).openConnection(proxy) as HttpURLConnection
    }

    /**
     * The only cleartext destination the app itself talks to; it is the single
     * exception in res/xml/network_security_config.xml. Changing the host here
     * without changing that file makes every request fail with
     * "Cleartext HTTP traffic ... not permitted".
     */
    private fun getUrl(path: String): URL {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val encodedPath = cleanPath.split("/").joinToString("/") { 
            try { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } catch (e: Exception) { it }
        }
        return URL("http://100.100.100.100:8080$encodedPath")
    }

    private fun setRequestMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.requestMethod = method
        } catch (e: Exception) {
            try {
                var targetClass: Class<*> = conn.javaClass
                while (targetClass != Any::class.java) {
                    try {
                        val field = targetClass.getDeclaredField("method")
                        field.isAccessible = true
                        field.set(conn, method)
                        return
                    } catch (ex: NoSuchFieldException) {
                        targetClass = targetClass.superclass ?: break
                    }
                }
                val field = HttpURLConnection::class.java.getDeclaredField("method")
                field.isAccessible = true
                field.set(conn, method)
            } catch (ex: Exception) {
                throw e
            }
        }
    }

    fun list(path: String): List<WebDavFile> {
        val conn = open(path)
        try {
            setRequestMethod(conn, "PROPFIND")
            conn.setRequestProperty("Depth", "1")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = TaildriveLocal.status(conn)
            if (code != 207 && code != 200) {
                throw WebDavHttpException("PROPFIND", code, errorBodyLine(conn))
            }
            return conn.inputStream.use { parsePropFindXml(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * OPTIONS on [path]; the status code. Unlike a Depth-0 PROPFIND, which the sharing
     * node answers from its own share table, OPTIONS is forwarded to the node's file
     * server, so 200 means the share's contents are actually reachable.
     */
    fun probe(path: String): Int {
        val conn = open(path)
        try {
            setRequestMethod(conn, "OPTIONS")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            return TaildriveLocal.status(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** The first line of an error response, for the log and the reason shown to the user. */
    private fun errorBodyLine(conn: HttpURLConnection): String = try {
        conn.errorStream?.bufferedReader()?.use { it.readText() }
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: ""
    } catch (e: Exception) { "" }

    fun delete(path: String) {
        val conn = open(path)
        try {
            setRequestMethod(conn, "DELETE")
            conn.connectTimeout = 10000
            val code = TaildriveLocal.status(conn)
            if (code != 200 && code != 204) {
                throw Exception("WebDAV DELETE failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun createFolder(path: String) {
        val conn = open(path)
        try {
            setRequestMethod(conn, "MKCOL")
            conn.connectTimeout = 10000
            val code = TaildriveLocal.status(conn)
            if (code != 201) {
                throw Exception("WebDAV MKCOL failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun createEmptyFile(path: String) {
        val conn = open(path)
        try {
            setRequestMethod(conn, "PUT")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.outputStream.close()
            val code = TaildriveLocal.status(conn)
            if (code != 201 && code != 200 && code != 204) {
                throw Exception("WebDAV PUT empty failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun move(fromPath: String, toPath: String) {
        val conn = open(fromPath)
        try {
            setRequestMethod(conn, "MOVE")
            val targetUrl = getUrl(toPath)
            conn.setRequestProperty("Destination", targetUrl.toString())
            conn.connectTimeout = 10000
            val code = TaildriveLocal.status(conn)
            if (code != 201 && code != 204 && code != 200) {
                throw Exception("WebDAV MOVE failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun getDownloadStream(path: String): InputStream {
        val conn = open(path)
        setRequestMethod(conn, "GET")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = TaildriveLocal.status(conn)
        if (code != 200) {
            conn.disconnect()
            throw Exception("WebDAV GET failed: HTTP $code")
        }
        return conn.inputStream
    }

    /**
     * The file at [path] a range at a time, for a descriptor a player can seek in
     * ([TaildriveDescriptor]): the same GET the same way as [getDownloadStream],
     * with a `Range` header. [HttpRangeUpstream] sends it and sorts the answers.
     */
    fun rangeUpstream(path: String): RangeUpstream = HttpRangeUpstream {
        try {
            open(path)
        } catch (e: Exception) {
            throw RangeIoException("no way to the daemon")
        }
    }

    fun getUploadStream(path: String): OutputStream {
        val conn = open(path)
        setRequestMethod(conn, "PUT")
        conn.doOutput = true
        conn.setChunkedStreamingMode(4096)
        conn.connectTimeout = 15000
        return conn.outputStream
    }
}

fun parsePropFindXml(inputStream: java.io.InputStream): List<WebDavFile> {
    val files = mutableListOf<WebDavFile>()
    val factory = XmlPullParserFactory.newInstance()
    val parser = factory.newPullParser()
    parser.setInput(inputStream, "UTF-8")

    var eventType = parser.eventType
    var currentTag = ""

    var href = ""
    var isDirectory = false
    var size = 0L
    var lastModified = 0L

    val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> {
                currentTag = parser.name.substringAfter(":")
                if (currentTag.equals("response", ignoreCase = true)) {
                    href = ""
                    isDirectory = false
                    size = 0L
                    lastModified = 0L
                } else if (currentTag.equals("collection", ignoreCase = true)) {
                    isDirectory = true
                }
            }
            XmlPullParser.TEXT -> {
                val text = parser.text.trim()
                if (text.isNotEmpty()) {
                    when {
                        currentTag.equals("href", ignoreCase = true) -> href = text
                        currentTag.equals("getcontentlength", ignoreCase = true) -> size = text.toLongOrNull() ?: 0L
                        currentTag.equals("getlastmodified", ignoreCase = true) -> {
                            try {
                                lastModified = dateFormat.parse(text)?.time ?: 0L
                            } catch (e: Exception) {
                                lastModified = 0L
                            }
                        }
                    }
                }
            }
            XmlPullParser.END_TAG -> {
                val tag = parser.name.substringAfter(":")
                if (tag.equals("response", ignoreCase = true)) {
                    if (href.isNotEmpty()) {
                        // An href is a percent-encoded path, where "+" is a literal plus;
                        // URLDecoder is form decoding and would turn it into a space.
                        val decodedHref = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
                        files.add(WebDavFile(decodedHref, isDirectory, size, lastModified))
                    }
                }
                currentTag = ""
            }
        }
        eventType = parser.next()
    }
    return files
}
