package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The app's language for words shown outside an activity: notifications, a toast from a
 * worker thread, the reasons the documents provider hands to another app, an error a send
 * writes into the history.
 *
 * An activity gets its language from wrapContextWithLocale, in attachBaseContext. Nothing
 * else has that hook: a service, a receiver, a provider and the application context all
 * resolve strings in the system's language — and the platform's per-app locale cannot be
 * relied on to fix that, since HyperOS accepts it and stores nothing. So a notification
 * posted from the service would come out in another language than the screen it opens.
 *
 * Why not wrapContextWithLocale here: it is the activity's hook and acts like one — it moves
 * the process's default Locale (Locale.setDefault), asks LocaleManager over binder to store
 * the choice, and applies the interface scale. None of that is a notification's business,
 * and Locale.setDefault from a binder or worker thread races with every screen that formats
 * a date. Only the configuration half is done here: a configuration context of the
 * application context with the language set, built once per language and kept.
 *
 * The language is `app_locale` as stored. A language picked in Android's own settings is
 * adopted into it when an activity starts (AppLocale.current); until then the platform's
 * choice is what the application context carries anyway when the stored value is "sys".
 *
 * Safe from any thread: a preference read and, on a change of language, one
 * createConfigurationContext.
 */
object AppStrings {
    /** The language the context below was made for, and the context. */
    @Volatile
    private var cache: Pair<String, Context>? = null

    /** A context whose resources are in the app's language; the application context itself when that is the system's. */
    fun of(context: Context): Context {
        val app = context.applicationContext ?: context
        val lang = GlobalSettings.getString(app, "app_locale", AppLanguages.SYSTEM)
        if (lang == AppLanguages.SYSTEM) return app
        cache?.let { (cachedLang, ctx) -> if (cachedLang == lang) return ctx }
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale.forLanguageTag(lang))
        return app.createConfigurationContext(config).also { cache = lang to it }
    }

    /** [of]'s resources. */
    fun resources(context: Context): Resources = of(context).resources
}
