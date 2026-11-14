package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*

/*
 * Installing a downloaded update and tidying the downloads: the system installer through the
 * FileProvider, a PackageInstaller session when that fails (its result comes back to
 * MainActivity as ACTION_INSTALL_COMPLETE), and the pruning of old APKs. The download itself
 * is UpdateDownload.kt.
 */

fun launchApkInstaller(context: Context, apkFile: java.io.File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, context.getString(R.string.main_update_grant_perm), Toast.LENGTH_LONG).show()
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }
        return
    }

    try {
        val apkUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(installIntent)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "FileProvider install intent failed, falling back to PackageInstaller session", e)
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.openWrite("tailsocks_update", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                action = "ACTION_INSTALL_COMPLETE"
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, flags)

            session.commit(pendingIntent.intentSender)
            session.close()
        } catch (fallbackEx: Exception) {
            Toast.makeText(context, context.getString(R.string.main_installer_failed_format, fallbackEx.message), Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The in-app updater kept every APK it ever downloaded in the external files
 * dir — five releases later that was 118 MB of "app storage". Drop every
 * download that is not newer than the installed version (installed or
 * superseded) and every stale .tmp; keepVersion names the download in flight.
 */
internal fun pruneUpdateDownloads(context: Context, keepVersion: String? = null) {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
    val installed = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }
    // In the order of versions, where a beta is newer than the beta before it and older than its release.
    fun newerThanInstalled(v: String): Boolean = isVersionNewer(installed, v)
    dir.listFiles { f -> f.name.startsWith("tailsocks-update-") }?.forEach { f ->
        val ver = f.name.removePrefix("tailsocks-update-").substringBefore(".apk").substringBefore(".tmp")
        val keep = f.name.endsWith(".apk") && (ver == keepVersion || newerThanInstalled(ver))
        if (!keep) f.delete()
    }
}
