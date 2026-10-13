package io.github.bropines.tailscaled.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TaildriveAccess.Verdict

/*
 * What the tailnet's policy lets this device do with Taildrive, said where a person looks: a line
 * beside the switches that share folders and a line beside the way into the other devices'
 * folders (ui/TaildriveActivity.kt). Worded like the netmap cache's verdict (ui/NetmapCacheCard.kt),
 * which has the same three answers — allowed, refused, not known until the daemon has a map.
 */

/** The two things the policy decides separately: the `drive:share` and the `drive:access` attribute. */
internal enum class DriveAbility { SHARE, ACCESS }

/** The sentence for [ability] when the server said [verdict]. */
@StringRes
internal fun verdictText(ability: DriveAbility, verdict: Verdict): Int = when (verdict) {
    Verdict.UNKNOWN -> R.string.taildrive_verdict_unknown
    Verdict.ALLOWED -> if (ability == DriveAbility.SHARE) R.string.taildrive_verdict_share_allowed else R.string.taildrive_verdict_access_allowed
    Verdict.DENIED -> if (ability == DriveAbility.SHARE) R.string.taildrive_verdict_share_denied else R.string.taildrive_verdict_access_denied
}

/** What a refusal needs, folded under its line; nothing to explain for the other answers. */
@StringRes
internal fun verdictHelp(ability: DriveAbility, verdict: Verdict): Int? =
    if (verdict != Verdict.DENIED) null
    else if (ability == DriveAbility.SHARE) R.string.taildrive_verdict_share_help
    else R.string.taildrive_verdict_access_help

/**
 * The line itself, an icon of its own beside the words — never colour alone — and, for a refusal,
 * the explanation folded under it.
 */
@Composable
internal fun DriveVerdict(ability: DriveAbility, verdict: Verdict, modifier: Modifier = Modifier) {
    val icon = when (verdict) {
        Verdict.UNKNOWN -> Icons.Default.QuestionMark
        Verdict.ALLOWED -> Icons.Default.CheckCircle
        Verdict.DENIED -> Icons.Default.Block
    }
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(verdictText(ability, verdict)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        verdictHelp(ability, verdict)?.let { HelpText(stringResource(it), Modifier.padding(start = 24.dp, top = 4.dp)) }
    }
}

/**
 * The way into the policy editor, under the first refusal; offered only where the console is and
 * a profile may write the policy ([io.github.bropines.tailscaled.admin.TaildrivePolicyFix]).
 */
@Composable
internal fun FixPolicyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 8.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        TextButton(onClick = onClick) {
            Icon(Icons.Default.Policy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.taildrive_fix_policy))
        }
        HelpText(stringResource(R.string.taildrive_fix_policy_help), Modifier.padding(start = 8.dp))
    }
}

/** Under the heading of the shared folders while the policy keeps them from being shared. */
@Composable
internal fun SharesNotLiveNote(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.taildrive_shares_not_live),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
