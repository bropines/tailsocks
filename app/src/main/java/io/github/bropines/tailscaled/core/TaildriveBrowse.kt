package io.github.bropines.tailscaled.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.ui.theme.findActivity
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.io.encoding.Base64
import org.xmlpull.v1.XmlPullParserException

/*
 * Browsing the shares of the other devices (Taildrive), without Compose: what is on the
 * tailnet, what is inside a share, and how to hand a file to another app.
 *
 * Listing goes through TaildriveClient, as the DocumentsProvider does: PROPFIND over the
 * daemon's SOCKS5 port to 100.100.100.100:8080. Opening a file goes through the local
 * WebDAV proxy (Settings -> Taildrive), because an external player cannot reach the
 * daemon's SOCKS5 port, and 100.100.100.100:8080 means nothing in userspace mode.
 *
 * Nothing here logs an address: a file URL may carry the proxy's password.
 */

private const val TAG = "TaildriveBrowse"

/** The WebDAV server every Tailscale client answers on; the same paths as the local proxy. */
private const val TAILNET_BASE = "http://100.100.100.100:8080"

// ---------------------------------------------------------------------------------------------
// Where we are
// ---------------------------------------------------------------------------------------------

/** What a [DriveLocation] holds, by how deep it is. */
enum class DriveLevel {
    /** The root: Taildrive shows the tailnet's domain, one folder. */
    TAILNETS,

    /** Inside the tailnet: the devices that share anything with this one. */
    DEVICES,

    /** Inside a device: its shares. */
    SHARES,

    /** Inside a share: folders and files. */
    FOLDER;

    companion object {
        fun of(depth: Int): DriveLevel = when {
            depth <= 0 -> TAILNETS
            depth == 1 -> DEVICES
            depth == 2 -> SHARES
            else -> FOLDER
        }
    }
}

/**
 * A place in Taildrive's namespace, `/<tailnet>/<device>/<share>/<folder>/…`, as the names
 * between the slashes. The names are plain, never percent-encoded: [davPath] goes to
 * TaildriveClient, which escapes every segment itself, and [DriveProxy.urlFor] escapes them
 * for a URL.
 */
data class DriveLocation(val segments: List<String> = emptyList()) {
    val depth: Int get() = segments.size
    val isRoot: Boolean get() = segments.isEmpty()
    val level: DriveLevel get() = DriveLevel.of(depth)

    /** The last name; null at the root. */
    val name: String? get() = segments.lastOrNull()

    /** One level up; null at the root. */
    val parent: DriveLocation? get() = if (isRoot) null else DriveLocation(segments.dropLast(1))

    fun child(name: String): DriveLocation = DriveLocation(segments + name)

    /** The path TaildriveClient takes: slash-separated plain names, `/` for the root. */
    val davPath: String get() = "/" + segments.joinToString("/")

    companion object {
        val ROOT = DriveLocation()
    }
}

/**
 * [names] as one line for a path bar, `a / b / c`, short enough for [maxChars]: the names at
 * the front give way first, as `… / b / c`, because the last one says where you are. The last
 * name is always kept, however long it is.
 */
fun pathLine(names: List<String>, maxChars: Int, separator: String = " / "): String {
    if (names.isEmpty()) return ""
    val ellipsis = "…$separator"
    var kept = names.size
    fun width(from: Int) = names.drop(from).sumOf { it.length } + separator.length * (names.size - from - 1) +
        if (from > 0) ellipsis.length else 0
    while (kept > 1 && width(names.size - kept) > maxChars) kept--
    val from = names.size - kept
    return (if (from > 0) ellipsis else "") + names.drop(from).joinToString(separator)
}

// ---------------------------------------------------------------------------------------------
// What is there
// ---------------------------------------------------------------------------------------------

/** What a row is: the places of the namespace, then what a file's name says it is. */
enum class DriveKind(val isFolder: Boolean) {
    TAILNET(true), DEVICE(true), SHARE(true), FOLDER(true),
    VIDEO(false), AUDIO(false), IMAGE(false), SUBTITLES(false), TEXT(false), PDF(false), OTHER(false)
}

/** One row of a listing. [size] and [modified] (epoch milliseconds) are 0 when the server did not say. */
data class DriveEntry(
    val name: String,
    val kind: DriveKind,
    val size: Long = 0,
    val modified: Long = 0,
) {
    val isFolder: Boolean get() = kind.isFolder
}

/** A listing and the place it is the listing of — not always the one asked for, see [TaildriveBrowse.list]. */
data class DriveListing(val location: DriveLocation, val entries: List<DriveEntry>)

/** Folders first, then names in natural order. */
val DriveEntryOrder: Comparator<DriveEntry> = Comparator { a, b ->
    if (a.isFolder != b.isFolder) if (a.isFolder) -1 else 1 else NaturalOrder.compare(a.name, b.name)
}

/**
 * Names as a person orders them: case does not matter and a run of digits is a number, so
 * `Episode 2` comes before `Episode 10` and `S01E09` before `S01E10`. Names that differ only in
 * case or zero padding still get a fixed order, by their plain characters.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isAsciiDigit()) ie++
                var je = j
                while (je < b.length && b[je].isAsciiDigit()) je++
                val x = a.substring(i, ie).trimStart('0')
                val y = b.substring(j, je).trimStart('0')
                // Digits only, so a longer number is a bigger one, however long.
                if (x.length != y.length) return x.length.compareTo(y.length)
                val c = x.compareTo(y)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

// ---------------------------------------------------------------------------------------------
// What a file is
// ---------------------------------------------------------------------------------------------

/** A file's MIME type and the kind of row it gets. */
data class DriveFileType(val mime: String, val kind: DriveKind)

/**
 * A MIME type from the extension. The table is ours, not `MimeTypeMap`'s: the platform's differs
 * from one Android version to the next (`mkv`, `opus` and `m4v` were missing for years), and the
 * type decides which players are offered the file.
 */
object DriveMime {
    const val FALLBACK = "application/octet-stream"

    private val OTHER = DriveFileType(FALLBACK, DriveKind.OTHER)

    private val byExtension: Map<String, DriveFileType> = buildMap {
        fun add(kind: DriveKind, mime: String, vararg extensions: String) =
            extensions.forEach { put(it, DriveFileType(mime, kind)) }

        add(DriveKind.VIDEO, "video/mp4", "mp4")
        add(DriveKind.VIDEO, "video/x-m4v", "m4v")
        add(DriveKind.VIDEO, "video/x-matroska", "mkv")
        add(DriveKind.VIDEO, "video/webm", "webm")
        add(DriveKind.VIDEO, "video/x-msvideo", "avi")
        add(DriveKind.VIDEO, "video/quicktime", "mov")
        add(DriveKind.VIDEO, "video/x-ms-wmv", "wmv")
        add(DriveKind.VIDEO, "video/x-flv", "flv")
        add(DriveKind.VIDEO, "video/mpeg", "mpg", "mpeg", "vob")
        add(DriveKind.VIDEO, "video/mp2t", "ts", "m2ts", "mts")
        add(DriveKind.VIDEO, "video/3gpp", "3gp")
        add(DriveKind.VIDEO, "video/3gpp2", "3g2")
        add(DriveKind.VIDEO, "video/ogg", "ogv")
        add(DriveKind.VIDEO, "video/x-ms-asf", "asf")
        add(DriveKind.VIDEO, "video/divx", "divx")

        add(DriveKind.AUDIO, "audio/mpeg", "mp3")
        add(DriveKind.AUDIO, "audio/mp4", "m4a", "m4b")
        add(DriveKind.AUDIO, "audio/aac", "aac")
        add(DriveKind.AUDIO, "audio/flac", "flac")
        add(DriveKind.AUDIO, "audio/ogg", "ogg", "oga")
        add(DriveKind.AUDIO, "audio/opus", "opus")
        add(DriveKind.AUDIO, "audio/x-wav", "wav")
        add(DriveKind.AUDIO, "audio/x-ms-wma", "wma")
        add(DriveKind.AUDIO, "audio/x-aiff", "aif", "aiff")
        add(DriveKind.AUDIO, "audio/x-matroska", "mka")
        add(DriveKind.AUDIO, "audio/midi", "mid", "midi")

        add(DriveKind.IMAGE, "image/jpeg", "jpg", "jpeg")
        add(DriveKind.IMAGE, "image/png", "png")
        add(DriveKind.IMAGE, "image/gif", "gif")
        add(DriveKind.IMAGE, "image/webp", "webp")
        add(DriveKind.IMAGE, "image/bmp", "bmp")
        add(DriveKind.IMAGE, "image/heic", "heic")
        add(DriveKind.IMAGE, "image/heif", "heif")
        add(DriveKind.IMAGE, "image/avif", "avif")
        add(DriveKind.IMAGE, "image/tiff", "tif", "tiff")
        add(DriveKind.IMAGE, "image/svg+xml", "svg")

        add(DriveKind.SUBTITLES, "application/x-subrip", "srt")
        add(DriveKind.SUBTITLES, "text/vtt", "vtt")
        add(DriveKind.SUBTITLES, "text/x-ssa", "ass", "ssa")
        add(DriveKind.SUBTITLES, "text/x-microdvd", "sub")

        add(DriveKind.TEXT, "text/plain", "txt", "log", "nfo", "ini", "conf", "yaml", "yml")
        add(DriveKind.TEXT, "text/markdown", "md")
        add(DriveKind.TEXT, "text/csv", "csv")
        add(DriveKind.TEXT, "application/json", "json")
        add(DriveKind.TEXT, "text/xml", "xml")
        add(DriveKind.TEXT, "text/html", "html", "htm")

        add(DriveKind.PDF, "application/pdf", "pdf")

        add(DriveKind.OTHER, "application/zip", "zip")
        add(DriveKind.OTHER, "application/x-7z-compressed", "7z")
        add(DriveKind.OTHER, "application/vnd.rar", "rar")
        add(DriveKind.OTHER, "application/epub+zip", "epub")
        add(DriveKind.OTHER, "application/vnd.android.package-archive", "apk")
    }

    /** The type of a file called [name]; [FALLBACK] and [DriveKind.OTHER] for an extension we do not know. */
    fun of(name: String): DriveFileType {
        // A leading dot is a hidden file, not an extension: ".mp4" is a file named that.
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return OTHER
        return byExtension[name.substring(dot + 1).lowercase()] ?: OTHER
    }

    fun guess(name: String): String = of(name).mime

    /**
     * The wildcard type of [mime]'s family, "video/" and a star for `video/x-matroska`: a player that lists
     * only some of the video types still takes the file when it declares the family. Null for
     * the types that have no family worth asking for.
     */
    fun family(mime: String): String? {
        val major = mime.substringBefore('/', "")
        return if (major == "video" || major == "audio" || major == "image") "$major/*" else null
    }
}

// ---------------------------------------------------------------------------------------------
// When it does not work
// ---------------------------------------------------------------------------------------------

/**
 * Why a listing or an open did not happen, as the screen has to tell it. A closed set: the page
 * maps each to words and an action, nothing here carries a message for the user.
 */
sealed interface DriveError {
    /** The daemon is not running, so there is no SOCKS5 port to ask. */
    data object NotConnected : DriveError

    /** Settings -> Taildrive -> proxy is off; files cannot be handed to another app. */
    data object ProxyOff : DriveError

    /** The proxy is on but nothing answers on its address: starting up, or the port is taken. */
    data object ProxyNotListening : DriveError

    /**
     * The tailnet's policy does not give this device `drive:access`, so its daemon will not
     * answer for the other devices' folders at all (see [TaildriveAccess]).
     */
    data object NotPermitted : DriveError

    /**
     * Taildrive on this device closed the connection or answered nothing: before the network map
     * has arrived, or without `drive:access` when the app could not read the policy's verdict.
     * Not a device's doing — it is this device's own daemon that was asked.
     */
    data object NoAnswer : DriveError

    /** One device, past this device's own daemon, or its file server did not answer: offline, asleep, or the Tailscale app closed. */
    data object Unreachable : DriveError

    /** The access rules of the tailnet do not let this device into the share. */
    data object AccessDenied : DriveError

    /** The share or the folder is not there (any more). */
    data object NotFound : DriveError

    /** The answer took too long. */
    data object Timeout : DriveError

    /** Anything else; [detail] is one short line without an address. */
    data class Failed(val detail: String) : DriveError

    companion object {
        /** What TaildriveClient throws when the daemon's SOCKS5 port is not configured. */
        private const val NO_SOCKS_MESSAGE = "SOCKS5 proxy is not running"

        /**
         * How deep a place is where a request leaves this device's own daemon: `/<tailnet>/<device>`.
         * The root and the tailnet are answered by the daemon alone; from a device down, the
         * request goes to that device, and only then can "the device" be what failed.
         */
        const val DEVICE_DEPTH = 2

        /**
         * The status code of an answer, as the server's node reports an unavailable share. [depth]
         * is how deep the place asked about is: a 5xx at a device or below is the device's (or its file
         * server's), above it there is no device to blame.
         */
        fun fromHttp(code: Int, depth: Int = DEVICE_DEPTH): DriveError = when (code) {
            401, 403, 407 -> AccessDenied
            404, 410 -> NotFound
            408, 504 -> Timeout
            // 500 and 502 are what a node answers when its file server is not there: on Windows
            // and macOS the Tailscale app serves the files, and with it closed the share still
            // lists (see TailsocksFileProvider.unavailableReason).
            500, 502, 503 -> if (depth >= DEVICE_DEPTH) Unreachable else Failed("HTTP $code")
            else -> Failed("HTTP $code")
        }

        /** [t] as the screen has to tell it; [depth] as for [fromHttp]. */
        fun from(t: Throwable, depth: Int = DEVICE_DEPTH): DriveError = when (t) {
            is WebDavHttpException -> fromHttp(t.code, depth)
            is SocketTimeoutException -> Timeout
            // The only thing the client connects to itself is the daemon's SOCKS5 port.
            is ConnectException -> NotConnected
            is UnknownHostException, is NoRouteToHostException -> deviceOrDaemon(depth)
            // The SOCKS5 server's own refusal: "SOCKS: Host unreachable", "SOCKS server general failure".
            is SocketException -> if (t.message.orEmpty().startsWith("SOCKS")) deviceOrDaemon(depth) else Failed(describe(t))
            is XmlPullParserException -> Failed("unreadable answer")
            // A reply that never came, "unexpected end of stream": a device's failure reaches us as
            // an HTTP 502 from our own daemon, so a connection that is just closed is the daemon's.
            is IOException -> NoAnswer
            else -> if (t.message == NO_SOCKS_MESSAGE) NotConnected else Failed(describe(t))
        }

        private fun deviceOrDaemon(depth: Int): DriveError = if (depth >= DEVICE_DEPTH) Unreachable else NoAnswer

        private fun describe(t: Throwable): String =
            t.javaClass.simpleName + (t.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(120) } ?: "")
    }
}

/** An answer or the reason there is none; the page never sees an exception. */
sealed interface DriveResult<out T> {
    data class Ok<T>(val value: T) : DriveResult<T>
    data class Err(val error: DriveError) : DriveResult<Nothing>
}

// ---------------------------------------------------------------------------------------------
// The local proxy: the address a player on this device can use
// ---------------------------------------------------------------------------------------------

/** The proxy's login, when the proxy asks for one. The password never appears in [toString]. */
class DriveProxyAuth(val user: String, val password: String) {
    override fun toString(): String = "DriveProxyAuth(user=$user, password=***)"
}

/**
 * The local WebDAV proxy of Settings -> Taildrive, as stored for one profile. [host] is an
 * address to connect to (the proxy's bind address, or loopback when that is a wildcard), not
 * bracketed; [auth] is set exactly when the proxy checks a login — the daemon starts the
 * proxy with authentication only if the switch is on and both user and password are not empty.
 */
class DriveProxy(val enabled: Boolean, val host: String, val port: Int, val auth: DriveProxyAuth?) {

    /** [host] as it goes into a URL: an IPv6 literal in brackets. */
    private val urlHost: String get() = if (':' in host) "[$host]" else host

    /** `http://host:port` with no credentials. */
    val baseUrl: String get() = "http://$urlHost:$port"

    /** The address of [location] on the proxy; with the login in front when [withCredentials] and the proxy has one. */
    fun urlFor(location: DriveLocation, withCredentials: Boolean = false): String {
        val login = auth?.takeIf { withCredentials }?.let { "${percentEncode(it.user)}:${percentEncode(it.password)}@" }.orEmpty()
        return "http://$login$urlHost:$port${encodedPath(location)}"
    }

    /** Whether something accepts connections on the proxy's address. Blocking. */
    fun isListening(timeoutMs: Int = 800): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: IOException) {
        false
    }

    override fun toString(): String = "DriveProxy(enabled=$enabled, $host:$port, auth=${auth != null})"

    companion object {
        const val DEFAULT_PORT = 33445
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_USER = "tailsocks"

        // The profile preference keys of ui/TaildriveActivity.kt, which writes them, and
        // TailscaledService.applyTaildrive, which starts the proxy from them.
        const val KEY_ENABLED = "taildrive_proxy_enabled"
        const val KEY_IP = "taildrive_proxy_ip"
        const val KEY_PORT = "taildrive_proxy_port"
        const val KEY_AUTH_ENABLED = "taildrive_proxy_auth_enabled"
        const val KEY_USER = "taildrive_proxy_username"
        const val KEY_PASSWORD = "taildrive_proxy_password"

        /** From the stored strings; what [read] does, apart from the reading. */
        fun of(enabled: Boolean, bind: String, port: String, authEnabled: Boolean, user: String, password: String) =
            DriveProxy(
                enabled = enabled,
                host = connectHost(bind),
                port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT,
                auth = if (authEnabled && user.isNotEmpty() && password.isNotEmpty()) DriveProxyAuth(user, password) else null
            )

        fun read(prefs: SharedPreferences): DriveProxy = of(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            bind = prefs.getString(KEY_IP, DEFAULT_HOST) ?: DEFAULT_HOST,
            port = prefs.getString(KEY_PORT, DEFAULT_PORT.toString()) ?: DEFAULT_PORT.toString(),
            authEnabled = prefs.getBoolean(KEY_AUTH_ENABLED, false),
            user = prefs.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER,
            password = prefs.getString(KEY_PASSWORD, "") ?: ""
        )

        /** The proxy of profile [accountId]. */
        fun read(context: Context, accountId: String): DriveProxy =
            read(context.getSharedPreferences("appctr_$accountId", Context.MODE_PRIVATE))

        /** The profile preference that says the share browser opens files through the link before the document road. */
        const val KEY_OPEN_VIA_LINK = "taildrive_open_via_link"

        fun openViaLink(context: Context, accountId: String): Boolean =
            context.getSharedPreferences("appctr_$accountId", Context.MODE_PRIVATE).getBoolean(KEY_OPEN_VIA_LINK, false)

        fun setOpenViaLink(context: Context, accountId: String, on: Boolean) {
            context.getSharedPreferences("appctr_$accountId", Context.MODE_PRIVATE).edit().putBoolean(KEY_OPEN_VIA_LINK, on).apply()
        }

        /** The address to connect to a listener bound to [bind]: a wildcard is reached over loopback. */
        internal fun connectHost(bind: String): String {
            val host = bind.trim().removePrefix("[").removeSuffix("]")
            return when (host) {
                "", "0.0.0.0" -> DEFAULT_HOST
                "::" -> "::1"
                else -> host
            }
        }
    }
}

private const val HEX = "0123456789ABCDEF"

/**
 * [s] as a URL component: every byte of its UTF-8 form outside RFC 3986's unreserved set
 * (letters, digits, `-._~`) as `%XX`. More than a path needs — `(`, `+` and `&` are legal
 * there — but it is exact for any name, and a player has no way to mistake `%2B` for a space.
 */
internal fun percentEncode(s: String): String {
    val out = StringBuilder(s.length + 8)
    for (byte in s.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            out.append(c)
        } else {
            out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
    }
    return out.toString()
}

/** The path of [location] with every segment escaped; `/` for the root. */
internal fun encodedPath(location: DriveLocation): String =
    if (location.isRoot) "/" else location.segments.joinToString("") { "/" + percentEncode(it) }

// ---------------------------------------------------------------------------------------------
// Handing a file to another app
// ---------------------------------------------------------------------------------------------

/**
 * Everything needed to open one file in another app, by either of the two roads of
 * [TaildriveOpenPlan]: the file as a document of the app's own provider, and the link on the local
 * proxy. [url] carries the proxy's login when the proxy has one, so it is internal and absent from
 * [toString]; [deviceUrl] and [tailnetUrl] are the same file without a login, for showing.
 */
class DriveOpenSpec internal constructor(
    val name: String,
    val mime: String,
    internal val url: String,
    internal val authorization: String?,
    /** The file on this device's proxy, no login: what a player here would be given. */
    val deviceUrl: String,
    /** The file at Taildrive's own address, which every Tailscale client with Taildrive access answers. */
    val tailnetUrl: String,
    /** The file as a document of the app's provider (`drive_path:<account>:<path>`); null where there is no document road. */
    internal val documentId: String? = null,
    /** Whether the local proxy is on and answering: the link road exists only then. */
    val linkReady: Boolean = true,
) {
    /**
     * The types to ask for, in order: the file's own, then its family (the video wildcard for a video). A typed
     * intent is not offered to browsers, which declare no type for http — only to apps that
     * say they play or show this.
     */
    internal fun types(): List<String> = listOfNotNull(mime, DriveMime.family(mime)).distinct()

    /** Whether this is a file to play or look at: the kind a browser is no taker of. */
    private val isMedia: Boolean get() = TaildriveOpenPlan.isMedia(DriveMime.of(name).kind)

    /**
     * The `ACTION_VIEW` intents of the link road, one per type of [types]. `title` is the extra
     * VLC, MX Player and Just Player read; `headers` is MX Player's way to send `Authorization`, for
     * the players that do not take a login from the URL.
     */
    fun intents(): List<Intent> = types().map(::linkIntent)

    private fun linkIntent(type: String): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), type).apply {
        putExtra("title", name)
        authorization?.let { putExtra("headers", arrayOf("Authorization", it)) }
    }

    /**
     * The document road's intent: the provider's `content://` URI, which the app grants the player to
     * read (the provider is ours, so the grant is ours to give) — no login in it, no address.
     */
    private fun documentIntent(type: String): Intent? {
        val id = documentId ?: return null
        val uri = DocumentsContract.buildDocumentUri(DOCUMENTS_AUTHORITY, id)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(name, uri)
            putExtra("title", name)
        }
    }

    private fun intentOf(road: OpenRoad, type: String): Intent? = if (road == OpenRoad.CONTENT) documentIntent(type) else linkIntent(type)

    /**
     * Hands the file to an app, never silently to a browser. Asks the system what takes each road
     * (and each type: the file's own, then its family), lets [TaildriveOpenPlan] decide, and starts
     * the one real player by name, or a chooser that offers the real players and nobody else. A
     * media file only a browser takes comes back as [DriveLaunch.OnlyBrowser], for the page to put to
     * the person. Asked afresh every time: an app may have been installed meanwhile. From an Activity
     * — [context] may wrap one — the player joins this task, so Back returns to the page.
     *
     * A television answers what no app takes with a placeholder — Android TV's media stub shows a
     * toast and finishes — and it is nobody here.
     */
    fun launch(context: Context, preferLink: Boolean = false): DriveLaunch {
        val browsers = browserPackages(context)
        val resolutions = types().flatMap { type ->
            OpenRoad.entries.filter { it == OpenRoad.CONTENT || linkReady }.mapNotNull { road ->
                intentOf(road, type)?.let { RoadResolution(road, type, TaildriveOpenPlan.takers(answers(context, it), browsers)) }
            }
        }
        return when (val plan = TaildriveOpenPlan.plan(Build.VERSION.SDK_INT, isMedia, resolutions, preferLink)) {
            is OpenPlan.Start -> start(context, intentOf(plan.road, plan.type) ?: return DriveLaunch.NoApp, plan.apps)
            is OpenPlan.OnlyBrowser -> DriveLaunch.OnlyBrowser { openInBrowser(context) }
            OpenPlan.NoApp -> DriveLaunch.NoApp
        }
    }

    private fun start(context: Context, intent: Intent, apps: List<AppRef>): DriveLaunch {
        val host = context.findActivity()
        val only = apps.singleOrNull()
        val start = if (only != null) Intent(intent).setComponent(ComponentName(only.pkg, only.cls)) else {
            // Everything that answered and is not one of the players stays out of the chooser: browsers, placeholders.
            val excluded = answers(context, intent).filter { it !in apps }.map { ComponentName(it.pkg, it.cls) }
            Intent.createChooser(intent, name).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toTypedArray())
        }
        return launched(context, host, start)
    }

    /** The link as a page in a browser, which the person asked for in so many words. */
    private fun openInBrowser(context: Context): DriveLaunch =
        launched(context, context.findActivity(), Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))

    private fun launched(context: Context, host: Activity?, intent: Intent): DriveLaunch {
        if (host == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (host ?: context).startActivity(intent)
            DriveLaunch.Started
        } catch (_: ActivityNotFoundException) {
            DriveLaunch.NoApp
        } catch (e: SecurityException) {
            DriveLaunch.Failed(e.javaClass.simpleName)
        }
    }

    override fun toString(): String = "DriveOpenSpec(name=$name, mime=$mime)"

    companion object {
        /** The authority of the app's DocumentsProvider, as the manifest declares it (`${applicationId}.documents`). */
        internal const val DOCUMENTS_AUTHORITY = BuildConfig.APPLICATION_ID + ".documents"

        /**
         * The spec for the file at [file], through [proxy]. [accountId] names the profile the
         * document road reads as; without it there is no document road. [linkReady] is whether the
         * proxy answers right now.
         */
        fun of(proxy: DriveProxy, file: DriveLocation, accountId: String? = null, linkReady: Boolean = true): DriveOpenSpec {
            val name = file.name.orEmpty()
            val auth = proxy.auth
            return DriveOpenSpec(
                name = name,
                mime = DriveMime.guess(name),
                url = proxy.urlFor(file, withCredentials = true),
                authorization = auth?.let { "Basic " + Base64.encode("${it.user}:${it.password}".toByteArray(Charsets.UTF_8)) },
                deviceUrl = proxy.urlFor(file),
                tailnetUrl = TAILNET_BASE + encodedPath(file),
                documentId = accountId?.let { documentIdOf(it, file) },
                linkReady = linkReady
            )
        }

        /** The provider's id for [file] of profile [accountId]: what its `drive_path:` documents are called. */
        fun documentIdOf(accountId: String, file: DriveLocation): String = "drive_path:$accountId:${file.davPath}"

        /** The apps whose package also answers a plain link to a web page: what a browser is. */
        private fun browserPackages(context: Context): Set<String> =
            answersTo(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_ALL)
                .map { it.pkg }.toSet()

        private fun answers(context: Context, intent: Intent): List<AppRef> = answersTo(context, intent, PackageManager.MATCH_DEFAULT_ONLY)

        private fun answersTo(context: Context, intent: Intent, flags: Int): List<AppRef> {
            val pm = context.packageManager
            val found = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, flags)
                }
            }.getOrDefault(emptyList())
            return found.mapNotNull { it.activityInfo }.map { AppRef(it.packageName, it.name) }
        }
    }
}

/** What starting the other app came to. */
sealed interface DriveLaunch {
    data object Started : DriveLaunch

    /** No installed app takes the file. */
    data object NoApp : DriveLaunch

    /**
     * Nothing here plays this kind of file from a link, only a web browser takes it; [openAnyway]
     * hands it to the browser, once the person has said so.
     */
    class OnlyBrowser(val openAnyway: () -> DriveLaunch) : DriveLaunch

    data class Failed(val detail: String) : DriveLaunch
}

// ---------------------------------------------------------------------------------------------
// Listing
// ---------------------------------------------------------------------------------------------

/** What the browser needs of a WebDAV client: one folder, one level deep. TaildriveClient is the real one. */
fun interface DriveLister {
    fun list(path: String): List<WebDavFile>
}

/**
 * The shares of the tailnet as a tree to walk. Every call is blocking — call it off the main
 * thread — and none throws: a failure is a [DriveResult.Err].
 */
class TaildriveBrowse(private val retryDelayMs: Long = RETRY_DELAY_MS, private val lister: DriveLister) {

    /**
     * What is in [location]. The root is special: Taildrive has one tailnet there, and a page of
     * "devices" is what was meant, so a root that holds exactly one folder is entered and the
     * listing of that folder is returned, with [DriveListing.location] saying so. Entries
     * starting with a dot (`.DS_Store`, `._name`) are left out unless [showHidden].
     *
     * A "bad gateway" from a device or below is asked again once, quietly: the first request
     * after the other device's daemon restarted goes to a connection that died with it, and
     * the next one is the one that works.
     */
    fun list(location: DriveLocation, showHidden: Boolean = false): DriveResult<DriveListing> {
        var retried = false
        while (true) {
            // Where the request that failed went: the root, then the tailnet that was entered for the user.
            var here = location
            try {
                var entries = fetch(here, showHidden)
                if (here.isRoot) {
                    val only = entries.singleOrNull()
                    if (only != null && only.isFolder) {
                        here = here.child(only.name)
                        entries = fetch(here, showHidden)
                    }
                }
                return DriveResult.Ok(DriveListing(here, entries))
            } catch (e: Exception) {
                if (!retried && isStale(e, here.depth) && pause()) {
                    retried = true
                    Log.i(TAG, "listing at depth ${here.depth} got a stale gateway, asking again")
                    continue
                }
                val error = DriveError.from(e, here.depth)
                // The class and the status, never the message: it may name an address.
                Log.w(TAG, "listing at depth ${here.depth} failed: ${e.javaClass.simpleName} -> $error")
                return DriveResult.Err(error)
            }
        }
    }

    /** Waits for the retry; false when the thread was interrupted, which is the end of asking. */
    private fun pause(): Boolean = try {
        Thread.sleep(retryDelayMs)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun fetch(location: DriveLocation, showHidden: Boolean): List<DriveEntry> =
        entriesOf(location, lister.list(location.davPath), showHidden)

    companion object {
        /** How long the quiet second ask waits: the other device's daemon needs a moment to be a peer again. */
        const val RETRY_DELAY_MS = 1_200L

        /**
         * Whether [e], from a request at [depth], is what a device's restarted daemon gives the
         * first request after it: our own daemon's reverse proxy answering 502 or 503 for a connection that
         * died. Above the device level nothing was forwarded, so there is no such thing.
         */
        internal fun isStale(e: Exception, depth: Int): Boolean =
            e is WebDavHttpException && (e.code == 502 || e.code == 503) && depth >= DriveError.DEVICE_DEPTH

        /** Browsing as the profile [accountId]'s daemon sees the tailnet. */
        fun forAccount(context: Context, accountId: String): TaildriveBrowse {
            val client = TaildriveClient(context.applicationContext, accountId)
            return TaildriveBrowse { client.list(it) }
        }

        /**
         * What stops a listing before it is asked for: the daemon is off, or the policy does not
         * give this device `drive:access` — its daemon would close the connection without a word.
         * Null when nothing is known to be in the way, which includes a policy that has not been
         * read (no map yet, or a bridge without the call): the listing then says what happened.
         */
        fun listingBlock(running: Boolean, access: TaildriveAccess.Verdict): DriveError? = when {
            !running -> DriveError.NotConnected
            access == TaildriveAccess.Verdict.DENIED -> DriveError.NotPermitted
            else -> null
        }

        /** [listingBlock] for the daemon as it is now. Blocking: a LocalAPI round trip. */
        fun listingBlock(context: Context): DriveError? {
            val running = ProxyState.isActualRunning(context)
            return listingBlock(running, if (running) TaildriveAccess.report().access else TaildriveAccess.Verdict.UNKNOWN)
        }

        /**
         * What to hand to another app for the file at [file]; reads the proxy settings afresh.
         * Blocking. The document road needs no proxy, and the link road exists while the proxy
         * answers; when the person asked for the link ([preferLink]) a proxy that is off or not
         * answering is the reason there is nothing to open, and says so.
         */
        fun prepareOpen(context: Context, accountId: String, file: DriveLocation, preferLink: Boolean = false): DriveResult<DriveOpenSpec> {
            val proxy = DriveProxy.read(context, accountId)
            if (preferLink && !proxy.enabled) return DriveResult.Err(DriveError.ProxyOff)
            val answering = proxy.enabled && proxy.isListening()
            if (preferLink && !answering) return DriveResult.Err(DriveError.ProxyNotListening)
            return DriveResult.Ok(DriveOpenSpec.of(proxy, file, accountId, linkReady = answering))
        }

        /**
         * The rows of a PROPFIND of [parent] (Depth 1): its direct children, without the entry
         * for [parent] itself, sorted by [DriveEntryOrder]. The server's hrefs are taken only for
         * the last name; the place of a row is always [parent] plus that name.
         */
        fun entriesOf(parent: DriveLocation, files: List<WebDavFile>, showHidden: Boolean = false): List<DriveEntry> {
            val base = parent.davPath.trimEnd('/')
            return files.mapNotNull { file ->
                val path = hrefPath(file.href).trimEnd('/')
                if (!path.startsWith("$base/")) return@mapNotNull null
                val name = path.substring(base.length + 1)
                // A deeper entry is not a child; an empty name is the folder itself.
                if (name.isEmpty() || '/' in name) return@mapNotNull null
                if (!showHidden && name.startsWith('.')) return@mapNotNull null
                val kind = if (file.isDirectory) folderKind(parent.level) else DriveMime.of(name).kind
                DriveEntry(name, kind, if (file.isDirectory) 0 else file.size, file.lastModified)
            }.sortedWith(DriveEntryOrder)
        }

        private fun folderKind(parent: DriveLevel) = when (parent) {
            DriveLevel.TAILNETS -> DriveKind.TAILNET
            DriveLevel.DEVICES -> DriveKind.DEVICE
            DriveLevel.SHARES -> DriveKind.SHARE
            DriveLevel.FOLDER -> DriveKind.FOLDER
        }

        /** The path part of an href, which a server may send as a full URL. */
        private fun hrefPath(href: String): String {
            if (!href.startsWith("http://", ignoreCase = true) && !href.startsWith("https://", ignoreCase = true)) return href
            return "/" + href.substringAfter("://").substringAfter('/', "")
        }
    }
}
