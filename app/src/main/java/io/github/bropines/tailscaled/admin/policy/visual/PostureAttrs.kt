package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R

/** Where a posture attribute comes from: what the device reports, what the control plane sees, an integration, or the API. */
enum class PostureSource { DEVICE, LOCATION, INTEGRATION, CUSTOM }

/**
 * A device posture attribute a condition can check. [key] is the attribute, or — for an
 * integration's or the custom namespace — its prefix ending in ':' ([keys] then lists the
 * attributes the integration documents). [example] is a whole condition, as the docs write it,
 * to start from. An integration's [brand] is its product name and is not translated.
 */
data class PostureAttr(
    val key: String,
    @param:StringRes val label: Int?,
    @param:StringRes val help: Int,
    val example: String,
    val source: PostureSource,
    val stage: FeatureStage? = null,
    val brand: String? = null,
    val keys: List<String> = emptyList(),
) {
    val namespace: Boolean get() = key.endsWith(':')

    /** Its name in words, the stage after it: "Public IP address (beta)", "CrowdStrike Falcon". */
    fun name(ctx: Context): String {
        val words = brand ?: label?.let(ctx::getString) ?: key
        return stage?.let { ctx.getString(R.string.admin_pvc_staged, words, KnownCaps.stage(ctx, it)) } ?: words
    }

    /** What it holds and what it needs, in a sentence or two. */
    fun explain(ctx: Context): String =
        if (source == PostureSource.INTEGRATION) ctx.getString(help, brand.orEmpty(), keys.joinToString(", ")) else ctx.getString(help)
}

/**
 * The posture attributes Tailscale documents (device posture: default and geolocation
 * attributes, the integrations' own pages), for the posture editor's catalogue and for the
 * words under each condition. A condition about anything else is kept as written.
 */
object PostureAttrs {

    private fun integration(prefix: String, brand: String, example: String, vararg keys: String) =
        PostureAttr(prefix, null, R.string.admin_pvc_pa_integration_help, example, PostureSource.INTEGRATION, brand = brand, keys = keys.toList())

    val ALL: List<PostureAttr> = listOf(
        PostureAttr("node:os", R.string.admin_pvc_pa_os, R.string.admin_pvc_pa_os_help, "node:os IN ['macos', 'windows', 'linux']", PostureSource.DEVICE),
        PostureAttr("node:osVersion", R.string.admin_pvc_pa_os_version, R.string.admin_pvc_pa_os_version_help, "node:osVersion >= '14.0'", PostureSource.DEVICE),
        PostureAttr("node:tsVersion", R.string.admin_pvc_pa_ts_version, R.string.admin_pvc_pa_ts_version_help, "node:tsVersion >= '1.86'", PostureSource.DEVICE),
        PostureAttr("node:tsReleaseTrack", R.string.admin_pvc_pa_track, R.string.admin_pvc_pa_track_help, "node:tsReleaseTrack == 'stable'", PostureSource.DEVICE),
        PostureAttr("node:tsAutoUpdate", R.string.admin_pvc_pa_auto_update, R.string.admin_pvc_pa_auto_update_help, "node:tsAutoUpdate == true", PostureSource.DEVICE),
        PostureAttr("node:tsStateEncrypted", R.string.admin_pvc_pa_encrypted, R.string.admin_pvc_pa_encrypted_help, "node:tsStateEncrypted == true", PostureSource.DEVICE),
        PostureAttr("ip:country", R.string.admin_pvc_pa_country, R.string.admin_pvc_pa_country_help, "ip:country IN ['US', 'CA']", PostureSource.LOCATION),
        PostureAttr(
            "ip:publicAddress", R.string.admin_pvc_pa_public_ip, R.string.admin_pvc_pa_public_ip_help,
            "ip:publicAddress IN ['203.0.113.0/24']", PostureSource.LOCATION, stage = FeatureStage.BETA,
        ),
        integration("falcon:", "CrowdStrike Falcon", "falcon:ztaScore >= 70", "ztaScore"),
        integration("sentinelOne:", "SentinelOne", "sentinelOne:infected == false",
            "operationalState", "activeThreats", "agentVersion", "encryptedApplications", "firewallEnabled", "infected"),
        integration("intune:", "Microsoft Intune", "intune:complianceState == 'compliant'",
            "complianceState", "azureADRegistered", "deviceRegistrationState", "isSupervised", "isEncrypted", "managedDeviceOwnerType"),
        integration("jamfPro:", "Jamf Pro", "jamfPro:remoteManaged == true", "remoteManaged", "supervised", "firewallEnabled", "fileVaultStatus", "SIPEnabled"),
        integration("kandji:", "Iru (Kandji)", "kandji:mdmEnabled == true", "mdmEnabled", "agentInstalled"),
        integration("kolide:", "1Password XAM (Kolide)", "kolide:authState == 'Good'", "authState"),
        integration("fleet:", "Fleet", "fleet:present == true", "present"),
        integration("huntress:", "Huntress", "huntress:defenderStatus == 'Protected'", "defenderStatus", "defenderPolicyStatus", "firewallStatus"),
        PostureAttr("custom:", R.string.admin_pvc_pa_custom, R.string.admin_pvc_pa_custom_help, "custom:tier == 'prod'", PostureSource.CUSTOM),
    )

    private val KEY_END = Regex("""[\s=!<>]""")

    /** The attribute a condition is about: `ip:publicAddress IN [...]` → `ip:publicAddress`. */
    fun keyOf(condition: String): String = condition.trim().let { c -> KEY_END.find(c)?.let { c.substring(0, it.range.first) } ?: c }

    /** The catalogue entry [condition] checks, if the editor knows it. */
    fun of(condition: String): PostureAttr? {
        val key = keyOf(condition)
        if (key.isEmpty()) return null
        return ALL.firstOrNull { if (it.namespace) key.startsWith(it.key) && key.length > it.key.length else key == it.key }
    }
}
