package io.github.bropines.tailscaled.admin.policy.templates

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.admin.api.HttpRequest
import io.github.bropines.tailscaled.admin.api.HttpTransport
import io.github.bropines.tailscaled.admin.api.TransportException
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv
import java.io.File

/** The template sources of this app: the APK's assets, the cache in its files, the published set through the console's connection. */
object TemplateSources {

    /** The directory of the bundled set in the assets, and of the cache in the app's files. */
    const val DIR = "policy-templates"

    private const val TAG = "PolicyTemplates"

    val app = AppIdentity(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    private fun log(line: String) {
        Log.w(TAG, line)
    }

    fun assets(context: Context) = TemplateFiles { name ->
        runCatching { context.assets.open("$DIR/$name").use { it.readBytes() } }.getOrNull()
    }

    /**
     * The console's source. [transport] is the admin profile's connection at the time of a
     * fetch — its proxy, as every request of the console — or null when there is no profile.
     */
    fun forApp(context: Context, transport: () -> HttpTransport?): TemplateSource {
        val app = context.applicationContext
        val fetch = TemplateFetch { url ->
            val t = transport() ?: throw TransportException("no admin profile")
            t.execute(HttpRequest("GET", url, mapOf("Accept" to "application/json")))
        }
        return TemplateSource(assets(app), File(app.filesDir, DIR), fetch, TemplateSources.app, log = ::log)
    }

    @Volatile
    private var bundled: TemplateCatalog? = null

    /** The bundled set alone, read once a process: what the cards show before the console has read the cache. */
    fun bundled(context: Context): TemplateCatalog =
        bundled ?: TemplateSource(assets(context), null, null, app, log = ::log).local().also { bundled = it }
}

/** The templates a page offers: the console's catalog, or the bundled set until it has one. */
@Composable
fun rememberTemplates(env: VisualEnv): TemplateCatalog {
    val ctx = LocalContext.current
    return env.templates ?: remember { TemplateSources.bundled(ctx) }
}
