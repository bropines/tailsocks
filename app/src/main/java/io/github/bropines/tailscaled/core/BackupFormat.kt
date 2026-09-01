package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Provenance metadata carried inside a backup.
 *
 * Without it a restore is a blind overwrite: an archive written by a newer build
 * can carry preferences and state files this build does not understand, and
 * applying it silently corrupts the profile instead of failing. Every backup now
 * records what produced it, and a restore refuses anything it cannot honour.
 */
object BackupFormat {

    /** Entry name inside the encrypted ZIP. */
    const val MANIFEST_ENTRY = "backup_manifest.json"

    /**
     * Layout version of the archive itself.
     *
     * Bump this only when the structure changes so that older builds can no
     * longer read it correctly — not on every app release.
     */
    const val CURRENT_FORMAT_VERSION = 1

    @Serializable
    data class Manifest(
        val formatVersion: Int = CURRENT_FORMAT_VERSION,
        val appVersionCode: Long = 0,
        val appVersionName: String = "",
        val packageName: String = "",
        val createdAt: Long = 0
    )

    /** Outcome of comparing a backup against the build that is restoring it. */
    sealed class Compatibility {
        /** Safe to restore. */
        object Ok : Compatibility()

        /** Written before backups carried a manifest; restorable, but unverified. */
        object Legacy : Compatibility()

        /** The archive layout is newer than this build understands. */
        data class FormatTooNew(val backupVersion: Int, val supported: Int) : Compatibility()

        /** The archive comes from a newer app build. */
        data class AppTooNew(val backupVersion: String, val installedVersion: String) : Compatibility()

        /** The archive belongs to a different application. */
        data class WrongPackage(val backupPackage: String) : Compatibility()
    }

    private fun packageInfo(context: Context): PackageInfo? = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    fun current(context: Context): Manifest {
        val info = packageInfo(context)
        return Manifest(
            formatVersion = CURRENT_FORMAT_VERSION,
            appVersionCode = info?.let { versionCodeOf(it) } ?: 0,
            appVersionName = info?.versionName ?: "",
            packageName = context.packageName,
            createdAt = System.currentTimeMillis()
        )
    }

    fun toJson(manifest: Manifest): String = AppJson.encodeToString(manifest)

    fun fromJson(json: String): Manifest? {
        if (json.isBlank()) return null
        return runCatching { AppJson.decodeFromString<Manifest>(json) }.getOrNull()
    }

    /** Reads the manifest out of a decrypted archive, or null for a legacy one. */
    fun readManifest(archive: ByteArray): Manifest? = try {
        var found: Manifest? = null
        ZipInputStream(ByteArrayInputStream(archive)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && found == null) {
                if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                    found = fromJson(zis.readBytes().toString(Charsets.UTF_8))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        found
    } catch (e: Exception) {
        null
    }

    /**
     * Decides whether [manifest] may be restored onto the running build.
     * A backup from an older or equal build is fine; a newer one is refused.
     */
    fun check(context: Context, manifest: Manifest?): Compatibility {
        if (manifest == null) return Compatibility.Legacy

        if (manifest.packageName.isNotEmpty() && manifest.packageName != context.packageName) {
            return Compatibility.WrongPackage(manifest.packageName)
        }
        if (manifest.formatVersion > CURRENT_FORMAT_VERSION) {
            return Compatibility.FormatTooNew(manifest.formatVersion, CURRENT_FORMAT_VERSION)
        }

        val info = packageInfo(context)
        if (info != null && manifest.appVersionCode > 0) {
            val installed = versionCodeOf(info)
            if (manifest.appVersionCode > installed) {
                return Compatibility.AppTooNew(
                    backupVersion = manifest.appVersionName.ifEmpty { manifest.appVersionCode.toString() },
                    installedVersion = info.versionName ?: installed.toString()
                )
            }
        }
        return Compatibility.Ok
    }
}
