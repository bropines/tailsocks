package io.github.bropines.tailscaled.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Where received Taildrop files are moved on arrival — the "Taildrop folder" of Settings
 * and of the inbox.
 *
 * Two kinds. [Tree] is a folder of the Storage Access Framework, granted once through the
 * system's picker: what a phone or a tablet has always had. [Dir] is a directory by its
 * path, for a device whose system has no picker to grant one with — a television, mostly.
 * It is chosen in the app's own browser (ui/FileBrowser.kt) and written with java.io.File.
 *
 * Plain values, a string and a file, so the rules of [TaildropFolder] have tests.
 */
sealed interface ReceiveFolder {
    /** A SAF tree, as the picker returned it. */
    data class Tree(val uri: String) : ReceiveFolder {
        val treeUri: Uri get() = Uri.parse(uri)
    }

    /** A directory. [isDefault]: nobody chose it, it is where a television starts ([TaildropFolder.defaultDir]). */
    data class Dir(val dir: File, val isDefault: Boolean = false) : ReceiveFolder
}

/**
 * The receive folder as it is stored and read. For another screen that shows or changes it
 * (Settings): [get], [set], [label] / [quickLabel] and [state] are the whole API; choosing
 * one with whatever picker the device has is `rememberReceiveFolderChooser` in
 * ui/TaildropPickers.kt.
 *
 * What an install that has a folder keeps, and what a new one starts with ([resolve]):
 *  1. a SAF folder that is stored stays the folder, on every kind of device — nothing here
 *     rewrites or drops `taildrop_root_uri`, so a phone that chose one before this existed
 *     goes on saving there;
 *  2. otherwise a stored path;
 *  3. otherwise, on a television, [defaultDir]; on a phone or a tablet nothing — received
 *     files wait in the inbox, as they always have.
 * Choosing a folder of one kind forgets the other ([set]), so the user's last choice is the
 * one that is stored. Neither key is exported with the settings: both name a place on this
 * device.
 */
object TaildropFolder {
    /** The path of a chosen [ReceiveFolder.Dir]; beside GlobalSettings' `taildrop_root_uri`, which holds a [ReceiveFolder.Tree]. */
    private const val KEY_PATH = "taildrop_folder_path"

    /** What a television's default is called under Downloads. */
    private const val DEFAULT_NAME = "Taildrop"

    /** Whether files can be saved into a folder right now; see [state]. */
    enum class State {
        READY,

        /** The storage access is missing ([StorageAccess]); granting it is what helps. */
        NEEDS_ACCESS,

        /** It cannot be written whatever the app is granted: a drive pulled out, a SAF grant revoked. Choose again. */
        UNAVAILABLE,
    }

    /** The rule of the three cases above; pure. An empty string is "not stored". */
    fun resolve(treeUri: String?, path: String?, device: DeviceClass, defaultDir: File?): ReceiveFolder? = when {
        !treeUri.isNullOrEmpty() -> ReceiveFolder.Tree(treeUri)
        !path.isNullOrEmpty() -> ReceiveFolder.Dir(FileBrowse.clean(File(path)))
        device == DeviceClass.TV && defaultDir != null -> ReceiveFolder.Dir(FileBrowse.clean(defaultDir), isDefault = true)
        else -> null
    }

    /** The folder received files go to on this device, or null: they wait in the inbox. */
    fun get(context: Context): ReceiveFolder? {
        val tree = GlobalSettings.getTaildropRootUri(context)?.toString()
        val path = GlobalSettings.getString(context, KEY_PATH, "")
        // Only an install with nothing stored is asked what kind of device it is, and only a
        // television where its default would be: a phone has none, and the preview renderer,
        // which Settings now reaches this through, has no storage to be asked about.
        val nothing = tree.isNullOrEmpty() && path.isEmpty()
        val device = if (nothing) DeviceProfile.deviceClass(context) else DeviceClass.HANDHELD
        return resolve(tree, path, device, if (device == DeviceClass.TV) defaultDir() else null)
    }

    /**
     * Makes [folder] the receive folder. A SAF tree's grant is persisted here; the grant on
     * a tree that is being replaced is kept, because files saved there earlier are opened
     * through it.
     */
    fun set(context: Context, folder: ReceiveFolder) {
        when (folder) {
            is ReceiveFolder.Tree -> {
                GlobalSettings.setString(context, KEY_PATH, "")
                GlobalSettings.setTaildropRootUri(context, folder.treeUri)
            }
            is ReceiveFolder.Dir -> {
                GlobalSettings.setTaildropRootUri(context, null)
                GlobalSettings.setString(context, KEY_PATH, FileBrowse.clean(folder.dir).path)
            }
        }
    }

    /**
     * Where a television saves until told otherwise: "Taildrop" in the public Downloads
     * folder. Downloads because every player and file manager on a television starts
     * there, and because from Android 11 an app may create its own files under it with no
     * grant at all, so receiving works before anybody has opened a settings page; a folder
     * of its own in it because without that grant the app lists only its own files, and
     * there every file is its own — a free name is then really free.
     */
    @Suppress("DEPRECATION")
    fun defaultDir(): File? =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, DEFAULT_NAME) }

    /** [label] without asking anybody; null for a SAF folder only its provider can name. */
    fun quickLabel(folder: ReceiveFolder): String? = when (folder) {
        is ReceiveFolder.Tree -> TaildropSave.quickFolderLabel(folder.treeUri)
        is ReceiveFolder.Dir -> pathLabel(folder.dir)
    }

    /** The folder as the user knows it: "Download/Taildrop". May ask a documents provider — not on the main thread. */
    fun label(context: Context, folder: ReceiveFolder): String = when (folder) {
        is ReceiveFolder.Tree -> TaildropSave.folderLabel(context, folder.treeUri)
        is ReceiveFolder.Dir -> pathLabel(folder.dir)
    }

    /**
     * A directory named the way the SAF folders are: by its path under the storage it is
     * on — "Download/Taildrop" for "/storage/emulated/0/Download/Taildrop" and for
     * "/storage/1234-ABCD/Download/Taildrop" alike. The top of a storage, and a path that
     * is on none, keep the whole path. Pure.
     */
    fun pathLabel(dir: File): String {
        val seg = FileBrowse.segments(dir)
        val under = when {
            seg.size > 3 && seg[0] == "storage" && seg[1] == "emulated" -> seg.drop(3)
            seg.size > 2 && seg[0] == "storage" && seg[1] != "emulated" -> seg.drop(2)
            seg.size > 1 && seg[0] == "sdcard" -> seg.drop(1)
            else -> return FileBrowse.clean(dir).path
        }
        return under.joinToString("/")
    }

    /**
     * Whether a received file can be saved into [folder] now. For a directory this is
     * tried, not asked: a missing one is made and a file is created in it and removed —
     * under scoped storage `canWrite()` answers for the mount, not for this app. Blocking.
     */
    fun state(context: Context, folder: ReceiveFolder): State = when (folder) {
        is ReceiveFolder.Tree -> if (TaildropSave.holdsGrant(context, folder.treeUri)) State.READY else State.UNAVAILABLE
        is ReceiveFolder.Dir -> stateOf(canCreateIn(folder.dir), FileBrowse.storageAccess(context))
    }

    /** A directory's [State] from whether a file could be created in it and what the app is granted; pure. */
    fun stateOf(writable: Boolean, access: StorageAccess): State = when {
        writable -> State.READY
        access != StorageAccess.GRANTED -> State.NEEDS_ACCESS
        else -> State.UNAVAILABLE
    }

    /** Whether a file can be created in [dir], which is made first when it is missing. */
    fun canCreateIn(dir: File): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val probe = File.createTempFile(".taildrop", null, dir)
        probe.delete()
        true
    }.getOrDefault(false)
}
