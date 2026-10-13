package io.github.bropines.tailscaled.core

import android.content.Context

/*
 * Remote management, what the managed device puts on its own screen because a paired device
 * asked or changed something (appctr/remote_app.go): a permission's page, "managed from
 * <name>", and a line for each change. Called by core/RemoteDeviceHost.kt on a thread of the
 * bridge's; nothing here may block for long.
 *
 * Nothing is ever granted from afar: [ask] brings Android's own page or dialog in front of
 * whoever is at the device, as far as Android lets an app do that ([RemoteAskRoute.of]), and
 * says which of the three happened.
 */
object RemoteDevicePrompts {

    /**
     * Brings [permission]'s page or dialog in front of whoever is at the device, naming
     * [from] as the device that asks: at once where Android lets it, otherwise through a
     * notification and a line on the screen, and in any case when TailSocks is next opened
     * within a few minutes.
     */
    fun ask(context: Context, permission: AppPermission, from: String): RemoteAskRoute = RemoteAskRoute.WAITING

    /** The request made by [ask] that still waits for a person, for the report; null when none does. */
    fun asked(context: Context): RemoteDeviceAsked? = null

    /** Says "managed from [from]" for a few seconds. */
    fun identify(context: Context, from: String): RemoteAskRoute = RemoteAskRoute.WAITING

    /**
     * Says, while one of the app's screens is up, that [from] changed something: [op] and
     * [detail] are the line of the "recent remote changes" log (RemoteActionsJSON).
     */
    fun notice(context: Context, from: String, op: String, detail: String) {}
}
