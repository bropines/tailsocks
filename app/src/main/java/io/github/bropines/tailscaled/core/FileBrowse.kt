package io.github.bropines.tailscaled.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/*
 * What the in-app file browser (ui/FileBrowser.kt) knows, without Compose: the places it can
 * start from, a folder's listing and its order, the Up rule, and what the app may read at all.
 * It exists because an Android TV often has no system document picker, so Taildrop (files to
 * send, a folder to save into) and Taildrive (a folder to share) cannot ask the system. It
 * browses java.io.File paths — the same raw-path access Taildrive's daemon already relies on —
 * so everything below is path arithmetic plus a thin layer over the platform's storage APIs.
 *
 * What it can see, by Android version (the grant is [StorageAccess]):
 *  - 7.0–9 (API 24–28): the Storage permission, asked at run time, gives read and write on the
 *    primary storage and read on removable volumes. Android never lets an app write to the
 *    root of a removable volume by path before 11, whatever it holds.
 *  - 10 (API 29): the same, through requestLegacyExternalStorage (AndroidManifest.xml), which
 *    Android 10 still honours.
 *  - 11 and later (API 30+): without All files access a path-based app sees its own folders
 *    (Android/data|media|obb/<package>, on every volume) and, at most, media files that the
 *    system lets through. The manifest declares only READ_EXTERNAL_STORAGE, which from 13 grants
 *    nothing, so in practice every other folder lists empty or refuses: [FileSource.access]
 *    says NEEDS_ALL_FILES and the browser offers the grant. With All files access it sees
 *    everything but other apps' Android/data and Android/obb, on every volume, and may write
 *    to removable ones.
 */

/** What the app may do with raw paths on this device; see [FileBrowse.storageAccess]. */
enum class StorageAccess {
    /** All the platform lets a path-based app do. */
    GRANTED,

    /** Android 10 and below: the Storage permission (read and write) is missing; a run-time dialog asks for it. */
    NEEDS_PERMISSION,

    /** Android 11 and later: All files access is missing; only a Settings page (or adb) grants it. */
    NEEDS_ALL_FILES,
}

enum class RootKind {
    /** The device's shared storage ("Internal storage"). */
    PRIMARY,

    /** An SD card, a USB stick or other volume apart from the primary one. */
    VOLUME,

    /** The public Downloads folder, a shortcut into the primary storage. */
    DOWNLOADS,

    /** The folder the caller asked the browser to open at. */
    START,
}

/**
 * A place the browser starts from. [label] is the volume's own description, localised by the
 * system ("SD card", "USB drive", the stick's name); null for the other kinds, which the screen
 * names itself, and for a volume the system gave no name.
 */
data class BrowseRoot(val kind: RootKind, val dir: File, val label: String? = null, val removable: Boolean = false)

/** Where the browser is: the list of places, or inside a folder. */
sealed interface BrowseLocation {
    data object Roots : BrowseLocation
    data class Dir(val dir: File) : BrowseLocation
}

/** One file or folder of a listing. [size] is 0 for a folder, [modified] 0 when unknown. */
data class FileEntry(val file: File, val isDirectory: Boolean, val size: Long = 0, val modified: Long = 0) {
    val name: String get() = file.name
    val isHidden: Boolean get() = FileBrowse.isHiddenName(name)
}

/** What a folder held when it was listed, or why it could not be. Never an exception. */
sealed interface Listing {
    val dir: File

    /**
     * [entries] are in display order. [limited]: the system hides files from this app in this
     * folder until the access is granted, so an empty or short list proves nothing. [writable]:
     * the app can create files here.
     */
    data class Entries(
        override val dir: File,
        val entries: List<FileEntry>,
        val limited: Boolean = false,
        val writable: Boolean = false,
    ) : Listing

    data class Unreadable(override val dir: File, val reason: Reason) : Listing

    enum class Reason {
        /** The folder is gone — a stick pulled out, a folder deleted meanwhile. */
        NOT_FOUND,

        /** It exists and the system refuses it, whatever the app is granted. */
        DENIED,

        /** It refuses because the storage access is missing; granting it may help. */
        NO_ACCESS,
    }
}

/** The breadcrumb of a folder: the place it is in, and the folder names under that place. */
data class Breadcrumb(val root: BrowseRoot?, val trail: List<String>)

/** A storage volume as the platform reports it, before it becomes a [BrowseRoot]. */
data class VolumeInfo(val dir: File?, val primary: Boolean, val removable: Boolean, val mounted: Boolean, val description: String?)

/**
 * Where the browser reads from. [RealFileSource] is the device; a test or a preview supplies its
 * own. Every method may block on the disk — call them off the main thread.
 */
interface FileSource {
    /** The places to start from, in the order they are shown; [start] is the caller's folder, if any. */
    fun roots(start: File? = null): List<BrowseRoot>

    /** What [dir] holds; hidden entries (a leading dot) only with [showHidden]. */
    fun list(dir: File, showHidden: Boolean = false): Listing

    /** Size, time and kind of one path, null when it does not exist. */
    fun describe(file: File): FileEntry?

    /** What the app may read here now; read again after the user comes back from Settings. */
    val access: StorageAccess

    /** Whether files outside the app's own folders stay out of reach until the user grants access. */
    val needsAllFilesAccess: Boolean get() = access != StorageAccess.GRANTED

    /** [file] with links resolved ("/sdcard/Download" is "/storage/emulated/0/Download"), when the disk can tell. */
    fun canonical(file: File): File = file
}

object FileBrowse {
    // Names -----------------------------------------------------------------------------

    /** A leading dot: Android's own convention for what a file manager hides. */
    fun isHiddenName(name: String): Boolean = name.startsWith('.')

    /**
     * Case-insensitive, number-aware order: "file2" before "file10", "a 9" before "a 10". A run
     * of digits is compared as a number by its length and digits, so a 40-digit run does not
     * overflow; the rest, character by character with case folded (Cyrillic and Greek included).
     * Zero for names that differ only in case or in leading zeros — see [entryOrder].
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (isDigit(a[i]) && isDigit(b[j])) {
                var ie = i
                while (ie < a.length && isDigit(a[ie])) ie++
                var je = j
                while (je < b.length && isDigit(b[je])) je++
                // The numbers without their leading zeros (one digit stays, so "0" is "0").
                var is0 = i
                while (is0 < ie - 1 && a[is0] == '0') is0++
                var js0 = j
                while (js0 < je - 1 && b[js0] == '0') js0++
                val la = ie - is0
                val lb = je - js0
                if (la != lb) return la.compareTo(lb)
                for (k in 0 until la) {
                    val d = a[is0 + k].compareTo(b[js0 + k])
                    if (d != 0) return d
                }
                i = ie
                j = je
            } else {
                val ca = fold(a[i])
                val cb = fold(b[j])
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun fold(c: Char): Char = Character.toLowerCase(Character.toUpperCase(c))

    /** Folders first, then [naturalCompare]; names it cannot tell apart ("a" and "A") by their characters, so the order is total. */
    val entryOrder: Comparator<FileEntry> = Comparator { a, b ->
        when {
            a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
            else -> naturalCompare(a.name, b.name).takeIf { it != 0 } ?: a.name.compareTo(b.name)
        }
    }

    /** [entries] in display order, without the hidden ones unless [showHidden]. */
    fun arrange(entries: List<FileEntry>, showHidden: Boolean): List<FileEntry> =
        entries.filter { showHidden || !it.isHidden }.sortedWith(entryOrder)

    // Paths -----------------------------------------------------------------------------

    /**
     * The names of an absolute path, with "." dropped and ".." applied; empty for "/". Paths are
     * compared by these, never by string prefix: ".../Download2" is not inside ".../Download".
     */
    fun segments(file: File): List<String> {
        val out = ArrayList<String>()
        for (part in file.absolutePath.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(part)
            }
        }
        return out
    }

    private fun fileOf(segments: List<String>): File = File(segments.joinToString("/", prefix = "/"))

    /** [file] spelled once and only one way: absolute, no "." or "..", no trailing slash. */
    fun clean(file: File): File = fileOf(segments(file))

    fun same(a: File, b: File): Boolean = segments(a) == segments(b)

    /** [child] is [parent] or somewhere below it. */
    fun isWithin(child: File, parent: File): Boolean {
        val c = segments(child)
        val p = segments(parent)
        return c.size >= p.size && c.subList(0, p.size) == p
    }

    /** The folder names from [parent] down to [child]; null when [child] is not within [parent]. */
    fun relative(child: File, parent: File): List<String>? =
        if (isWithin(child, parent)) segments(child).drop(segments(parent).size) else null

    /** The folder above [dir]; null for "/". */
    fun parentOf(dir: File): File? {
        val seg = segments(dir)
        return if (seg.isEmpty()) null else fileOf(seg.dropLast(1))
    }

    // Places and the Up rule ---------------------------------------------------------------

    /**
     * The highest folder one can climb to from [dir]: the volume (primary or removable) it sits
     * on; failing that the caller's folder or other root that contains it; else [dir] itself, so
     * a path the roots do not know about is never climbed out of into "/".
     */
    fun top(dir: File, roots: List<BrowseRoot>): File {
        fun deepest(kinds: (BrowseRoot) -> Boolean) = roots.filter { kinds(it) && isWithin(dir, it.dir) }
            .maxByOrNull { segments(it.dir).size }
        val volume = deepest { it.kind == RootKind.PRIMARY || it.kind == RootKind.VOLUME }
        return (volume ?: deepest { true })?.dir ?: dir
    }

    fun isTop(dir: File, roots: List<BrowseRoot>): Boolean = same(dir, top(dir, roots))

    /**
     * One level up from [from]: the parent folder, or the list of places from the top of a
     * volume. Null above the list of places — there Back cancels.
     */
    fun up(from: BrowseLocation, roots: List<BrowseRoot>): BrowseLocation? = when (from) {
        BrowseLocation.Roots -> null
        is BrowseLocation.Dir ->
            if (isTop(from.dir, roots)) BrowseLocation.Roots
            else parentOf(from.dir)?.let { BrowseLocation.Dir(it) } ?: BrowseLocation.Roots
    }

    /**
     * Where the browser opens: [start], or the nearest folder above it that exists when it has
     * been deleted since (a remembered folder); null — the list of places — when there is no
     * start or nothing of it is left within [roots]. [isDirectory] is the disk's answer.
     */
    fun initialDir(start: File?, roots: List<BrowseRoot>, isDirectory: (File) -> Boolean): File? {
        var dir: File? = start?.let(::clean)
        while (dir != null) {
            val here: File = dir
            if (roots.any { isWithin(here, it.dir) } && isDirectory(here)) return here
            dir = parentOf(here)
        }
        return null
    }

    /** The place [dir] is in and the names under it, for the path line; the root is null for a path none of [roots] holds. */
    fun breadcrumb(dir: File, roots: List<BrowseRoot>): Breadcrumb {
        val top = top(dir, roots)
        val root = roots.filter { same(it.dir, top) }.minByOrNull { it.kind.ordinal }
        val base = if (root != null) top else null
        val trail = if (base != null) segments(dir).drop(segments(base).size) else segments(dir)
        return Breadcrumb(root, trail)
    }

    // Roots -----------------------------------------------------------------------------

    /**
     * The folder a volume's app-specific directory sits in: "/storage/1234-ABCD" for
     * "/storage/1234-ABCD/Android/data/<package>/files". What Context.getExternalFilesDirs gives
     * for every mounted volume, so a volume the platform has no path for is still found.
     */
    fun volumeRootOfAppDir(dir: File): File? {
        val seg = segments(dir)
        val i = androidAreaIndex(seg)
        return if (i < 0 || seg.getOrNull(i + 1) !in APP_AREAS) null else fileOf(seg.take(i))
    }

    private val APP_AREAS = setOf("data", "media", "obb")

    /**
     * Where "Android" sits in [seg] when it is the top of a volume — "/sdcard/Android",
     * "/storage/1234-ABCD/Android", "/storage/emulated/0/Android", "/mnt/media_rw/1234-ABCD/Android" —
     * and not a folder of that name somewhere inside one; -1 if there is none.
     */
    private fun androidAreaIndex(seg: List<String>): Int = seg.indexOf("Android").takeIf { it in 1..3 } ?: -1

    /**
     * [primary] (Environment.getExternalStorageDirectory), [downloads] (the public Downloads
     * folder), the mounted [volumes] and the volumes behind [appDirs] (getExternalFilesDirs),
     * and the caller's [start], as the roots to offer: start, primary, downloads, then the other
     * volumes in the system's order. A place that is not a folder on the disk now ([isDirectory])
     * or that another root already is — a start folder that is the Downloads folder — is left out.
     */
    fun assembleRoots(
        primary: File?,
        downloads: File?,
        volumes: List<VolumeInfo>,
        appDirs: List<File?>,
        start: File?,
        isDirectory: (File) -> Boolean,
    ): List<BrowseRoot> {
        val out = ArrayList<BrowseRoot>()
        fun add(root: BrowseRoot) {
            val dir = clean(root.dir)
            if (out.none { same(it.dir, dir) } && isDirectory(dir)) out.add(root.copy(dir = dir))
        }
        primary?.let { add(BrowseRoot(RootKind.PRIMARY, it)) }
        downloads?.let { add(BrowseRoot(RootKind.DOWNLOADS, it)) }
        for (v in volumes) {
            val dir = v.dir
            if (dir == null || !v.mounted || v.primary) continue
            add(BrowseRoot(RootKind.VOLUME, dir, v.description?.trim()?.takeIf { it.isNotEmpty() }, v.removable))
        }
        for (appDir in appDirs) {
            val volume = appDir?.let(::volumeRootOfAppDir) ?: continue
            add(BrowseRoot(RootKind.VOLUME, volume, null, true))
        }
        // The caller's folder goes first, but only when nothing else already is that folder.
        if (start != null && out.none { same(it.dir, start) } && isDirectory(clean(start))) {
            out.add(0, BrowseRoot(RootKind.START, clean(start)))
        }
        return out
    }

    // Reading a folder --------------------------------------------------------------------

    /**
     * [dir]'s contents in display order, or the reason there are none to show. [limited] is
     * passed on to the result, and makes a refusal one that granting the access may lift
     * ([Listing.Reason.NO_ACCESS]) rather than one it will not ([Listing.Reason.DENIED]).
     * A symbolic link to a folder counts as a folder.
     */
    fun listDirectory(dir: File, showHidden: Boolean, limited: Boolean = false): Listing {
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        }
        if (children == null) {
            val reason = when {
                limited -> Listing.Reason.NO_ACCESS
                dir.isDirectory -> Listing.Reason.DENIED
                else -> Listing.Reason.NOT_FOUND
            }
            return Listing.Unreadable(dir, reason)
        }
        val entries = arrange(children.map(::entryOf), showHidden)
        return Listing.Entries(dir, entries, limited, writable = dir.canWrite())
    }

    fun entryOf(file: File): FileEntry {
        val folder = file.isDirectory
        return FileEntry(file, folder, size = if (folder) 0 else file.length(), modified = file.lastModified())
    }

    // Access --------------------------------------------------------------------------------

    /** Which grant, if any, is missing on Android [sdk]; see the table at the top of this file. */
    fun storageAccessFor(sdk: Int, storagePermission: Boolean, allFilesAccess: Boolean): StorageAccess = when {
        sdk >= Build.VERSION_CODES.R -> if (allFilesAccess) StorageAccess.GRANTED else StorageAccess.NEEDS_ALL_FILES
        else -> if (storagePermission) StorageAccess.GRANTED else StorageAccess.NEEDS_PERMISSION
    }

    /**
     * The grant as of now. Below Android 11 it is the Storage permission, both halves — the
     * same check as Taildrive's, so one grant serves both; from 11, All files access.
     */
    fun storageAccess(context: Context): StorageAccess {
        fun held(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val sdk = Build.VERSION.SDK_INT
        return storageAccessFor(
            sdk,
            storagePermission = held(Manifest.permission.READ_EXTERNAL_STORAGE) && held(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            allFilesAccess = sdk >= Build.VERSION_CODES.R && Environment.isExternalStorageManager(),
        )
    }

    /**
     * The Settings pages that grant [StorageAccess.NEEDS_ALL_FILES] (or lift a refused Storage
     * permission), most direct first. A TV's Settings often has none of them: start each in turn
     * and catch ActivityNotFoundException, then fall back to [adbGrantCommand].
     */
    fun grantIntents(context: Context): List<Intent> {
        val pkg = Uri.parse("package:${context.packageName}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        } else {
            listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    /** What to type on a computer when the device has no Settings page for the grant (a TV often has none). */
    fun adbGrantCommand(packageName: String): String =
        "adb shell appops set $packageName MANAGE_EXTERNAL_STORAGE allow"

    private val SHARED_STORAGE = setOf("storage", "sdcard", "mnt")

    /** A path on shared storage or a removable volume — where the missing access matters — as opposed to the app's private storage. */
    fun isSharedStoragePath(dir: File): Boolean = segments(dir).firstOrNull() in SHARED_STORAGE

    /**
     * Android/data, Android/media or Android/obb of [packageName]: the one part of shared
     * storage the app reads and writes with no grant at all, on every volume and Android version.
     */
    fun isAppOwnedExternal(dir: File, packageName: String): Boolean {
        val seg = segments(dir)
        val i = androidAreaIndex(seg)
        return i >= 1 && seg.getOrNull(i + 1) in APP_AREAS && seg.getOrNull(i + 2) == packageName
    }
}

/**
 * [FileSource] over java.io.File and the platform's storage APIs, per Android version:
 *  - primary storage and Downloads: Environment.getExternalStorageDirectory and
 *    getExternalStoragePublicDirectory, every level (deprecated in 29, still the shared storage
 *    mount, and raw paths are the premise of this browser);
 *  - other volumes, Android 11+ (API 30): StorageVolume.getDirectory;
 *  - other volumes, Android 7–10 (API 24–29): StorageManager.getStorageVolumes (uuid, state,
 *    removable, description) mapped to "/storage/<uuid>", the mount point of those releases —
 *    the hidden StorageVolume.getPath is not called — and Context.getExternalFilesDirs (API 19),
 *    cut at "/Android/data/", for a volume that came without a uuid.
 */
class RealFileSource(context: Context) : FileSource {
    private val context = context.applicationContext

    @Suppress("DEPRECATION")
    override fun roots(start: File?): List<BrowseRoot> {
        val volumes = runCatching {
            context.getSystemService(StorageManager::class.java).storageVolumes.map(::volumeInfo)
        }.getOrDefault(emptyList())
        val appDirs = runCatching { context.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList())
        return FileBrowse.assembleRoots(
            primary = Environment.getExternalStorageDirectory(),
            downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            volumes = volumes,
            appDirs = appDirs,
            start = start,
            isDirectory = { it.isDirectory },
        )
    }

    private fun volumeInfo(v: StorageVolume): VolumeInfo {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory else v.uuid?.let { File("/storage/$it") }
        val mounted = v.state == Environment.MEDIA_MOUNTED || v.state == Environment.MEDIA_MOUNTED_READ_ONLY
        return VolumeInfo(dir, v.isPrimary, v.isRemovable, mounted, v.getDescription(context))
    }

    override fun list(dir: File, showHidden: Boolean): Listing {
        val limited = needsAllFilesAccess && FileBrowse.isSharedStoragePath(dir) &&
            !FileBrowse.isAppOwnedExternal(dir, context.packageName)
        return FileBrowse.listDirectory(dir, showHidden, limited)
    }

    override fun describe(file: File): FileEntry? = if (file.exists()) FileBrowse.entryOf(file) else null

    override val access: StorageAccess get() = FileBrowse.storageAccess(context)

    override fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
}
