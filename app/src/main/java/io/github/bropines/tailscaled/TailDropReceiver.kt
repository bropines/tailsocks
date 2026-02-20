package io.github.bropines.tailscaled

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import java.io.File

class TailDropReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val filePath = intent.getStringExtra("FILE_PATH") ?: return
        val notifId = intent.getIntExtra("NOTIF_ID", 0)
        val file = File(filePath)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            "ACCEPT_FILE" -> {
                if (file.exists()) {
                    try {
                        // Сохраняем в публичную папку Загрузки
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val destFile = File(downloadsDir, file.name.removeSuffix(".pending"))
                        file.copyTo(destFile, overwrite = true)
                        file.delete()
                        Toast.makeText(context, "Saved to Downloads: ${destFile.name}", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                nm.cancel(notifId)
            }
            "REJECT_FILE" -> {
                if (file.exists()) file.delete()
                nm.cancel(notifId)
                Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}