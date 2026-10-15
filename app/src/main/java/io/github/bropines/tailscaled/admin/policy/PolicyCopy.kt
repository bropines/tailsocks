package io.github.bropines.tailscaled.admin.policy

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleServer
import io.github.bropines.tailscaled.admin.api.headscale.PolicyMode
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.SensitiveClipboard
import io.github.bropines.tailscaled.admin.secure.UnlockFailure
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.admin.secure.findFragmentActivity
import io.github.bropines.tailscaled.core.tryStartActivity
import io.github.bropines.tailscaled.ui.HelpText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The policy tab's copy actions: the policy file as it is, and the network's map for an AI
 * agent (PolicyExport) — copied, or handed to a share sheet. Each asks for the device's lock
 * first, the same prompt as a change (a strong biometric or the screen lock), because the text
 * is the map of the network. What is copied is marked sensitive.
 */

/** The three buttons and what they hold, folded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PolicyCopyActions(state: ConsoleState, vm: AdminConsoleViewModel?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val inPreview = LocalInspectionMode.current

    /** [action] after the lock is passed; a refusal is said, a closed prompt is not. */
    fun guarded(title: Int, prefetch: Boolean, action: suspend () -> Unit) {
        if (inPreview || vm == null) return
        val activity = ctx.findFragmentActivity() ?: return vm.say(ConsoleText.unlockFailure(ctx, UnlockFailure.PROMPT_UNAVAILABLE))
        // The lists the map needs load while the prompt is up.
        if (prefetch) loadMapLists(vm)
        scope.launch {
            when (val r = AdminWriteGate.unlock(activity, ctx.getString(title), ctx.getString(R.string.admin_pex_unlock_subtitle))) {
                is UnlockResult.Granted -> if (r.grant.consume()) action()
                UnlockResult.Cancelled -> Unit
                is UnlockResult.Failed -> vm.say(ConsoleText.unlockFailure(ctx, r.reason))
            }
        }
    }

    suspend fun agentText(): String? {
        val v = vm ?: return null
        withTimeoutOrNull(MAP_WAIT_MS) {
            v.state.first { !it.devices.loading && !it.users.loading }
            v.headscale.state.first { !it.server.loading }
        }
        val map = tailnetMap(v.state.value, v.headscale.state.value.server.value) ?: return null
        return PolicyExport.forAgent(map, ExportWords { id, args -> ctx.getString(id, *args) }, ctx.isRussian(), System.currentTimeMillis())
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CopyButton(Icons.Default.ContentCopy, ctx.getString(R.string.admin_pex_copy)) {
                guarded(R.string.admin_pex_unlock_policy, prefetch = false) {
                    val text = state.policy.file.value?.text ?: return@guarded
                    SensitiveClipboard.copy(ctx, ctx.getString(R.string.admin_pex_clip_policy), text)
                    vm?.say(ctx.getString(R.string.admin_pex_copied_policy))
                }
            }
            CopyButton(Icons.Default.SmartToy, ctx.getString(R.string.admin_pex_copy_ai)) {
                guarded(R.string.admin_pex_unlock_ai, prefetch = true) {
                    val text = agentText() ?: return@guarded
                    SensitiveClipboard.copy(ctx, ctx.getString(R.string.admin_pex_clip_ai), text)
                    vm?.say(ctx.getString(R.string.admin_pex_copied_ai, vm.state.value.devices.value?.size ?: 0))
                }
            }
            // Beside the copy it shares, as its icon: the row stays two lines on a phone.
            OutlinedIconButton(onClick = {
                guarded(R.string.admin_pex_unlock_share, prefetch = true) {
                    val text = agentText() ?: return@guarded
                    val title = ctx.getString(R.string.admin_pex_clip_ai)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_TITLE, title)
                    ctx.tryStartActivity(Intent.createChooser(send, ctx.getString(R.string.admin_pex_unlock_share)))
                }
            }, shape = MaterialTheme.shapes.medium, border = ButtonDefaults.outlinedButtonBorder()) {
                Icon(Icons.Default.Share, ctx.getString(R.string.admin_pex_share_ai), Modifier.size(20.dp))
            }
        }
        HelpText(ctx.getString(R.string.admin_pex_help))
    }
}

@Composable
private fun CopyButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.medium) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/** How long the map waits for the device and user lists after the prompt; then it says what is missing. */
private const val MAP_WAIT_MS = 10_000L

/** The device and user lists, and a Headscale server's version, when no tab has read them yet. */
private fun loadMapLists(vm: AdminConsoleViewModel) {
    val s = vm.state.value
    if (s.devices.value == null && ConsoleTab.DEVICES in s.tabs) vm.refresh(ConsoleTab.DEVICES)
    if (s.users.value == null && ConsoleTab.USERS in s.tabs) vm.refresh(ConsoleTab.USERS)
    if (s.isHeadscale && vm.headscale.state.value.server.value == null) vm.headscale.refresh(force = false)
}

private val ConsoleState.isHeadscale: Boolean
    get() = (caps?.backend ?: active?.backend ?: BackendKind.TAILSCALE) != BackendKind.TAILSCALE

private fun Context.isRussian(): Boolean = resources.configuration.locales[0]?.language == "ru"

/** What the console holds, as the map's input; null before the policy file is read. */
internal fun tailnetMap(state: ConsoleState, server: HeadscaleServer?): TailnetMap? {
    val file = state.policy.file.value ?: return null
    val devices = state.devices.value
    val suffix = state.active?.tailnetDnsName?.takeIf { it.isNotBlank() }
        ?: devices.orEmpty().filter { !it.isShared }.mapNotNull { it.dnsSuffix }.groupingBy { it.lowercase() }.eachCount().maxByOrNull { it.value }?.key
    return TailnetMap(
        headscale = state.isHeadscale,
        tailnet = state.active?.tailnet?.takeIf { it.isNotBlank() && it != "-" && !state.isHeadscale },
        dnsSuffix = suffix,
        users = state.users.value,
        devices = devices,
        routes = state.routes.mapNotNull { (id, l) -> l.value?.let { id to it } }.toMap(),
        policy = file.text,
        policyExternal = state.settings.value?.aclsExternallyManagedOn == true,
        headscaleVersion = server?.version?.label,
        headscalePolicyFile = when (server?.policyMode) {
            PolicyMode.FILE -> true
            PolicyMode.DATABASE -> false
            else -> null
        },
    )
}
