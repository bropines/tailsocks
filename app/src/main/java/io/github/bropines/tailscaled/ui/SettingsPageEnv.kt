package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.bropines.tailscaled.core.Availability
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.TailscaleAccount
import io.github.bropines.tailscaled.core.TailscaledService
import kotlinx.coroutines.CoroutineScope

/**
 * What every page of Settings is handed by the screen around it ([SettingsScreen]): the
 * context and the screen's coroutine scope, the account that is open and its preferences,
 * where a feature is offered on this device ([place]), the way to another page ([open]),
 * and the two ways a row stores what it changed.
 *
 * Made anew on each composition of the screen, like the closures the pages used to be:
 * [place] and [open] read the screen's current state, never a copy of it.
 */
internal class SettingsPageEnv(
    val context: Context,
    val scope: CoroutineScope,
    val activeAccount: TailscaleAccount,
    val profilePrefs: SharedPreferences,
    private val placeOf: (Feature?) -> Availability,
    private val opener: (String) -> Unit,
) {
    /** Where [feature] is offered on this device; everything without one is offered. */
    fun place(feature: Feature?): Availability = placeOf(feature)

    /** Opens the page [section], as a row of the list of sections would. */
    fun open(section: String) = opener(section)

    fun saveGlobalPref(key: String, value: Any?) {
        when (value) {
            is String -> GlobalSettings.setString(context, key, value)
            is Boolean -> GlobalSettings.setBoolean(context, key, value)
        }
        context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
    }

    fun saveProfilePref(key: String, value: Any?, triggerService: Boolean = true) {
        val editor = profilePrefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }
        editor.apply()
        if (triggerService) {
            context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
        }
    }
}
