package io.github.bropines.tailscaled.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import appctr.Appctr
import io.github.bropines.tailscaled.R

/**
 * The launcher icon the user picked (Settings → Appearance). Each icon is an
 * `<activity-alias>` of MainActivity named `.ui.Launcher_<id>`, with the
 * launcher filter and the launcher shortcuts; exactly one is enabled. The
 * manifest enables the default and disables the rest, so a new install shows
 * the default before the app has ever run, and the PackageManager state the
 * app writes stays the least that differs from the manifest:
 *  - the default alias is DEFAULT (on) when picked and DISABLED otherwise;
 *  - any other alias is ENABLED when picked and DEFAULT (off) otherwise.
 *
 * The preference ([PREF]) says which one is meant; [reconcile] makes
 * PackageManager agree with it at every start, after a restore, after an
 * update that drops an icon, after a crash between the two writes.
 */
object AppIcons {
    const val PREF = "app_icon"
    const val DEFAULT_ID = "fox_sock"

    /** Alias names resolve against the namespace, not the application id: the debug build's are the same. */
    private const val ALIAS_PREFIX = "io.github.bropines.tailscaled.ui.Launcher_"

    /** The picker's sections. */
    enum class Group(@StringRes val title: Int) {
        CLASSIC(R.string.icon_group_classic),
    }

    /** One icon: its alias's id, its name, and the two layers the picker draws it from. */
    class Variant(
        val id: String,
        @StringRes val title: Int,
        val group: Group,
        @DrawableRes val background: Int,
        @DrawableRes val foreground: Int,
    )

    /** In the picker's order. Each id has an alias in the manifest and an ic_launcher_<id> mipmap. */
    val ALL: List<Variant> = listOf(
        Variant("fox_sock", R.string.icon_fox_sock, Group.CLASSIC, R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    )

    val DEFAULT: Variant = ALL.first { it.id == DEFAULT_ID }

    /** The icon the preference names; the default when it names none, or one this build dropped. */
    fun current(context: Context): Variant {
        val id = GlobalSettings.getString(context, PREF, DEFAULT_ID)
        return ALL.firstOrNull { it.id == id } ?: DEFAULT
    }

    /** Remembers [variant] and switches the launcher to it. Binder calls: not on the main thread. */
    fun select(context: Context, variant: Variant) {
        GlobalSettings.setString(context, PREF, variant.id)
        apply(context, variant)
    }

    /** Makes PackageManager show the icon the preference names. Cheap when they agree: reads only. */
    fun reconcile(context: Context) = apply(context, current(context))

    private fun component(context: Context, v: Variant) = ComponentName(context.packageName, ALIAS_PREFIX + v.id)

    private fun wanted(v: Variant, picked: Variant): Int = when {
        v === picked -> if (v === DEFAULT) COMPONENT_ENABLED_STATE_DEFAULT else COMPONENT_ENABLED_STATE_ENABLED
        else -> if (v === DEFAULT) COMPONENT_ENABLED_STATE_DISABLED else COMPONENT_ENABLED_STATE_DEFAULT
    }

    @Synchronized
    private fun apply(context: Context, picked: Variant) {
        val pm = context.packageManager
        val changes = ALL.mapNotNull { v ->
            val cn = component(context, v)
            val want = wanted(v, picked)
            runCatching { pm.getComponentEnabledSetting(cn) }.getOrNull()?.takeIf { it != want }?.let { cn to want }
        }
        if (changes.isEmpty()) return
        // The new alias first: the package is never without a launcher entry,
        // which would make a launcher drop it from the home screen, and the
        // static shortcuts pass to the new alias under the same ids instead of
        // being removed, which would disable their pinned copies. DONT_KILL_APP:
        // the switch happens while the user looks at the picker.
        val ordered = changes.sortedBy { (cn, _) -> cn.className != ALIAS_PREFIX + picked.id }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // One call, one PACKAGE_CHANGED: the launcher redraws once.
                pm.setComponentEnabledSettings(ordered.map { (cn, state) ->
                    PackageManager.ComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
                })
            } else {
                for ((cn, state) in ordered) pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
            }
            log("INFO", "Launcher icon: ${picked.id}")
        }.onFailure { log("ERROR", "Launcher icon ${picked.id} not applied: ${it.message}") }
    }

    private fun log(level: String, message: String) = runCatching { Appctr.logAndroid(level, "CORE", message) }
}
