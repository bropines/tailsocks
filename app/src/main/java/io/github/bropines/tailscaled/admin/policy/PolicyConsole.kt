package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.admin.secure.KeystoreSecretBox
import androidx.lifecycle.viewModelScope
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.safety.ChangeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The policy tab's actions, on the console's ViewModel: loading the file, the editor, the
 * review pipeline, the write through the safety gates, the 412 that follows a change made
 * elsewhere, the revert, and the "who can reach" previews. Its state is [PolicyState] in the
 * console state, so it outlives rotation with the rest.
 */
class PolicyConsole internal constructor(private val vm: AdminConsoleViewModel) {

    private var reviewJob: Job? = null
    private val store by lazy { PolicyStore(vm.app.filesDir, KeystoreSecretBox()) }

    private val state: PolicyState get() = vm.state.value.policy
    private fun set(change: (PolicyState) -> PolicyState) = vm.update { it.copy(policy = change(it.policy)) }
    private fun setEditor(change: (PolicyEditorState) -> PolicyEditorState) = set { s -> s.editor?.let { s.copy(editor = change(it)) } ?: s }

    // ------------------------------------------------------------------ loading

    /** The policy file (and the settings that say whether it is managed elsewhere). */
    fun load(force: Boolean = false) {
        val b = vm.backend ?: return
        val current = state.file
        val now = System.currentTimeMillis()
        loadPrevious()
        vm.refresh(ConsoleTab.SETTINGS)
        if (current.loading || (!force && current.fresh(now, FRESH_MS))) return
        set { it.copy(file = current.copy(loading = true)) }
        vm.viewModelScope.launch {
            val r = attempt { b.policyFile() }
            if (vm.backend !== b) return@launch
            set { s ->
                s.copy(file = r.fold({ Loadable(it, false, null, loadedAt = System.currentTimeMillis()) }, { s.file.copy(loading = false, error = it) }))
            }
        }
    }

    /** Drops [profileId]'s copy to revert to: the profile is gone or points at another tailnet. */
    internal suspend fun forget(profileId: String) {
        withContext(Dispatchers.IO) { runCatching { store.clear(profileId) } }
        if (vm.state.value.active?.id == profileId) set { it.copy(previous = null) }
    }

    private fun loadPrevious() {
        val id = vm.state.value.active?.id ?: return
        vm.viewModelScope.launch {
            val prev = withContext(Dispatchers.IO) { store.load(id) }
            set { it.copy(previous = prev) }
        }
    }

    // ------------------------------------------------------------------ the editor

    fun openEditor(line: Int? = null) {
        val file = state.file.value ?: return
        set { it.copy(editor = PolicyEditorState(base = file, text = file.text, goToLine = line), conflict = null) }
    }

    fun edit(text: String) = setEditor { if (it.text == text) it else it.copy(text = text, review = null, sendError = null) }

    fun closeEditor() {
        reviewJob?.cancel()
        set { it.copy(editor = null, conflict = null) }
    }

    /** Back from the review to the text, the cursor on [line] when one is given. */
    fun backToEditor(line: Int? = null) {
        reviewJob?.cancel()
        setEditor { it.copy(review = null, goToLine = line ?: it.goToLine) }
    }

    fun lineShown() = setEditor { it.copy(goToLine = null) }

    // ------------------------------------------------------------------ the pipeline

    /** Runs the checks on the editor's text; the review screen shows each step as it ends. */
    fun review() {
        val b = vm.backend ?: return
        val ed = state.editor ?: return
        reviewJob?.cancel()
        val subject = subject()
        val s = vm.state.value
        // What the previews' words mean in devices; without a list they are compared as written.
        val view = s.devices.value?.let { TailnetView(it, s.users.value) }
        reviewJob = vm.viewModelScope.launch {
            PolicyPipeline.review(b, ed.base, ed.text, subject, ed.isRevert, view) { r ->
                // A review of a text since edited is stale: dropped.
                setEditor { e -> if (e.text == r.candidate && e.base == r.base) e.copy(review = r, sendError = null) else e }
            }
        }
    }

    /** This phone in the policy: its user (an untagged node) and its address — when it is a node here. */
    private fun subject(): AccessSubject? {
        val s = vm.state.value
        if (!s.phoneInTailnet) return null
        val me = s.devices.value?.firstOrNull { it.nodeId == s.self.nodeId } ?: return null
        return AccessSubject(login = if (me.isTagged) null else (s.self.loginName ?: me.user), ipv4 = me.ipv4)
    }

    /** Sends a passed review through the gates: the typed phrase, the unlock, If-Match. */
    fun apply() {
        val ed = state.editor ?: return
        val review = ed.review?.takeIf { it.passed && it.candidate == ed.text } ?: return
        val profileId = vm.state.value.active?.id ?: return
        var written: PolicyFile? = null
        val planned = PolicyChanges.plan(review, texts(review), vm.tailnetLabel) { written = it }
        vm.propose(planned, outcome = { out -> onOutcome(profileId, review, out, written) })
    }

    private fun onOutcome(profileId: String, review: PolicyReview, out: ChangeOutcome, written: PolicyFile?) {
        when (out) {
            is ChangeOutcome.Applied -> {
                val saved = StoredPolicy(review.base.text, System.currentTimeMillis(), written?.etag)
                vm.viewModelScope.launch {
                    withContext(Dispatchers.IO) { runCatching { store.save(profileId, saved) } }
                    set { it.copy(previous = saved) }
                }
                reviewJob?.cancel()
                set { it.copy(editor = null, conflict = null) }
                load(force = true)
            }
            is ChangeOutcome.Failed -> {
                val e = out.error
                if (e is AdminApiException.PreconditionFailed) conflict(review)
                else setEditor { it.copy(sendError = sendError(e)) }
            }
            is ChangeOutcome.Refused -> setEditor { it.copy(sendError = ConsoleText.refusal(vm.text, out.reason)) }
        }
    }

    private fun sendError(e: Throwable): String = when (e) {
        is PolicyRejectedException -> vm.text.getString(R.string.admin_cfg_policy_rejected, e.message)
        else -> ConsoleText.error(vm.text, e)
    }

    /** The 412: their version fetched, my edit merged onto it where the two do not touch. */
    private fun conflict(review: PolicyReview) {
        val b = vm.backend ?: return
        vm.viewModelScope.launch {
            val theirs = attempt { b.policyFile() }.getOrElse {
                setEditor { e -> e.copy(sendError = vm.text.getString(R.string.admin_cfg_policy_conflict_unread, ConsoleText.error(vm.text, it))) }
                return@launch
            }
            val merge = LineDiff.merge3(LineDiff.lines(review.base.text), LineDiff.lines(review.candidate), LineDiff.lines(theirs.text))
            set {
                it.copy(
                    file = Loadable(theirs, loadedAt = System.currentTimeMillis()),
                    conflict = PolicyConflict(review.base, review.candidate, theirs, merge.lines?.joinToString("\n"), merge.conflicts, review.isRevert),
                )
            }
        }
    }

    fun resolve(choice: ConflictChoice) {
        val c = state.conflict ?: return
        when (choice) {
            ConflictChoice.DISCARD -> closeEditor()
            ConflictChoice.REAPPLY -> {
                val merged = c.merged ?: return
                set { it.copy(conflict = null, editor = PolicyEditorState(c.theirs, merged, c.isRevert)) }
                review()
            }
            ConflictChoice.EDIT -> set { it.copy(conflict = null, editor = PolicyEditorState(c.theirs, c.mine, c.isRevert)) }
        }
    }

    /**
     * The version saved before this phone's last write, back in the editor against what the
     * server holds now, and straight into review: a revert passes every check a change does.
     */
    fun revert() {
        val b = vm.backend ?: return
        val prev = state.previous ?: return
        vm.viewModelScope.launch {
            val now = attempt { b.policyFile() }.getOrElse {
                vm.say(ConsoleText.error(vm.text, it))
                return@launch
            }
            set {
                it.copy(
                    file = Loadable(now, loadedAt = System.currentTimeMillis()),
                    editor = PolicyEditorState(now, prev.text, isRevert = true),
                    conflict = null,
                )
            }
            review()
        }
    }

    // ------------------------------------------------------------------ who can reach

    /** Which rules of the saved policy let [previewFor] through, as [type] reads it. */
    fun whoCanReach(type: PolicyPreviewType, previewFor: String, label: String) {
        val b = vm.backend ?: return
        set { it.copy(reachQuery = ReachQuery(type, previewFor, label), reach = Loadable(loading = true)) }
        vm.viewModelScope.launch {
            val result = attempt {
                val text = state.file.value?.text ?: b.policyFile().also { f -> set { it.copy(file = Loadable(f, loadedAt = System.currentTimeMillis())) } }.text
                b.previewPolicy(text, type, previewFor)
            }
            set { s ->
                if (s.reachQuery?.previewFor != previewFor || s.reachQuery?.type != type) s
                else s.copy(reach = result.fold({ Loadable(it, loadedAt = System.currentTimeMillis()) }, { Loadable(error = it) }))
            }
        }
    }

    /** "Who can reach this device on [port]?" — for the device sheet. */
    fun whoCanReach(device: ApiDevice, port: Int) {
        val ip = device.ipv4 ?: device.ipv6 ?: return
        whoCanReach(PolicyPreviewType.IP_PORT, "$ip:$port", "${device.shortName}:$port")
    }

    fun clearReach() = set { it.copy(reachQuery = null, reach = Loadable()) }

    // ------------------------------------------------------------------ words

    private fun texts(review: PolicyReview): PolicyChangeTexts {
        val t = vm.text
        val diff = review.diff.orEmpty()
        val oldLines = LineDiff.lines(review.base.text).size
        val newLines = LineDiff.lines(review.candidate).size
        val warnings = buildList {
            val risks = review.risks.orEmpty()
            risks.take(MAX_LISTED).forEach { add(PolicyText.riskTitle(t, it.kind) + ": " + it.detail) }
            if (risks.size > MAX_LISTED) add(t.resources.getQuantityString(R.plurals.admin_cfg_policy_more, risks.size - MAX_LISTED, risks.size - MAX_LISTED))
            review.access?.let { a ->
                a.probes.filter { it.warns }.forEach { add(PolicyText.lostLine(t, it)) }
                if (!a.ran) add(PolicyText.accessUnchecked(t, a))
            }
        }
        return PolicyChangeTexts(
            title = t.getString(if (review.isRevert) R.string.admin_cfg_policy_revert_title else R.string.admin_cfg_policy_change_title),
            effect = t.getString(R.string.admin_cfg_policy_change_effect),
            phrase = t.getString(R.string.admin_cfg_policy_phrase),
            linesLabel = t.getString(R.string.admin_cfg_policy_lines_label),
            linesBefore = t.resources.getQuantityString(R.plurals.admin_cfg_policy_lines, oldLines, oldLines),
            linesAfter = t.getString(
                R.string.admin_cfg_policy_lines_after,
                t.resources.getQuantityString(R.plurals.admin_cfg_policy_lines, newLines, newLines), LineDiff.added(diff), LineDiff.removed(diff),
            ),
            warnings = warnings,
        )
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val FRESH_MS = 60_000L
        private const val MAX_LISTED = 4
    }
}
