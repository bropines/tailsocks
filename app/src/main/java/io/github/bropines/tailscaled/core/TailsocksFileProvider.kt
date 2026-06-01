package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

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
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, DEFAULT_ROOT_ID)
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Taildrop files")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_TITLE, "TailSocks")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(documentId ?: "root")
        
        val displayName = when {
            documentId == "root" -> "TailSocks"
            documentId?.startsWith("account:") == true -> {
                val accId = documentId.substring("account:".length)
                AccountManager.getAccounts(context!!).find { it.id == accId }?.name ?: file.name
            }
            else -> file.name
        }
        
        var flags = 0
        if (file.isDirectory) {
            // Не разрешаем создавать файлы снаружи, только смотреть/удалять
        } else if (documentId != "root" && documentId?.startsWith("account:") != true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        }

        result.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
            add(DocumentsContract.Document.COLUMN_SIZE, if (file.isDirectory) 0 else file.length())
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, getTypeForFile(file))
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        
        if (parentDocumentId == "root") {
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
        } else if (parentDocumentId?.startsWith("account:") == true) {
            val accId = parentDocumentId.substring("account:".length)
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

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = getFileForDocId(documentId!!)
        if (file.isDirectory) {
            throw FileNotFoundException("Directory cannot be opened as a file")
        }
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun deleteDocument(documentId: String?) {
        if (documentId == null || documentId == "root" || documentId.startsWith("account:")) {
            throw FileNotFoundException("Cannot delete root or account folder")
        }
        val file = getFileForDocId(documentId)
        if (file.exists() && !file.isDirectory) {
            file.delete()
        }
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
