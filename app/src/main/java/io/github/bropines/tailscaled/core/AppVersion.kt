package io.github.bropines.tailscaled.core

/**
 * A version as a release names it, read from wherever the app meets one: its own versionName,
 * a tag on GitHub, the name of a downloaded update.
 *
 * `5.1.0` is a release and `5.1.0-beta.2` the second beta of it, which comes out before it:
 * `5.1.0-beta.2 < 5.1.0-beta.10 < 5.1.0 < 5.1.1-beta.1`. A `v` in front and whatever follows
 * the version are read past — the `-dev` of a debug build (`5.1.0-beta.2-dev`), the
 * `-c76dbb.release` of builds up to 4.5.1 — and so is the bare `-beta` in the tags of 2.x and
 * 3.x (`v2.0.0-beta`), which were ordinary releases with no number and nothing to precede.
 *
 * Android orders the same builds by their code. That rule belongs to the build (buildSrc:
 * ReleaseVersion, docs/RELEASING.md); AppVersionTest holds the two orders together.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int? = null) : Comparable<AppVersion> {
    val isBeta: Boolean get() = beta != null

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.beta ?: Int.MAX_VALUE })

    /** The name with nothing around it: `5.1.0`, `5.1.0-beta.2`. */
    override fun toString(): String = "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")

    companion object {
        private val name = Regex("""^v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-beta\.(\d{1,9}))?(?![.\d])""")

        /** The version [text] starts with, or null when it does not start with one. */
        fun parse(text: String): AppVersion? {
            val (major, minor, patch, beta) = name.find(text)?.destructured ?: return null
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt(), beta.toIntOrNull())
        }

        /** Whether [text] names a beta build: what the About dialog and the diagnostics say next to the version. */
        fun isBeta(text: String): Boolean = parse(text)?.isBeta == true
    }
}

/**
 * Whether [latest] is a later version than [current]: the updater's one question. A [latest]
 * that is not a version is never an update, and a [current] that is not one is older than any.
 */
fun isVersionNewer(current: String, latest: String): Boolean {
    val offered = AppVersion.parse(latest) ?: return false
    val installed = AppVersion.parse(current) ?: return true
    return offered > installed
}
