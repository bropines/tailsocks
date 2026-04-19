package io.github.bropines.tailscaled

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import java.io.File

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result
}

fun logSentFile(context: Context, fileName: String, targetName: String) {
    try {
        val historyFile = File(context.filesDir, "sent_history.json")
        val gson = Gson()
        val type = object : com.google.gson.reflect.TypeToken<MutableList<SentFileEntry>>() {}.type
        
        val history: MutableList<SentFileEntry> = if (historyFile.exists()) {
            gson.fromJson(historyFile.readText(), type)
        } else {
            mutableListOf()
        }
        
        history.add(0, SentFileEntry(fileName, targetName, System.currentTimeMillis()))
        if (history.size > 50) history.removeAt(history.size - 1)
        
        historyFile.writeText(gson.toJson(history))
    } catch (e: Exception) {}
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
