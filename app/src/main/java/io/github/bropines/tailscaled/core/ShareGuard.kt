package io.github.bropines.tailscaled.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/*
 * What the exported share target may read for an intent that came from another app.
 *
 * The share sheet reads the URIs it is given with this app's own rights, and whoever starts it
 * chooses the URIs. So an app with no access to this app's data could name a file of it — the
 * node's state, the SOCKS5 credentials — and have it sent to a device of the tailnet, without a
 * tap when the intent also names a Direct Share target. Nothing in a share intent says who sent
 * it in a way the sender cannot forge, so the URI itself is judged: what only this app can read
 * is refused, except the few places this app hands out for sharing itself.
 *
 * The decision takes strings and files, so a unit test runs all of it.
 */

/** The places only this app can read, and the names of its two providers. */
internal class OwnPlaces(
    /** The app's data directories, inside and on external storage. */
    val privateDirs: List<File>,
    val fileProviderAuthority: String,
    val documentsAuthority: String,
)

internal object ShareGuard {
    /**
     * The roots of the app's FileProvider (res/xml/file_paths.xml) that hold what the app shares
     * or what was shared with it: received files and the history export. `storage` is the whole
     * of /storage and is judged by the file it names; the updater's APK is nobody's to send.
     */
    private val SHARED_ROOTS = setOf("taildrop", "exports")
    private const val STORAGE_ROOT = "storage"

    /** Whether a share from outside may have [uri] read for it. */
    fun allows(uri: Uri, own: OwnPlaces): Boolean =
        allows(uri.scheme, uri.authority, uri.path, uri.pathSegments.orEmpty(), own)

    /**
     * The same, of a URI's parts: [path] is its decoded path, [segments] the decoded names of it.
     * A `file:` URI is judged by where its path really leads; a `content:` URI of another app's
     * provider is that provider's to answer for, and one of ours only where we hand such out.
     */
    fun allows(scheme: String?, authority: String?, path: String?, segments: List<String>, own: OwnPlaces): Boolean = when (scheme) {
        ContentResolver.SCHEME_FILE -> path != null && isOutside(File(path), own.privateDirs)
        ContentResolver.SCHEME_CONTENT -> when (authority?.substringAfterLast('@')) { // "10@authority" names a user
            own.fileProviderAuthority -> when (segments.firstOrNull()) {
                in SHARED_ROOTS -> true
                STORAGE_ROOT -> isOutside(File("/$STORAGE_ROOT", segments.drop(1).joinToString("/")), own.privateDirs)
                else -> false
            }
            own.documentsAuthority -> isSharedDocument(segments)
            else -> true
        }
        else -> true
    }

    /**
     * Whether [file] lies outside every one of [privateDirs] and outside /proc, by its canonical
     * path: a link or a `..` that leads into the app's data is followed before the answer.
     * /proc/self is this process to whoever reads it here, and nothing there is another app's to
     * ask for. A path that cannot be resolved is refused.
     */
    fun isOutside(file: File, privateDirs: List<File>): Boolean {
        val real = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (isWithin(real, File("/proc"))) return false
        return privateDirs.none { dir -> isWithin(real, runCatching { dir.canonicalFile }.getOrDefault(dir)) }
    }

    /** Whether [file] is [dir] or below it, name by name: `/data/app` does not hold `/data/app2`. */
    private fun isWithin(file: File, dir: File): Boolean = file == dir || file.startsWith(dir)

    /**
     * Whether a URI of the documents provider names a document it gives to other apps: a received
     * file or a file of a share, as `…/document/<id>` (TailsocksFileProvider). Those are what a
     * file manager browsing the provider shares back to us.
     */
    private fun isSharedDocument(segments: List<String>): Boolean {
        val at = segments.lastIndexOf("document")
        if (at < 0 || at != segments.size - 2) return false
        val id = segments.last()
        return id.startsWith("file:") || DriveDocId.parse(id) != null
    }

    /** This app's own places, for [allows]. */
    fun ownPlaces(context: Context): OwnPlaces {
        val dirs = buildList {
            add(context.dataDir)
            context.filesDir.parentFile?.let(::add)
            add(context.cacheDir)
            // The directory of a direct-boot-aware service: the same data, before the first unlock.
            runCatching { add(context.createDeviceProtectedStorageContext().dataDir) }
            // Android/data/<package> on every volume: the parent of its files and cache directories.
            (context.getExternalFilesDirs(null) + context.externalCacheDirs).forEach { dir ->
                dir?.let { add(it.parentFile ?: it) }
            }
        }
        return OwnPlaces(dirs.distinct(), "${context.packageName}.fileprovider", "${context.packageName}.documents")
    }
}
