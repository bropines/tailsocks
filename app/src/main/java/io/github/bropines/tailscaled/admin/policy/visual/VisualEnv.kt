package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.RiskFinding
import io.github.bropines.tailscaled.admin.policy.TailnetView

/**
 * Everything a section of the visual editor reads: the draft, the console's device and user
 * lists (for pickers and coverage), the backend, and what the checks said about elements. Built
 * by the shell from the console state; sections never reach into the view model themselves.
 */
data class VisualEnv(
    val draft: PolicyDraft,
    val devices: List<ApiDevice> = emptyList(),
    val users: List<ApiUser> = emptyList(),
    val headscale: Boolean = false,
    /** False for a read-only credential or a policy managed elsewhere: everything shows, nothing edits. */
    val canWrite: Boolean = true,
    /** Risk findings the draft has and the saved policy did not, by element: the badge on its card. */
    val risks: Map<PolicyPath, List<RiskFinding>> = emptyMap(),
    /** Messages of the last server check, by the element they are about: the error on its card. */
    val errors: Map<PolicyPath, List<String>> = emptyMap(),
    /** The element to bring into view and outline: a server error, the JSON cursor, a search hit. */
    val focus: PolicyPath? = null,
) {
    val model: PolicyModel? get() = draft.model
    val view: TailnetView get() = TailnetView(devices, users.takeIf { it.isNotEmpty() })
    /** Edits are offered at all: a writable policy whose text parses without duplicate sections. */
    val editable: Boolean get() = canWrite && draft.editable

    companion object {
        /** New risks of [text] against [base] (the saved policy), placed on elements. */
        fun risks(base: String?, text: String): Map<PolicyPath, List<RiskFinding>> {
            val t = SourceTree.parseOrNull(text) ?: return emptyMap()
            val found = PolicyLint.introduced(base?.let { HuJson.parseOrNull(it) }, HuJson.parse(text))
            return found.mapNotNull { f -> PolicyLocator.element(t, f)?.let { it to f } }.groupBy({ it.first }, { it.second })
        }

        /** Server messages placed on the elements they are about; one message may land on several. */
        fun errors(text: String, messages: List<String>): Map<PolicyPath, List<String>> {
            val t = SourceTree.parseOrNull(text) ?: return emptyMap()
            return messages.flatMap { m -> PolicyLocator.locate(t, m).map { it to m } }.groupBy({ it.first }, { it.second })
        }
    }
}

/**
 * What a section of the visual editor can ask for. The shell implements it on top of the
 * console (PolicyConsole) and shows a refusal itself, so a section only says what to do.
 */
interface VisualActions {
    /** Run one edit on the draft (PolicyEdits / SourceEdits); false when the engine refused it. */
    fun edit(op: (String) -> String): Boolean

    /** Switch to the JSON editor with the cursor on 1-based [line]. */
    fun openJson(line: Int)

    /** Bring an element into view: its section, its card, its editor on a two-pane window. */
    fun show(path: PolicyPath)
}
