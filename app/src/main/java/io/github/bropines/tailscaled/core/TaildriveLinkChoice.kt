package io.github.bropines.tailscaled.core

/*
 * The share browser's "Open files through a link" switch against the proxy the link needs. Pure, so
 * the decision has tests; the page reads the proxy and the API level and does what this says.
 *
 * The link road exists only while the local Taildrive proxy answers (TaildriveOpenPlan). A switch
 * left on while the proxy is off — turned off in Settings later, or still starting — must not stop
 * files from opening: the document road needs no proxy, so the file goes that way and the person
 * is told why the link was not used.
 */

/** The local Taildrive proxy, as the link road sees it. */
enum class ProxyCondition {
    /** Switched off in Settings → Taildrive. */
    OFF,

    /** On, and nothing answers on its address: starting, or its port is taken. */
    SILENT,

    READY,
}

/** What the page says after a file opened without the link it was asked to use. */
enum class LinkNotice {
    /** The proxy is off: the file opened directly; turning the proxy on is offered. */
    PROXY_OFF,

    /** The proxy is on and does not answer: the file opened directly. */
    PROXY_SILENT,

    /** [PROXY_OFF] for a media file before Android 8: it opened directly, and directly it cannot be seeked in. */
    PROXY_OFF_NO_SEEK,

    /** [PROXY_SILENT] for a media file before Android 8, as above. */
    PROXY_SILENT_NO_SEEK;

    /** Whether the notice offers to turn the proxy on: only one that is off can be. */
    val offersProxy: Boolean get() = this == PROXY_OFF || this == PROXY_OFF_NO_SEEK
}

/** How a file is opened: by the link first ([useLink]) or not, and what to say after it opened, if anything. */
data class LinkDecision(val useLink: Boolean, val notice: LinkNotice?)

object TaildriveLinkChoice {
    /**
     * For a file of [media] kind at Android [api], with the switch at [preferLink] and the proxy
     * in [proxy]: the link goes first only when it was asked for and the proxy answers. Asked for
     * and not there, the file goes directly, with a notice — one that adds, for media the document
     * road cannot seek in ([contentSeeks]: before Android 8, or with seeking switched off), that
     * seeking needs the link. Not asked for, nothing is said: the open plan's own
     * order stands (TaildriveOpenPlan.roadOrder).
     */
    fun decide(
        preferLink: Boolean,
        proxy: ProxyCondition,
        api: Int,
        media: Boolean,
        contentSeeks: Boolean = api >= TaildriveOpenPlan.SEEKABLE_API,
    ): LinkDecision {
        if (!preferLink) return LinkDecision(useLink = false, notice = null)
        if (proxy == ProxyCondition.READY) return LinkDecision(useLink = true, notice = null)
        val noSeek = media && !contentSeeks
        val notice = when (proxy) {
            ProxyCondition.OFF -> if (noSeek) LinkNotice.PROXY_OFF_NO_SEEK else LinkNotice.PROXY_OFF
            else -> if (noSeek) LinkNotice.PROXY_SILENT_NO_SEEK else LinkNotice.PROXY_SILENT
        }
        return LinkDecision(useLink = false, notice = notice)
    }

    /**
     * Turning the switch on asks first when the proxy is off — the link needs it — and is simply
     * done otherwise. Turning it off never asks.
     */
    fun asksToEnable(turningOn: Boolean, proxy: ProxyCondition): Boolean = turningOn && proxy == ProxyCondition.OFF
}
