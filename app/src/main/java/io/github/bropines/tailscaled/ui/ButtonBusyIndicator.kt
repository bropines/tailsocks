package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * The mark a button shows while its action runs, in place of its icon or beside its label.
 *
 * LoadingIndicator draws its shape in 38/48 of its box, so a box given the size of the ring
 * it replaces yields a shape a fifth smaller — at 14 dp, one that no longer reads. [diameter]
 * is the shape's own size, the size the old ring was: the box is made larger to match, and
 * the indicator takes exactly [diameter] in the layout, so a button does not change size
 * when it appears. [color] is the old ring's colour, which each button chose for its own
 * fill (and disabled state).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ButtonBusyIndicator(
    diameter: Dp,
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        LoadingIndicator(
            modifier = Modifier.requiredSize(diameter * (LoadingIndicatorDefaults.ContainerWidth / LoadingIndicatorDefaults.IndicatorSize)),
            color = color,
        )
    }
}
