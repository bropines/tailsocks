package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import java.io.File

/*
 * Remote management, the two switches of the managed device's page beyond the main one
 * (appctr/remote_level.go): full control, and guests let in or not. Like the main switch
 * (RemoteManage) each is a file under files/remote/ — out of every backup, so a restore
 * cannot turn full control on — and each reaches the bridge with the options the service
 * builds (StartOptions.remoteFullControl, remoteNoGuests).
 *
 * Full control is switched on here and nowhere else. A paired owner may switch it off
 * (the remote setting full_control, off only), which ends up in [setFullControl] as well.
 */
object RemoteLevel {
    private const val TAG = "RemoteLevel"
    private const val DIR = "remote"

    /** Their presence is the switch. Not `<profile>.json`, so no profile's pairings can be taken for one. */
    private const val FULL_FILE = "full.on"
    private const val NO_GUESTS_FILE = "guests.off"

    private fun file(context: Context, name: String) = File(File(context.filesDir, DIR), name)

    /** Whether a paired owner may do what shows more of this device or what is on it. Never without the main switch. */
    fun isFullControl(context: Context): Boolean = RemoteManage.isEnabled(context) && file(context, FULL_FILE).isFile

    /** Switches full control and tells a running service; false when it could not be written. */
    fun setFullControl(context: Context, on: Boolean): Boolean = write(context, FULL_FILE, on)

    /** Whether devices that cannot be owners — another user's, tagged, shared in — may pair and manage as guests. */
    fun guestsAllowed(context: Context): Boolean = !file(context, NO_GUESTS_FILE).isFile

    fun setGuestsAllowed(context: Context, allowed: Boolean): Boolean = write(context, NO_GUESTS_FILE, !allowed)

    private fun write(context: Context, name: String, present: Boolean): Boolean {
        val file = file(context, name)
        val done = try {
            if (present) {
                file.parentFile?.mkdirs()
                file.createNewFile() || file.isFile
            } else {
                !file.exists() || file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "A switch could not be written: ${e.message}")
            false
        }
        if (done && ProxyState.isActualRunning(context)) TailscaledService.requestApplySettings(context)
        return done
    }
}

/** One setting in [RemoteAccess]: which way it may be written from afar. */
@Serializable
data class RemoteAccessSetting(
    val key: String = "",
    /** The one value that may be written from afar, "true" or "false"; empty for either. */
    val only: String = "",
    /** "full" when the other value takes full control. */
    val needs: String = "",
    /** "optional" or "always": the change is put back unless confirmed. */
    val guard: String = "",
)

/** What one class of paired device may do. */
@Serializable
data class RemoteAccessClass(
    /** Operations, by the bridge's names (appctr/remote_policy.go, remoteOps). */
    val ops: List<String> = emptyList(),
    val settings: List<RemoteAccessSetting> = emptyList(),
    val actions: List<String> = emptyList(),
    /** The permissions it may ask to be shown at the device. */
    val permissions: List<String> = emptyList(),
)

/**
 * RemoteAccessJSON: what a paired device may do on this device right now, from the lists the
 * endpoint itself enforces. The managed device's page draws its "what paired devices may do"
 * from this and from nothing else, so the page cannot say more or less than is done.
 * [guest] and [owner] are at the current [level]; [full] is what switching full control on
 * would add for an owner (empty while it is on); [never] what no level and no class reaches.
 */
@Serializable
data class RemoteAccess(
    val level: String = LEVEL_BASIC,
    val guests: Boolean = true,
    val guest: RemoteAccessClass = RemoteAccessClass(),
    val owner: RemoteAccessClass = RemoteAccessClass(),
    val full: RemoteAccessClass = RemoteAccessClass(),
    val never: List<String> = emptyList(),
) {
    companion object {
        const val LEVEL_BASIC = "basic"
        const val LEVEL_FULL = "full"

        /** The bridge's document, or the empty one when it is not what it should be. */
        fun of(json: String): RemoteAccess = RemoteJson.decode<RemoteAccess>(json) ?: RemoteAccess()
    }
}
