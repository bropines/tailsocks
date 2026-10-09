package io.github.bropines.tailscaled.admin

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.ui.theme.findActivity

/**
 * Pages of Tailscale's web console for what its API does not offer. They open in a Custom Tab:
 * the person's own browser with its session, which the app never sees.
 */
object ConsoleLinks {
    private const val BASE = "https://login.tailscale.com/admin"

    /**
     * A machine's page, addressed as the console links it: by the machine's first address.
     * Remote client updates live there ("Start update"); the API has no call for them.
     */
    fun machine(d: ApiDevice): String? = d.addresses.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "$BASE/machines/$it" }

    fun open(context: Context, url: String) {
        val activity = context.findActivity()
        try {
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (activity == null) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.launchUrl(activity ?: context, url.toUri())
        } catch (_: ActivityNotFoundException) {
            runCatching {
                (activity ?: context).startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply { if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
        }
    }
}
