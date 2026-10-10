package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R

/**
 * A node attribute the editor knows and explains. [key] is what is written; for one that
 * [takesValue] it is the prefix of `nextdns:<profile>`, the value typed after it. [headscale]
 * is false for an attribute Headscale's policy parser refuses.
 */
data class KnownAttr(
    val key: String,
    @param:StringRes val label: Int,
    @param:StringRes val help: Int,
    val headscale: Boolean = true,
    val takesValue: Boolean = false,
)

/**
 * The node attributes of Tailscale's policy syntax ("nodeAttrs") the device attributes page
 * offers as switches, with what each does in a sentence. Anything else is kept and shown as
 * written — custom `cap:` names, attributes newer than this list.
 */
object KnownAttrs {

    val ALL: List<KnownAttr> = listOf(
        // Headscale refuses funnel (juanfont/headscale#2527), and nothing else here.
        KnownAttr("funnel", R.string.admin_pvd_attr_funnel, R.string.admin_pvd_attr_funnel_help, headscale = false),
        KnownAttr("drive:share", R.string.admin_pvd_attr_drive_share, R.string.admin_pvd_attr_drive_share_help),
        KnownAttr("drive:access", R.string.admin_pvd_attr_drive_access, R.string.admin_pvd_attr_drive_access_help),
        KnownAttr("mullvad", R.string.admin_pvd_attr_mullvad, R.string.admin_pvd_attr_mullvad_help),
        KnownAttr("randomize-client-port", R.string.admin_pvd_attr_random_port, R.string.admin_pvd_attr_random_port_help),
        KnownAttr("disable-ipv4", R.string.admin_pvd_attr_no_ipv4, R.string.admin_pvd_attr_no_ipv4_help),
        KnownAttr("disable-captive-portal-detection", R.string.admin_pvd_attr_no_captive, R.string.admin_pvd_attr_no_captive_help),
        KnownAttr("magicdns-aaaa", R.string.admin_pvd_attr_aaaa, R.string.admin_pvd_attr_aaaa_help),
        KnownAttr("nextdns:no-device-info", R.string.admin_pvd_attr_nextdns_private, R.string.admin_pvd_attr_nextdns_private_help),
        KnownAttr("nextdns:", R.string.admin_pvd_attr_nextdns, R.string.admin_pvd_attr_nextdns_help, takesValue = true),
        KnownAttr("controld:", R.string.admin_pvd_attr_controld, R.string.admin_pvd_attr_controld_help, takesValue = true),
    )

    /** The catalogue entry [attr] is, if any: `nextdns:abc123` is the NextDNS profile entry, `nextdns:no-device-info` its own. */
    fun of(attr: String): KnownAttr? = ALL.firstOrNull { k ->
        if (k.takesValue) attr.startsWith(k.key) && attr.length > k.key.length && ALL.none { it.key == attr } else attr == k.key
    }

    /** The value after a [KnownAttr.takesValue] prefix: `nextdns:abc123` → `abc123`. */
    fun valueOf(attr: String): String? = of(attr)?.takeIf { it.takesValue }?.let { attr.removePrefix(it.key) }

    /** The entry of [attrs] that [k] is, when one is: what a switch for [k] shows as on. */
    fun find(k: KnownAttr, attrs: List<String>): String? = attrs.firstOrNull { of(it) == k }

    /** Written only as the server would refuse it: on Headscale, funnel. */
    fun refused(attr: String, headscale: Boolean): Boolean = headscale && of(attr)?.headscale == false

    /** [attr] in words: "Funnel", "NextDNS profile abc123"; as written when the editor does not know it. */
    fun label(ctx: Context, attr: String): String {
        val k = of(attr) ?: return attr
        val name = ctx.getString(k.label)
        return if (k.takesValue) "$name ${attr.removePrefix(k.key)}" else name
    }

    /**
     * [attrs] with the switch for [k] turned [on]: added last ([value] after the prefix for an
     * attribute that takes one), or removed; a profile changed in place.
     */
    fun toggle(attrs: List<String>, k: KnownAttr, on: Boolean, value: String? = null): List<String> {
        val current = find(k, attrs)
        if (!on) return if (current == null) attrs else attrs - current
        val written = if (k.takesValue) k.key + (value?.trim().orEmpty().ifEmpty { return attrs }) else k.key
        return when (current) {
            null -> attrs + written
            written -> attrs
            else -> attrs.map { if (it == current) written else it }
        }
    }
}
