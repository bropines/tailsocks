package io.github.bropines.tailscaled.core

import android.os.Build
import java.io.File

/*
 * The small rules of sharing a folder that do not need a screen: what to call a share nobody
 * named, and what to type on a computer when the device has no page to give the access.
 */

/** The longest a suggested name is let to grow, so a long folder name stays a name. */
private const val NAME_MAX = 40

/** The internal storage's root, and the name the switch that shares all of it gives the share. */
internal const val FULL_STORAGE_PATH = "/storage/emulated/0"
internal const val FULL_STORAGE_NAME = "sdcard"

internal fun isFullStoragePath(path: String) = path == FULL_STORAGE_PATH || path == "$FULL_STORAGE_PATH/"

/** What a share is called: letters, digits and the underscore, the only characters Taildrive takes. */
private val SHARE_NAME_CHARACTERS = Regex("[^a-zA-Z0-9_]")

/**
 * A name for the share of [path], for a person who picked the folder instead of typing: the
 * folder's own name with what Taildrive does not take left out; "share" when nothing is left (a
 * folder named in Cyrillic); and a number after it while [taken] has it, whatever the case —
 * Taildrive treats `Music` and `music` as one. Typing a name is slow with a remote, so the
 * suggestion has to be one that can be accepted as it is.
 */
fun suggestShareName(path: String, taken: Collection<String>): String {
    // The whole storage is "0" to File, and "sdcard" to the switch that shares it.
    val base = if (isFullStoragePath(path)) FULL_STORAGE_NAME
    else File(path).name.replace(SHARE_NAME_CHARACTERS, "").take(NAME_MAX).ifEmpty { "share" }
    val used = taken.mapTo(HashSet()) { it.lowercase() }
    if (base.lowercase() !in used) return base
    var n = 2
    while ("${base}_$n".lowercase() in used) n++
    return "${base}_$n"
}

/**
 * What to run on a computer to give the app the access to files that sharing folders by path
 * needs, for a device whose Settings has no page for it (a television often has none). From
 * Android 11 it is All files access; before that, the Storage permission.
 */
fun storageGrantCommand(sdk: Int, packageName: String): String =
    if (sdk >= Build.VERSION_CODES.R) {
        FileBrowse.adbGrantCommand(packageName)
    } else {
        "adb shell pm grant $packageName android.permission.READ_EXTERNAL_STORAGE && " +
            "adb shell pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE"
    }
