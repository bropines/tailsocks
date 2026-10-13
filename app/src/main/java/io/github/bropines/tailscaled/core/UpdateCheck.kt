package io.github.bropines.tailscaled.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the in-app updater asks GitHub, and which of the answers it offers. Whether this copy
 * may ask at all is [UpdateChannel]'s; the launch check and the About dialog (ui/MainActivity.kt)
 * do the asking and the installing.
 *
 * Two channels come out of one repository (docs/RELEASING.md): releases, and betas of the
 * next version, published as pre-releases under a `-beta.N` tag. Without "Receive beta
 * versions" only releases count — for a beta build too, which is then offered the first
 * release that is newer than it. With it the newest of both by version does.
 */
object UpdateCheck {
    private const val RELEASES = "https://github.com/bropines/tailsocks/releases"
    private const val API = "https://api.github.com/repos/bropines/tailsocks/releases"

    /** A file of a release, as published. */
    data class Asset(val name: String, val url: String)

    /** A release on GitHub, as much of it as the updater reads. */
    data class Release(
        val tag: String,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<Asset> = emptyList(),
    ) {
        /** A beta by either sign: GitHub's mark, which keeps it from being "latest", or its name. */
        val isBeta: Boolean get() = prerelease || AppVersion.isBeta(tag)
    }

    /** The page of the release tagged [tag]: where an update is read about, or fetched by hand. */
    fun page(tag: String): String = "$RELEASES/tag/$tag"

    /**
     * The newest of [releases] by version, betas among them only when [betas] is set. A draft
     * and a tag that is not a version are nobody's update.
     */
    fun newest(releases: List<Release>, betas: Boolean): Release? =
        releases.filter { !it.draft && (betas || !it.isBeta) }
            .mapNotNull { release -> AppVersion.parse(release.tag)?.let { it to release } }
            .maxByOrNull { it.first }?.second

    /** What a copy named [installed] is offered out of [releases]: the newest, when it is newer. */
    fun offer(installed: String, releases: List<Release>, betas: Boolean): Release? =
        newest(releases, betas)?.takeIf { isVersionNewer(installed, it.tag) }

    /**
     * The APK of [release] for a device whose first ABI is [abi]: the one named for it
     * (`TailSocks-v5.1.0-arm64-v8a-release.apk`), or failing that whichever APK there is.
     * `-x86-` and not `x86`, which is in the name of the x86_64 APK as well.
     */
    fun apkFor(release: Release, abi: String): String? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val own = if (abi.isEmpty()) null else
            apks.firstOrNull { it.name.contains("-$abi-", ignoreCase = true) } ?: apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }
        return (own ?: apks.firstOrNull())?.url
    }

    /** GitHub's answer read: one release (`/releases/latest`) or a list of them (`/releases`). */
    fun parse(json: String): List<Release> {
        val root = AppJson.parseToJsonElement(json)
        return (if (root is JsonArray) root else listOf(root)).mapNotNull { release(it) }
    }

    private fun release(element: JsonElement): Release? {
        val o = element as? JsonObject ?: return null
        val tag = o.text("tag_name") ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { asset ->
            val a = asset as? JsonObject ?: return@mapNotNull null
            Asset(a.text("name") ?: return@mapNotNull null, a.text("browser_download_url") ?: return@mapNotNull null)
        }
        return Release(tag, o.flag("prerelease"), o.flag("draft"), assets)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    /**
     * Asks GitHub for the releases the choice is made from. Blocks, and throws when the
     * request fails: call it off the main thread.
     *
     * Without [betas] it is the question the app always asked — `/releases/latest`, GitHub's
     * newest release that is not a pre-release. With them it is the head of the whole list,
     * where the pre-releases are too: the ten published last, which is where the newest
     * version is (a list entry carries the release's notes and files, some 15 KB each).
     */
    fun fetch(betas: Boolean): List<Release> {
        val connection = URL(if (betas) "$API?per_page=10" else "$API/latest").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
        return parse(connection.inputStream.bufferedReader().use { it.readText() })
    }

    /** [fetch], then [newest]: the release this copy compares itself with. Throws when there is none. */
    fun latest(betas: Boolean): Release = newest(fetch(betas), betas) ?: throw Exception("no release")
}
