package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

/*
 * The shared adaptive pieces on their own, in every window size: what a screen built on
 * them looks like before it has any content of its own. The reference for the per-screen
 * work; Peers (TabletPeersPreviews.kt) is the same pieces with real content.
 */

@Composable
private fun SampleCard(index: Int, lines: Int) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Card $index", style = MaterialTheme.typography.titleMedium)
            repeat(lines) { Text("Line ${it + 1}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Cards of uneven heights: one column on a phone, two or three from medium up. */
@PreviewTest @WindowSizes @Composable
fun AdaptiveCardColumns() = AdaptiveShowcase {
    Scaffold(topBar = { AppTopBar(title = "CardColumns", onBack = {}) }) { padding ->
        val window = rememberWindowLayout()
        CardColumns(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = window.margin),
        ) {
            listOf(3, 6, 2, 4, 1, 5, 2).forEachIndexed { i, lines -> SampleCard(i + 1, lines) }
        }
    }
}

/** A Settings-like list-detail: a flat pane with a hairline, the detail a page of its own. */
@PreviewTest @WindowSizes @Composable
fun AdaptiveListDetailFlat() = AdaptiveShowcase {
    Scaffold(topBar = { AppTopBar(title = "ListDetailLayout · FLAT", onBack = {}) }) { padding ->
        ListDetailLayout(
            modifier = Modifier.padding(padding).fillMaxSize(),
            style = PaneStyle.FLAT,
            list = { Column(Modifier.padding(16.dp)) { repeat(6) { SampleCard(it + 1, 1); androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) } } },
            detail = { Column(Modifier.padding(16.dp)) { SampleCard(1, 8) } },
        )
    }
}

/** Nothing picked and nothing to pick: the pane says so instead of standing empty. */
@PreviewTest @WindowSizes @Composable
fun AdaptiveListDetailEmpty() = AdaptiveShowcase {
    Scaffold(topBar = { AppTopBar(title = "ListDetailLayout · RAISED", onBack = {}) }) { padding ->
        ListDetailLayout(
            modifier = Modifier.padding(padding).fillMaxSize(),
            list = { Column(Modifier.padding(16.dp)) { SampleCard(1, 2) } },
            detail = { PaneEmptyState(Icons.Default.Settings, "Nothing picked") },
        )
    }
}
