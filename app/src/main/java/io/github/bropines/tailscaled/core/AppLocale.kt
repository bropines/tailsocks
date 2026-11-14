package io.github.bropines.tailscaled.core

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi

/**
 * The app's language: `app_locale` in GlobalSettings ("sys" or a tag of [AppLanguages]),
 * kept in agreement with Android 13+'s per-app language, which the system's own settings
 * page changes (the app is listed there through `res/xml/locales_config.xml`).
 *
 * Talks to LocaleManager itself. AppCompatDelegate's per-app locale calls reach the
 * platform only through a live AppCompatActivity, and every screen here is a
 * ComponentActivity: those calls stored nothing on Android 13+.
 *
 * The preference stays the source the app renders from (wrapContextWithLocale), on every
 * version: a ROM may accept the platform call and store nothing (HyperOS), and before
 * Android 13 there is no platform setting at all.
 */
object AppLocale {
    private const val KEY = "app_locale"

    /** Set once the platform has been seen holding a language: an empty list after that is a reset in its settings. */
    private const val PLATFORM_HELD = "app_locale_platform_held"

    /**
     * `app_locale`, after taking in what the system's settings changed since: a language
     * picked there is adopted, and so is "System default" once the platform had held one.
     * An empty platform list that never held anything is a ROM that does not store it, and
     * changes nothing.
     */
    fun current(context: Context): String {
        val stored = GlobalSettings.getString(context, KEY, AppLanguages.SYSTEM)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return stored
        val held = platformTags(context) ?: return stored
        val first = held.substringBefore(',')
        val adopted = when {
            first.isNotEmpty() -> {
                GlobalSettings.setBoolean(context, PLATFORM_HELD, true)
                AppLanguages.fromPlatform(first)
            }
            stored != AppLanguages.SYSTEM && GlobalSettings.getBoolean(context, PLATFORM_HELD, false) -> {
                GlobalSettings.setBoolean(context, PLATFORM_HELD, false)
                AppLanguages.SYSTEM
            }
            else -> stored
        }
        if (adopted != stored) GlobalSettings.setString(context, KEY, adopted)
        return adopted
    }

    /** Stores [tag] (or "sys") as the app's language and hands it to the platform. The caller recreates the screen. */
    fun set(context: Context, tag: String) {
        GlobalSettings.setString(context, KEY, tag)
        if (tag == AppLanguages.SYSTEM) GlobalSettings.setBoolean(context, PLATFORM_HELD, false)
        toPlatform(context, tag)
    }

    /** Hands [tag] to the platform where it differs from what that holds; nothing before Android 13. */
    fun toPlatform(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val wanted = if (tag == AppLanguages.SYSTEM) "" else tag
        val held = platformTags(context) ?: return
        if (held.substringBefore(',').let { it == wanted || (it.isNotEmpty() && AppLanguages.fromPlatform(it) == wanted) }) return
        runCatching { setPlatformTags(context, wanted) }
            .onFailure { android.util.Log.w("AppLocale", "Could not set the per-app locale: ${it.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun platformTags(context: Context): String? = runCatching {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags()
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setPlatformTags(context: Context, tags: String) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags)
    }
}
