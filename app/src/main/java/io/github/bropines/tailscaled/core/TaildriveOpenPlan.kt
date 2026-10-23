package io.github.bropines.tailscaled.core

/*
 * Which road a share's file takes to another app, and whether anything on this device is a
 * real taker of it. Pure: what resolves is handed in, so the decision has tests; the part that asks
 * the system and starts the app is in TaildriveBrowse.kt (DriveOpenSpec.launch).
 *
 * Two roads lead to a player:
 *
 * - CONTENT: the file as a `content://` document of the app's own DocumentsProvider
 *   (core/TailsocksFileProvider.kt). Players take it and browsers do not, and on Android 8 and up
 *   the provider hands out a descriptor that can seek (StorageManager.openProxyFileDescriptor), so a
 *   player can jump about in a large video. Before Android 8 the provider can only give a pipe: it
 *   plays from the start and cannot seek.
 * - LINK: `http://127.0.0.1:<port>/…` on the local Taildrive proxy (Settings, Taildrive). A player
 *   reads it with byte ranges of its own, so it seeks at any Android version — and a browser claims
 *   it too, which is why nothing here ever hands a media file to one without asking.
 */

/** The two roads, see above. */
enum class OpenRoad { CONTENT, LINK }

/** An installed activity that took an intent. */
data class AppRef(val pkg: String, val cls: String)

/**
 * Who answered an intent, as the system lists it, without the television's placeholders (the stub
 * that shows a toast and finishes is nobody): [players] are the apps that do something with the
 * file, [browsers] those that also answer a plain link to a web page.
 */
data class Takers(val players: List<AppRef> = emptyList(), val browsers: List<AppRef> = emptyList()) {
    val any: Boolean get() = players.isNotEmpty() || browsers.isNotEmpty()
}

/** What resolved for [type] on [road]; one entry per road and type asked, in the order of asking. */
data class RoadResolution(val road: OpenRoad, val type: String, val takers: Takers)

/** What to tell a person for whom no app took a file. */
enum class NoAppAdvice {
    /** A film, a song or a picture: a media player is what is missing, and one can be installed. */
    INSTALL_PLAYER,

    /** Anything else: nothing on this device opens this kind of file, and no player would. */
    NOTHING_OPENS,
}

/** What to do with a file once the roads have been asked. */
sealed interface OpenPlan {
    /**
     * Start [road]'s intent with MIME [type]: at the one app of [apps], or — several — through a
     * chooser that offers those and nothing else.
     */
    data class Start(val road: OpenRoad, val type: String, val apps: List<AppRef>) : OpenPlan

    /** Nothing here plays this kind of file from a link; only [browsers] take it. Ask before opening one. */
    data class OnlyBrowser(val type: String, val browsers: List<AppRef>) : OpenPlan

    /** No app takes it at all. */
    data object NoApp : OpenPlan
}

object TaildriveOpenPlan {

    /** What to say when no app takes a file of [kind]: a player is offered for media only. */
    fun noAppAdvice(kind: DriveKind): NoAppAdvice = if (isMedia(kind)) NoAppAdvice.INSTALL_PLAYER else NoAppAdvice.NOTHING_OPENS

    /** What a browser is not a taker of: a media file is played, and a browser only shows it. */
    fun isMedia(kind: DriveKind): Boolean = kind == DriveKind.VIDEO || kind == DriveKind.AUDIO || kind == DriveKind.IMAGE

    /**
     * The roads in the order to try them. The document road first wherever it seeks — Android 8
     * and up, with seeking switched on ([contentSeeks], TaildriveSeek) — and for every file that is
     * not media, where a pipe is as good as a descriptor. Where it cannot seek a media file goes by
     * the link first — its player seeks with ranges of its own — and the document road is the way
     * out. [preferLink] is the person's own switch for the link.
     */
    fun roadOrder(api: Int, media: Boolean, preferLink: Boolean = false, contentSeeks: Boolean = api >= SEEKABLE_API): List<OpenRoad> =
        if (preferLink || (media && !contentSeeks)) listOf(OpenRoad.LINK, OpenRoad.CONTENT)
        else listOf(OpenRoad.CONTENT, OpenRoad.LINK)

    /** The first Android that can give another app a descriptor to seek in (`openProxyFileDescriptor`). */
    const val SEEKABLE_API = 26

    /**
     * What to do for a file of [media] kind at Android [api], given what [resolutions] say
     * resolved. The first road, in [roadOrder], with a real player starts. A file that is not media
     * goes to anything that takes it, a browser included — a PDF or a page is what a browser shows.
     * A media file that only a browser takes is not opened: [OpenPlan.OnlyBrowser], which the page
     * puts to the person; and nothing at all is [OpenPlan.NoApp].
     */
    fun plan(
        api: Int,
        media: Boolean,
        resolutions: List<RoadResolution>,
        preferLink: Boolean = false,
        contentSeeks: Boolean = api >= SEEKABLE_API,
    ): OpenPlan {
        val order = roadOrder(api, media, preferLink, contentSeeks)
        val ordered = order.flatMap { road -> resolutions.filter { it.road == road } }
        ordered.firstOrNull { it.takers.players.isNotEmpty() }?.let {
            return OpenPlan.Start(it.road, it.type, it.takers.players)
        }
        if (!media) {
            ordered.firstOrNull { it.takers.any }?.let { return OpenPlan.Start(it.road, it.type, it.takers.browsers) }
        } else {
            // Only the link is a thing a browser could be asked to open: it cannot read a document of ours.
            ordered.firstOrNull { it.road == OpenRoad.LINK && it.takers.browsers.isNotEmpty() }
                ?.let { return OpenPlan.OnlyBrowser(it.type, it.takers.browsers) }
        }
        return OpenPlan.NoApp
    }

    /**
     * What an activity that answered an intent is: nothing (a television's placeholder), a
     * browser (its package also answers a plain link to a web page), or a player.
     */
    fun classify(app: AppRef, browserPackages: Set<String>): Kind =
        when {
            isPlaceholderActivity(app.pkg, app.cls) -> Kind.PLACEHOLDER
            app.pkg in browserPackages -> Kind.BROWSER
            else -> Kind.PLAYER
        }

    enum class Kind { PLAYER, BROWSER, PLACEHOLDER }

    /** [answered] sorted into [Takers], placeholders dropped. */
    fun takers(answered: List<AppRef>, browserPackages: Set<String>): Takers {
        val kinds = answered.distinct().groupBy { classify(it, browserPackages) }
        return Takers(kinds[Kind.PLAYER].orEmpty(), kinds[Kind.BROWSER].orEmpty())
    }
}
