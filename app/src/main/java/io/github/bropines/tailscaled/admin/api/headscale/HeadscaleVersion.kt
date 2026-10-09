package io.github.bropines.tailscaled.admin.api.headscale

/**
 * A Headscale release as `GET /version` names it: "v0.29.4", or a development build's Go
 * pseudo-version "v0.0.0-20261009094437-a8d6f5be81e5", which says nothing about the API it has.
 */
data class HeadscaleVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** What the server said, for the screen. */
    val raw: String,
    /** Built from a commit, not a release: its API is found by probing, not by number. */
    val development: Boolean = false,
) : Comparable<HeadscaleVersion> {

    override fun compareTo(other: HeadscaleVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    fun atLeast(minor: Int, patch: Int = 0): Boolean = !development && this >= HeadscaleVersion(0, minor, patch, "")

    /** "0.29.4", or the raw text of a development build. */
    val label: String get() = if (development) raw.removePrefix("v") else "$major.$minor.$patch"

    companion object {
        private val SEMVER = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
        private val PSEUDO = Regex("^v?0\\.0\\.0-\\d{14}-[0-9a-f]{12}.*$")

        fun parse(text: String?): HeadscaleVersion? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (PSEUDO.matches(t) || t == "dev" || t.startsWith("dev-")) return HeadscaleVersion(0, 0, 0, t, development = true)
            val m = SEMVER.matchEntire(t) ?: return null
            val (a, b, c) = m.destructured
            return HeadscaleVersion(a.toInt(), b.toInt(), c.toInt(), t)
        }

        /** Before `GET /version` existed: 0.25 still had the routes API, 0.26 did not. */
        val V0_25 = HeadscaleVersion(0, 25, 0, "0.25")
        val V0_26 = HeadscaleVersion(0, 26, 0, "0.26")
    }
}

/**
 * What one Headscale `/api/v1` generation takes, by release. Every breaking change between
 * 0.25 and 0.30 that the console meets is one switch here, so the adapter never guesses:
 *  - 0.26 removed the routes API (approve_routes on the node instead) and moved pre-auth keys
 *    from user names to user ids;
 *  - 0.28 replaced forced/valid/invalid tags with one `tags` field, listed every pre-auth key
 *    at once and expired or deleted one by id;
 *  - 0.29 added `/auth/register` (JSON), approve/reject, `/policy/check` and switching a
 *    node's key expiry off.
 */
data class V1Level(val version: HeadscaleVersion) {
    private val dev get() = version.development

    val routesApi: Boolean get() = !dev && !version.atLeast(26)
    val preAuthKeyUserIsId: Boolean get() = dev || version.atLeast(26)
    val preAuthKeysListAll: Boolean get() = dev || version.atLeast(28)
    val preAuthKeyById: Boolean get() = dev || version.atLeast(28)
    val singleTags: Boolean get() = dev || version.atLeast(28)
    val authRegister: Boolean get() = dev || version.atLeast(29)
    val authApproveReject: Boolean get() = dev || version.atLeast(29)
    val policyCheck: Boolean get() = dev || version.atLeast(29)
    val disableExpiry: Boolean get() = dev || version.atLeast(29)

    companion object {
        /** The newest generation: what a development build or 0.30 serves at /api/v1. */
        val LATEST = V1Level(HeadscaleVersion(0, 30, 0, "0.30"))
    }
}
