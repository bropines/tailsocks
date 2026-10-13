package io.github.bropines.tailscaled.admin.policy.visual

/*
 * Taildrive in the policy, without Compose. What Tailscale's core asks of a tailnet before a
 * device may use it (read from the core's source): the node attributes `drive:share` — the
 * device may share folders — and `drive:access` — it may open other devices' shares — on the
 * devices, and a grant carrying the `tailscale.com/cap/drive` capability from the devices that
 * open to the devices that share (the control server derives the opener's side from it). PeerAPI
 * connections are admitted whatever the ACLs say, so nothing else is needed. What is written
 * here is the example of `tailscale drive --help`: every member, their own devices.
 */
object TaildriveRules {

    const val ATTR_SHARE = "drive:share"
    const val ATTR_ACCESS = "drive:access"
    const val CAP = "tailscale.com/cap/drive"

    /** Both attributes, in the order they are written. */
    val ATTRS: List<String> = listOf(ATTR_SHARE, ATTR_ACCESS)

    /** Whose devices get them, and who opens shares: every member of the tailnet. */
    val MEMBERS: List<String> = listOf("autogroup:member")

    /** Whether [g] lets its sources open Taildrive shares on its destinations. */
    fun isDrive(g: GrantRule): Boolean = g.app.any { it.name == CAP }

    /** Whether some grant of [model] carries the capability, whomever it is for. */
    fun hasGrant(model: PolicyModel): Boolean = model.grants.any(::isDrive)

    /**
     * Whether [model] gives Taildrive to some devices at all: a rule of nodeAttrs turns sharing
     * on, one turns opening on, and a grant carries the capability. Which devices they reach is
     * not judged.
     */
    fun covered(model: PolicyModel): Boolean =
        model.nodeAttrs.any { ATTR_SHARE in it.attr } && model.nodeAttrs.any { ATTR_ACCESS in it.attr } && hasGrant(model)

    /** The grant's fields: members open every share on their own devices, to read and to write. */
    val grantFields: Fields = listOf(
        "src" to MEMBERS.pv(),
        "dst" to listOf("autogroup:self").pv(),
        "app" to PolicyValue.obj(
            CAP to PolicyValue.Arr(listOf(PolicyValue.obj("shares" to listOf("*").pv(), "access" to "rw".pv()))),
        ),
    )

    /**
     * [text] with that grant at the end of `grants`, the section written first when the file
     * has none — unless a grant already carries the capability: that one is the tailnet's own
     * answer to who opens what, and stays as it is.
     */
    fun withGrant(text: String): String =
        if (PolicyModel.read(text)?.let(::hasGrant) == true) text else PolicyEdits.addRule(text, Section.GRANTS, grantFields)
}
