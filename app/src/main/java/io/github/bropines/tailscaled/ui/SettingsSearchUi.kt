package io.github.bropines.tailscaled.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.CompactSearchBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The search of Settings on the screen (ui/SettingsSearch.kt has what it shows): the field
 * under the top bar of the list of sections, the results that take the list's place while
 * something is typed, and the mark a row of a page wears for a moment after a result has
 * opened the page on it.
 */

// ─── The field ───────────────────────────────────────────────────────────────────────────

/**
 * [bar], and under it the search field — where the device is offered one, and only over the
 * list of sections ([shown]). Stands in a Scaffold's top bar slot, so what is under it starts
 * below the field.
 *
 * Back, while the search is in use, puts the search away first: the query is cleared and
 * the list of sections comes back. Only the rendering in use ([live]) takes the key.
 */
@Composable
internal fun SettingsSearchBar(state: SettingsSearchState, shown: Boolean, live: Boolean, bar: @Composable () -> Unit) {
    Column {
        bar()
        if (!shown || !state.offered) return@Column
        val focus = LocalFocusManager.current
        val close = {
            state.query = ""
            focus.clearFocus()
        }
        BackHandler(enabled = live && state.active, onBack = close)
        CompactSearchBar(
            value = state.query,
            onValueChange = { state.query = it },
            placeholderText = stringResource(R.string.search_settings_hint),
            modifier = Modifier
                .readableWidth()
                .padding(horizontal = 16.dp)
                .onFocusChanged { if (live) state.focused = it.isFocused }
                // A keyboard's keys: Down steps into the results, Enter opens the first of them,
                // Escape puts the search away. A text field would keep all three for itself.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                        Key.Enter, Key.NumPadEnter -> state.results?.takeIf { it.query == state.query }?.first?.let {
                            focus.clearFocus()
                            state.open(it.id)
                            true
                        } ?: false
                        Key.Escape -> {
                            close()
                            true
                        }
                        else -> false
                    }
                },
            // The keyboard's own Search key only puts the keyboard away: the results are already there.
            onSearch = {}
        )
    }
}

// ─── The results ─────────────────────────────────────────────────────────────────────────

/**
 * What the search found, in the place of the list of sections: a line that says how the
 * query was understood when that needs saying, then the results, each one stop for a
 * keyboard's arrows.
 */
@Composable
internal fun SettingsSearchResults(state: SettingsSearchState, modifier: Modifier = Modifier) {
    val results = state.results
    val focus = LocalFocusManager.current
    // Shown again after a page was visited: what is on a page may have changed there.
    LaunchedEffect(Unit) { state.stale++ }
    ReadableWidth {
        // A list of its own for every query: a lazy list holds on to the row it had at its top, and
        // would show the answer to the next letter from wherever that row now stands in it. The same
        // query keeps its place: Back from a page returns to the result it was opened from.
        key(results?.query) {
        LazyColumn(
            state = state.listFor(results?.query),
            modifier = modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (results == null) return@LazyColumn
            val note = when {
                results.query.isBlank() -> null
                results.empty -> R.string.search_none to results.query.trim()
                results.readAs != null -> R.string.search_read_as to results.readAs
                results.closest -> R.string.search_closest to ""
                else -> null
            }
            if (note != null) {
                item(key = "note") {
                    Text(
                        stringResource(note.first, note.second),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                    )
                }
            }
            results.groups.forEachIndexed { index, group ->
                if (group.heading != 0) {
                    item(key = "heading${group.heading}") {
                        Text(
                            stringResource(group.heading),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = if (index == 0) 0.dp else 12.dp, bottom = 4.dp)
                        )
                    }
                }
                // An entry may stand in one group only, so its id is a key for the whole list.
                items(group.rows, key = { it.id }) { row ->
                    SettingsSearchResult(row) {
                        // Away from the field, which puts its keyboard down. A row reached with the arrows
                        // keeps the focus: the next arrow goes on from it.
                        if (state.focused) focus.clearFocus()
                        state.open(row.id)
                    }
                }
            }
        }
        }
    }
}

/** One result: where it lives, what it is called, one line of what it does. The whole of it is one button. */
@Composable
private fun SettingsSearchResult(row: SettingsSearchRow, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val source = remember { MutableInteractionSource() }
    val mark = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        interactionSource = source,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).tvFocusRing(source, shape)
    ) {
        ListItem(
            overlineContent = {
                Text(
                    buildAnnotatedString {
                        append(marked(row.trail, row.trailMarks, mark))
                        // What was typed stands nowhere else in the row: the word it was found by.
                        if (row.matched != null) {
                            append(" · ")
                            append(marked(row.matched, row.matchedMarks, mark))
                        }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    marked(row.summary, row.summaryMarks, mark),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            // Not on its page now: the line under the title says when it is, and the icon is that of a row switched off.
            leadingContent = { Icon(row.icon, null, tint = if (row.unavailable) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(marked(row.title, row.titleMarks, mark), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

private fun marked(text: String, marks: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (range in marks) addStyle(style, range.first.coerceIn(0, text.length), (range.last + 1).coerceIn(0, text.length))
}

// ─── The row a result opens on ───────────────────────────────────────────────────────────

/** The row the page under it is to bring into view and mark; null when there is none. */
private val LocalSettingTarget = compositionLocalOf<SettingTarget?> { null }

/** How long a row that could be marked is waited for before the one that brings it is marked instead. */
private const val ROW_WAIT_MS = 600L

/**
 * A page of Settings, [content], as the place a result may have been opened on: the rows in it
 * that carry [settingAnchor] learn which of them is looked for. Only the rendering of [section]
 * that is in use ([live]) is told — not a copy of it fading out or lying under a back gesture.
 *
 * A row that is not on the page after all — the page decides that from more than the search
 * can read — gives way to the row that brings it, and failing that the page is simply open.
 */
@Composable
internal fun SettingsRowTarget(state: SettingsSearchState, section: String?, live: Boolean, content: @Composable () -> Unit) {
    val target = state.target?.takeIf { live && it.section == section }
    if (target != null) {
        LaunchedEffect(target, target.title) {
            delay(ROW_WAIT_MS)
            if (target.found) return@LaunchedEffect
            if (target.fallback != null && target.title != target.fallback) target.title = target.fallback
            else if (state.target === target) state.target = null
        }
        // Left before the mark faded: coming back to the page later does not mark the row again.
        DisposableEffect(target) {
            onDispose { if (state.target === target) state.target = null }
        }
    }
    CompositionLocalProvider(LocalSettingTarget provides target) {
        content()
        // The mark has faded: the page is an ordinary page again.
        val done = target?.found == true && target.marked
        if (done) LaunchedEffect(target) { if (state.target === target) state.target = null }
    }
}

/**
 * Makes the element the row titled [title]: when a search result is opened on that title, the
 * page scrolls it into view, with some room around it, and marks it for a moment — a tint
 * and a thin line in the theme's colour that fade in and out. Nothing otherwise.
 *
 * The rows of the settings family carry it themselves (ui/SettingsComponents.kt); a block
 * that is no such row — a group of chips, a button — is given it where it is laid out, with
 * the title it shows. Goes after the element's outer padding, so the mark hugs the element;
 * [outset] draws it that much wider, for a block of bare text with no surface of its own, and
 * [shape] gives it the corners of what it marks.
 */
@Composable
fun Modifier.settingAnchor(title: String, outset: Dp = 0.dp, shape: CornerBasedShape = MaterialTheme.shapes.medium): Modifier {
    val target = LocalSettingTarget.current
    if (target == null || target.title != title) return this
    val requester = remember { BringIntoViewRequester() }
    val shown = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tint = MaterialTheme.colorScheme.primary
    LaunchedEffect(target) {
        target.found = true
        // Composed now, laid out a frame later, and a card may still be unfolding after that.
        repeat(3) { withFrameNanos { } }
        val around = size.height.coerceAtLeast(1) * 1.5f
        // A job of its own: a page with nowhere to scroll to gives the request up by cancelling it,
        // and that is no reason to leave the row unmarked.
        launch { requester.bringIntoView(Rect(0f, -around, size.width.toFloat(), size.height + around)) }
        shown.animateTo(1f, tween(MARK_IN_MS))
        delay(MARK_HOLD_MS)
        shown.animateTo(0f, tween(MARK_OUT_MS))
        target.marked = true
    }
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .drawWithContent {
            drawContent()
            val alpha = shown.value
            if (alpha > 0f) {
                val out = outset.toPx()
                val from = Offset(-out, -out)
                val area = Size(this.size.width + out * 2, this.size.height + out * 2)
                val radius = CornerRadius(shape.topStart.toPx(area, this))
                drawRoundRect(tint.copy(alpha = 0.14f * alpha), from, area, radius)
                drawRoundRect(tint.copy(alpha = 0.75f * alpha), from, area, radius, style = Stroke(1.5.dp.toPx()))
            }
        }
}

// A calm mark: it comes, stays long enough for the eye to land, and goes.
private const val MARK_IN_MS = 300
private const val MARK_HOLD_MS = 1600L
private const val MARK_OUT_MS = 900
