package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.ExitNodeSuggestion.Outcome
import io.github.bropines.tailscaled.core.ExitNodeSuggestion.Reason
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

// The "best exit node" row in every state it can show. The pickers it sits in
// are bottom sheets, which the renderer cannot draw, so the row is drawn alone.

@PreviewTest
@Preview(name = "phone", device = "spec:width=393dp,height=852dp,dpi=420")
@Preview(name = "phone-ru", device = "spec:width=393dp,height=852dp,dpi=420", locale = "ru")
@Preview(name = "font200", device = "spec:width=393dp,height=852dp,dpi=420", fontScale = 2f)
@Composable
fun BestExitNodeRowPreview() = TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
    Surface {
        Column(Modifier.padding(vertical = 16.dp)) {
            val strings = bestExitNodeStrings()
            listOf(
                null,
                Outcome.Suggested("n3", "100.94.210.8", "exit-frankfurt", "Frankfurt, Germany"),
                Outcome.Suggested("n4", "100.66.18.77", "exit-helsinki", null),
                Outcome.Unavailable(Reason.NO_RELAY_YET),
                Outcome.Unavailable(Reason.NONE_RECOMMENDED),
                Outcome.Unavailable(Reason.FAILED),
                Outcome.Unavailable(Reason.NOT_RUNNING),
            ).forEach { outcome ->
                BestExitNodeRow(outcome = outcome, strings = strings, onApply = {}, onRetry = {})
            }
        }
    }
}
