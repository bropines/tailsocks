package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ExitNodeSuggestion
import io.github.bropines.tailscaled.core.ExitNodeSuggestion.Outcome
import io.github.bropines.tailscaled.core.ExitNodeSuggestion.Reason
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Words of the "best exit node" row, resolved by the parent: the row lives in a sheet, which
 * is a window of its own and would look them up in the system language — see
 * wrapContextWithLocale().
 */
data class BestExitNodeStrings(
    val title: String,
    val asking: String,
    val suggestedBy: String,
    val retry: String,
    private val reasons: Map<Reason, String>,
) {
    fun reason(reason: Reason): String = reasons.getValue(reason)
}

@Composable
fun bestExitNodeStrings() = BestExitNodeStrings(
    title = stringResource(R.string.exit_best_title),
    asking = stringResource(R.string.exit_best_asking),
    suggestedBy = stringResource(R.string.exit_best_suggested_cd),
    retry = stringResource(R.string.action_retry),
    reasons = Reason.entries.associateWith { stringResource(bestExitNodeReasonRes(it)) },
)

/** The reason a suggestion is missing, for surfaces with no sheet of their own (widget, toast). */
fun bestExitNodeReasonRes(reason: Reason): Int = when (reason) {
    Reason.NOT_RUNNING -> R.string.exit_best_not_running
    Reason.NO_RELAY_YET -> R.string.exit_best_no_relay
    Reason.NONE_RECOMMENDED -> R.string.exit_best_none_recommended
    Reason.FAILED -> R.string.exit_best_failed
}

/** The StableID of the suggested node, to mark it in the list under the row. */
val Outcome?.suggestedId: String?
    get() = (this as? Outcome.Suggested)?.id

/**
 * The first row of an exit-node picker: the node Tailscale itself recommends
 * ([ExitNodeSuggestion]), named before it is chosen. [outcome] null is the question still
 * out. A tap applies the named node like any row below it; when there is no suggestion the
 * row says why in a few words, and a reason worth asking again makes the tap a retry.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BestExitNodeRow(
    outcome: Outcome?,
    strings: BestExitNodeStrings,
    onApply: (Outcome.Suggested) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggested = outcome as? Outcome.Suggested
    val reason = (outcome as? Outcome.Unavailable)?.reason
    val enabled = suggested != null || reason?.retryable == true
    val subtitle = when {
        suggested != null -> listOfNotNull(suggested.name, suggested.place).joinToString(" · ")
        reason != null -> strings.reason(reason)
        else -> strings.asking
    }
    val accent = MaterialTheme.colorScheme.primary
    Card(
        onClick = { if (suggested != null) onApply(suggested) else onRetry() },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // A suggestion that cannot be had reads as such, without vanishing: the row
                // still says why.
                .alpha(if (outcome == null || enabled) 1f else 0.6f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = accent)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    strings.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (suggested != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when {
                outcome == null -> {
                    Spacer(Modifier.width(10.dp))
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                }
                reason?.retryable == true -> {
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = strings.retry,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** The mark beside the suggested node's name in the list, so the row above and the node agree. */
@Composable
fun SuggestedExitNodeMark(contentDescription: String, modifier: Modifier = Modifier) {
    Icon(
        Icons.Default.AutoAwesome,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(15.dp)
    )
}

/** Asks [ExitNodeSuggestion] off the main thread. */
suspend fun fetchBestExitNode(peers: Collection<PeerData>?): Outcome =
    withContext(Dispatchers.IO) { ExitNodeSuggestion.fetch(peers) }
