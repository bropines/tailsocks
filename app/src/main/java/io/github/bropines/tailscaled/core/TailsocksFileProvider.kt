package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        
        if (documentId.startsWith("drive_path:")) {
            val parts = documentId.substring("drive_path:".length).split(":", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val path = parts[1]
                
                val client = TaildriveClient(context, accId)
                try {
                    val list = client.list(path)
                    if (list.isNotEmpty()) {
                        val file = list[0]
                        val isDir = file.isDirectory
                        var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        if (isDir) {
                            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                        }
                        
                        result.newRow().apply {
                            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getDisplayName())
                            add(DocumentsContract.Document.COLUMN_SIZE, if (isDir) 0 else file.size)
                            add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else getMimeTypeForName(file.getDisplayName()))
                            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified)
                            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                        }
                    }
                } catch (e: Exception) {
                    result.newRow().apply {
                        add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                        add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, path.substringAfterLast("/"))
                        add(DocumentsContract.Document.COLUMN_SIZE, 0)
                        add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                        add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)
                        add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                    }
                }
            }
        }
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parentId = parentDocumentId ?: "root"
        if (parentId.startsWith("drive_root") || parentId.startsWith("drive_account:") || parentId.startsWith("drive_path:")) {
            return queryWebDavChildDocuments(parentId, projection)
        }

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        
        if (parentId == "root") {
            val accounts = AccountManager.getAccounts(context)
            for (account in accounts) {
                val accountDir = File(context.filesDir, "states/${account.id}/taildrop")
                if (!accountDir.exists()) accountDir.mkdirs()
                
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
            val dir = File(context.filesDir, "states/$accId/taildrop")
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
                android.util.Log.e("Taildrive", "Error querying drive root children: ${e.message}")
            }
            return result
        }
        
        if (parentDocumentId.startsWith("drive_path:")) {
            val parts = parentDocumentId.substring("drive_path:".length).split(":", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val parentPath = parts[1]
                val client = TaildriveClient(context, accId)
                try {
                    val list = client.list(parentPath)
                    val normalizedParent = parentPath.trimEnd('/')
                    
                    for (file in list) {
                        val cleanHref = file.href.trimEnd('/')
                        if (cleanHref != normalizedParent && cleanHref.startsWith(normalizedParent)) {
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
                    android.util.Log.e("Taildrive", "Error querying drive_path children: ${e.message}")
                }
            }
        }
        return result
    }

    private fun getMimeTypeForName(name: String): String {
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
        if (docId.startsWith("drive_path:")) {
            val parts = docId.substring("drive_path:".length).split(":", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val path = parts[1]
                return if (mode?.contains("w") == true) {
                    openWebDavDocumentWrite(accId, path)
                } else {
                    openWebDavDocumentRead(accId, path)
                }
            }
            throw FileNotFoundException("Invalid drive document ID")
        }

        val file = getFileForDocId(docId)
        if (file.isDirectory) {
            throw FileNotFoundException("Directory cannot be opened as a file")
        }
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    private fun openWebDavDocumentRead(accountId: String, path: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        
        val client = TaildriveClient(context!!, accountId)
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
                android.util.Log.e("Taildrive", "Error reading file from WebDAV: ${e.message}")
                try {
                    writeFd.closeWithError(e.message ?: "Read error")
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
                android.util.Log.e("Taildrive", "Error writing file to WebDAV: ${e.message}")
                try {
                    readFd.closeWithError(e.message ?: "Write error")
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
                    client.delete(path)
                } catch (e: Exception) {
                    throw FileNotFoundException("Failed to delete WebDAV document: ${e.message}")
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
                if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                    client.createFolder(childPath)
                } else {
                    client.createEmptyFile(childPath)
                }
                return "drive_path:$accId:$childPath"
            } catch (e: Exception) {
                throw FileNotFoundException("Failed to create WebDAV document: ${e.message}")
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
                    client.move(oldPath, newPath)
                    return "drive_path:$accId:$newPath"
                } catch (e: Exception) {
                    throw FileNotFoundException("Failed to rename WebDAV document: ${e.message}")
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
                    client.move(oldPath, newPath)
                    return "drive_path:$accId:$newPath"
                } catch (e: Exception) {
                    throw FileNotFoundException("Failed to move WebDAV document: ${e.message}")
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
            if (accId.contains("/") || accId.contains("..")) {
                throw FileNotFoundException("Invalid account ID")
            }
            val dir = File(context.filesDir, "states/$accId/taildrop")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        if (docId.startsWith("file:")) {
            val path = docId.substring("file:".length)
            val parts = path.split("/", limit = 2)
            if (parts.size == 2) {
                val accId = parts[0]
                val fileName = parts[1]
                if (accId.contains("/") || accId.contains("..") || fileName.contains("/") || fileName.contains("..")) {
                    throw FileNotFoundException("Invalid file path")
                }
                return File(context.filesDir, "states/$accId/taildrop/$fileName")
            }
        }
        throw FileNotFoundException("Document ID not found: $docId")
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
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                    if (requestorType != RequestorType.PROXY) return null
                    if (requestingHost != host || requestingPort != port) return null
                    return java.net.PasswordAuthentication(user, pass.toCharArray())
                }
            })
        }

        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

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
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        val conn = getUrl(path).openConnection(proxy) as HttpURLConnection
        try {
            setRequestMethod(conn, "PROPFIND")
            conn.setRequestProperty("Depth", "1")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 207 && code != 200) {
                throw Exception("WebDAV PROPFIND failed: HTTP $code")
            }
            return conn.inputStream.use { parsePropFindXml(it) }
        } finally {
            conn.disconnect()
        }
    }

    fun delete(path: String) {
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        val conn = getUrl(path).openConnection(proxy) as HttpURLConnection
        try {
            setRequestMethod(conn, "DELETE")
            conn.connectTimeout = 10000
            val code = conn.responseCode
            if (code != 200 && code != 204) {
                throw Exception("WebDAV DELETE failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun createFolder(path: String) {
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        val conn = getUrl(path).openConnection(proxy) as HttpURLConnection
        try {
            setRequestMethod(conn, "MKCOL")
            conn.connectTimeout = 10000
            val code = conn.responseCode
            if (code != 201) {
                throw Exception("WebDAV MKCOL failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun createEmptyFile(path: String) {
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        val conn = getUrl(path).openConnection(proxy) as HttpURLConnection
        try {
            setRequestMethod(conn, "PUT")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.outputStream.close()
            val code = conn.responseCode
            if (code != 201 && code != 200 && code != 204) {
                throw Exception("WebDAV PUT empty failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun move(fromPath: String, toPath: String) {
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        val conn = getUrl(fromPath).openConnection(proxy) as HttpURLConnection
        try {
            setRequestMethod(conn, "MOVE")
            val targetUrl = getUrl(toPath)
            conn.setRequestProperty("Destination", targetUrl.toString())
            conn.connectTimeout = 10000
            val code = conn.responseCode
            if (code != 201 && code != 204 && code != 200) {
                throw Exception("WebDAV MOVE failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun getDownloadStream(path: String): InputStream {
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        val conn = getUrl(path).openConnection(proxy) as HttpURLConnection
        setRequestMethod(conn, "GET")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw Exception("WebDAV GET failed: HTTP $code")
        }
        return conn.inputStream
    }

    fun getUploadStream(path: String): OutputStream {
        val proxy = getSocksProxy() ?: throw Exception("SOCKS5 proxy is not running")
        val conn = getUrl(path).openConnection(proxy) as HttpURLConnection
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
                        val decodedHref = URLDecoder.decode(href, "UTF-8")
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
