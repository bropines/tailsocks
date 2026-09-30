package io.github.bropines.tailscaled.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The app's three corner radii, reached through `MaterialTheme.shapes`:
 *  - `small` (8dp): badges, tags, chips, small icon tiles, inline code;
 *  - `medium` (12dp): whatever sits inside a card or a sheet — fields,
 *    buttons, rows, tiles;
 *  - `large` (16dp): cards and banners on the screen background.
 *
 * Pills, bars and round things take `CircleShape`. `extraLarge` stays
 * Material's 28dp: dialogs and sheets use it, and so does the status card,
 * the one hero surface. The three values equal Material 3's defaults, so its
 * own components keep the corners they had.
 */
val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)
