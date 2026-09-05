package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Home of the files other apps are allowed to see.
 *
 * Received Taildrop files used to live in `files/states/<account>/taildrop`,
 * side by side with `tailscaled.state` (the node private keys), the LocalAPI
 * socket and the profile databases. Handing one of those files to a viewer app
 * therefore required a FileProvider root covering the whole `files/` directory,
 * so any component that could obtain a `content://` URI from this app could ask
 * for the keys just as easily.
 *
 * They now live in `files/taildrop/<account>`, a tree that holds nothing but
 * user data, and `res/xml/file_paths.xml` grants the FileProvider access to
 * that tree alone.
 *
 * [migrate] moves what the old location still holds. It is called from every
 * entry point that resolves the directory (the service on start, the Files
 * screen, the SAF provider), because in Root Mode a daemon started by the boot
 * script keeps writing to the path it was given at boot until the app hands it
 * the new one.
 */
object TaildropPaths {
    private const val TAG = "TaildropPaths"

    /**
     * Account ids reach this object from SAF document ids, so they are checked
     * before they become a path component.
     */
    fun isSafeAccountId(accountId: String): Boolean =
        accountId.isNotEmpty() && !accountId.contains('/') && !accountId.contains("..")

    /** Current location of the received files of one account. */
    fun dir(context: Context, accountId: String): File =
        File(context.filesDir, "taildrop/$accountId")

    /** Pre-4.0 location, kept only so [migrate] can empty it. */
    fun legacyDir(context: Context, accountId: String): File =
        File(context.filesDir, "states/$accountId/taildrop")

    /** [dir], migrated and created. */
    fun ensureDir(context: Context, accountId: String): File {
        migrate(context, accountId)
        val target = dir(context, accountId)
        if (!target.exists()) target.mkdirs()
        return target
    }

    /**
     * Moves files left in the pre-4.0 location into the new one. A no-op (one
     * stat) once the old directory is gone.
     */
    fun migrate(context: Context, accountId: String) {
        if (!isSafeAccountId(accountId)) return
        val legacy = legacyDir(context, accountId)
        if (!legacy.isDirectory) return

        val target = dir(context, accountId)
        if (!target.exists() && !target.mkdirs()) {
            Log.w(TAG, "Cannot create $target, leaving received files where they are")
            return
        }

        legacy.listFiles()?.forEach { file ->
            // Dotfiles are Taildrop bookkeeping and a partial is a transfer the
            // daemon still holds open; renaming either would strand it.
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.startsWith(".") || name.endsWith(".partial") || name.endsWith(".part")) return@forEach

            val dest = File(target, name)
            if (dest.exists()) return@forEach
            // Same filesystem and both parents belong to the app uid, so this is
            // a rename even for a file the root daemon wrote. Copying is
            // deliberately not attempted: received files can be gigabytes.
            if (!file.renameTo(dest)) {
                Log.w(TAG, "Could not move $name out of the legacy Taildrop directory")
            }
        }

        // Succeeds only when everything was moved, so nothing is ever dropped.
        legacy.delete()
    }
}
