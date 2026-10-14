package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R

/**
 * The top bar every screen shares, so they all look and read alike.
 *
 * [subtitle] is one line under the title — the account, the tailnet, a count.
 * With it the title steps down to titleMedium: a single-line bar has room for
 * two short lines and no more. [onBack] shows the back arrow, except on a
 * television; leave it null on a screen with nowhere to go back to.
 * [onTitleClick] makes the title a picker, and a caret after the subtitle
 * says so. [colors] is for a bar laid over something else — the QR scanner's
 * camera — and stays the default on every ordinary screen. [subtitleContent]
 * stands in for the subtitle's text where the line is more than words: the
 * share browser's path, whose names can be pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    subtitleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        colors = colors,
        title = {
            Column(
                when {
                    onTitleClick == null -> Modifier
                    // A television draws its focus ring inside the target, and here that
                    // is over the words. The target grows by the ring's room on every
                    // side; the title itself stays where it was.
                    isTv -> Modifier
                        .offset(x = -TitleFocusRoom)
                        .clickable(onClick = onTitleClick)
                        .padding(horizontal = TitleFocusRoom, vertical = TitleFocusRoom / 2)
                    else -> Modifier.clickable(onClick = onTitleClick)
                }
            ) {
                Text(
                    title,
                    style = if (subtitle != null || subtitleContent != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitleContent != null) {
                    subtitleContent()
                } else if (subtitle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Weighted so a long subtitle ellipsizes before the caret does.
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (onTitleClick != null) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            // Not on a television: its remote has a Back key, and the arrow
            // would be the first stop on every screen — one OK from leaving it.
            if (onBack != null && !isTv) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions
    )
}

/** Between a clickable title's words and the focus ring a television draws around them. */
private val TitleFocusRoom = 8.dp
