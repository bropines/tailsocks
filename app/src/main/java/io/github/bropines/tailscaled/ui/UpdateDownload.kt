package io.github.bropines.tailscaled.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The in-app update's download (About → Download), and the launch-time update check, in a
 * scope of the process rather than of a screen.
 *
 * The download used to run in the main screen's composition scope, so a rotation cancelled it
 * halfway — the partial file deleted, the dialog gone — and it published its progress with a
 * hop to the main thread for every 8 KB read, ~5000 of them for one APK, to show at most a
 * hundred different numbers. Here it runs until it ends, whatever the screens do, and says how
 * far it has got in [progress], which the About dialog reads when it is open and a recreated
 * one picks up where it was. Only the GitHub updater calls it (UpdateChannel.selfUpdate gates
 * the dialog's button), as before.
 */
internal object UpdateDownload {

    /** Process-wide and never cancelled: what is started here ends on its own. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A download under way: the version it fetches and how far it has got, 0–100. */
    data class Progress(val version: String, val percent: Int)

    private val running = MutableStateFlow<Progress?>(null)

    /** The download under way, or null when there is none. */
    val progress: StateFlow<Progress?> = running.asStateFlow()

    /** Where the APK of [version] is kept once downloaded. */
    fun apkFile(dir: File, version: String) = File(dir, "tailsocks-update-$version.apk")

    /**
     * Whether [file] is a complete APK of this app: a download that went through. Parses the
     * archive — tens of milliseconds for a full APK — so never on the main thread.
     */
    fun isOwnApk(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        return try {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            info != null && info.packageName == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Downloads [url] — the APK of [version] — into [dir], then hands it to the installer.
     * False when a download is already under way; it is not started twice.
     */
    fun start(context: Context, url: String, version: String, dir: File): Boolean {
        synchronized(this) {
            if (running.value != null) return false
            running.value = Progress(version, 0)
        }
        // The application's context, in the app's language: the screen that asked may be gone
        // by the time the toasts and the installer need one.
        val app = wrapContextWithLocale(context.applicationContext)
        scope.launch {
            try {
                download(app, url, version, dir)
            } finally {
                running.value = null
            }
        }
        return true
    }

    private suspend fun download(app: Context, targetUrl: String, version: String, dir: File) {
        pruneUpdateDownloads(app, keepVersion = version)
        val destFile = apkFile(dir, version)
        val tempFile = File(dir, "tailsocks-update-$version.tmp")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            // A server that stops answering must end the download, not leave its progress bar up for ever.
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            val totalLength = conn.contentLength

            conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var totalRead = 0L
                    var shown = 0
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (totalLength > 0) {
                            // Published from this thread, and only when the number changes.
                            val pct = (totalRead * 100 / totalLength).toInt()
                            if (pct != shown) {
                                shown = pct
                                running.value = Progress(version, pct)
                            }
                        }
                    }
                }
            }

            if (isOwnApk(app, tempFile)) {
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
                withContext(Dispatchers.Main) {
                    // Done: the dialog, if it is open, turns its button into Install.
                    running.value = null
                    // Revoked while it downloaded: the installer's own path to the setting
                    // starts an activity, which a context that is not one cannot do.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
                        Toast.makeText(app, app.getString(R.string.main_update_grant_perm), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(app, app.getString(R.string.main_update_installing), Toast.LENGTH_SHORT).show()
                        launchApkInstaller(app, destFile)
                    }
                }
            } else {
                if (tempFile.exists()) tempFile.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.main_check_failed_format, "Corrupted APK downloaded"), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            withContext(Dispatchers.Main) {
                Toast.makeText(app, app.getString(R.string.main_check_failed_format, e.message), Toast.LENGTH_SHORT).show()
            }
        } finally {
            conn?.disconnect()
        }
    }
}
