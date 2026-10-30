package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ExitNodeApply
import io.github.bropines.tailscaled.core.ExitNodeSuggestion
import io.github.bropines.tailscaled.core.ProxyState
import io.github.bropines.tailscaled.core.TailscaledService

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The two home-screen widgets. They draw their stored model (WidgetModels.kt) and nothing
// else: no daemon, preference or resource lookup happens inside a composition, because a
// Glance session recomposes on every update. WidgetUpdates builds and stores the models.

val paramExitNodeId = ActionParameters.Key<String>("exit_node_id")
val paramExitNodeIp = ActionParameters.Key<String>("exit_node_ip")
val paramExitNodeName = ActionParameters.Key<String>("exit_node_name")
val paramTailnetIp = ActionParameters.Key<String>("tailnet_ip")

/** The widget's card: the launcher's background and corner radius on Android 12+, a rounded shape of ours below. */
@Composable
private fun widgetSurface(): GlanceModifier {
    val base = GlanceModifier.fillMaxSize().appWidgetBackground()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        base.background(ImageProvider(R.drawable.widget_round_bg), colorFilter = ColorFilter.tint(GlanceTheme.colors.widgetBackground))
    }
}

@Composable
private fun toneColor(tone: WidgetTone): ColorProvider = when (tone) {
    WidgetTone.ON -> GlanceTheme.colors.primary
    WidgetTone.OFF -> GlanceTheme.colors.outline
    WidgetTone.BUSY -> GlanceTheme.colors.tertiary
    WidgetTone.WARN -> GlanceTheme.colors.error
}

/** The round toggle's fill and glyph for [tone]. */
@Composable
private fun toneFill(tone: WidgetTone): Pair<ColorProvider, ColorProvider> = when (tone) {
    WidgetTone.ON -> GlanceTheme.colors.primary to GlanceTheme.colors.onPrimary
    WidgetTone.OFF -> GlanceTheme.colors.surfaceVariant to GlanceTheme.colors.onSurfaceVariant
    WidgetTone.BUSY -> GlanceTheme.colors.tertiaryContainer to GlanceTheme.colors.onTertiaryContainer
    WidgetTone.WARN -> GlanceTheme.colors.errorContainer to GlanceTheme.colors.onErrorContainer
}

@Composable
private fun LoadingContent() {
    Box(modifier = widgetSurface(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = GlanceTheme.colors.primary)
    }
}

/** A small icon button: the refresh in a header. */
@Composable
private fun IconAction(icon: Int, label: String, onClick: Action, tint: ColorProvider) {
    Box(
        modifier = GlanceModifier.size(32.dp).clickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(tint)
        )
    }
}

/** The power toggle as a disc, coloured by the connection's tone. */
@Composable
private fun RoundToggle(model: ServiceWidgetModel, diameter: Int) {
    val (fill, glyph) = toneFill(model.status.tone)
    Box(
        modifier = GlanceModifier
            .size(diameter.dp)
            .background(ImageProvider(R.drawable.widget_circle), colorFilter = ColorFilter.tint(fill))
            .clickable(actionRunCallback<ToggleServiceActionCallback>()),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_power),
            contentDescription = "${model.statusText}. ${model.toggleLabel}",
            modifier = GlanceModifier.size((diameter / 2).dp),
            colorFilter = ColorFilter.tint(glyph)
        )
    }
}

/** A stored model, read once outside the composition: none means the widget asks for one. */
private suspend fun requestModelIfMissing(context: Context, id: GlanceId, decode: (String?) -> Any?) {
    val stored = runCatching { getAppWidgetState(context, WidgetStateDef, id)[WidgetStateKeys.MODEL] }.getOrNull()
    if (decode(stored) == null) WidgetUpdates.request(context)
}

private suspend fun toast(text: String, context: Context) = withContext(Dispatchers.Main) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

// ----------------------------------------------------------------
// The switch: 1×1 round toggle, 2×1/4×1 bar, 2×2 card, larger cards
// ----------------------------------------------------------------
class ServiceToggleWidget : GlanceAppWidget() {

    companion object {
        private val TINY = DpSize(48.dp, 48.dp)
        private val BAR = DpSize(110.dp, 48.dp)
        private val CARD = DpSize(110.dp, 100.dp)
        private val DETAILED = DpSize(110.dp, 150.dp)
        private val TALL = DpSize(110.dp, 190.dp)
        private val WIDE = DpSize(250.dp, 170.dp)
    }

    override val stateDefinition = WidgetStateDef
    // Each layout is drawn into whatever box the launcher has, so a breakpoint is the height
    // its content needs, not a hope: below it the next smaller layout is used.
    override val sizeMode = SizeMode.Responsive(setOf(TINY, BAR, CARD, DETAILED, TALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        requestModelIfMissing(context, id, ::decodeServiceModel)
        provideContent {
            val encoded = currentState(WidgetStateKeys.MODEL)
            val model = remember(encoded) { decodeServiceModel(encoded) }
            GlanceTheme {
                if (model == null) LoadingContent() else ServiceContent(model)
            }
        }
    }

    @Composable
    private fun ServiceContent(model: ServiceWidgetModel) {
        val size = LocalSize.current
        when {
            size.width < BAR.width -> ServiceTiny(model)
            size.height < CARD.height -> ServiceBar(model)
            size.width >= WIDE.width && size.height >= WIDE.height -> ServiceWide(model)
            size.height >= TALL.height -> ServiceTall(model)
            else -> ServiceCard(model, details = size.height >= DETAILED.height)
        }
    }

    @Composable
    private fun ServiceTiny(model: ServiceWidgetModel) {
        Box(
            modifier = GlanceModifier.fillMaxSize().appWidgetBackground(),
            contentAlignment = Alignment.Center
        ) {
            RoundToggle(model, diameter = 48)
        }
    }

    @Composable
    private fun ServiceBar(model: ServiceWidgetModel) {
        Row(
            modifier = widgetSurface()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                StatusLine(model, 14)
                Text(
                    text = model.profile,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    maxLines = 1
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            RoundToggle(model, diameter = 36)
        }
    }

    /** A small card: the state, the profile, with room the details, and the button. */
    @Composable
    private fun ServiceCard(model: ServiceWidgetModel, details: Boolean) {
        Column(
            modifier = widgetSurface()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            StatusLine(model, 18)
            Text(
                text = model.profile,
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            if (details && (model.exitLabel.isNotEmpty() || model.tailnetIp.isNotEmpty())) {
                Spacer(GlanceModifier.defaultWeight())
                Details(model)
            }
            Spacer(GlanceModifier.defaultWeight())
            ToggleButton(model, height = 40)
        }
    }

    /**
     * A 2×2 and taller: the state large, the profile under it, the details in the middle and a
     * tall button; what is left over is shared between the groups rather than pooled in one gap.
     */
    @Composable
    private fun ServiceTall(model: ServiceWidgetModel) {
        Column(
            modifier = widgetSurface()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            BrandRow(model)
            Spacer(GlanceModifier.defaultWeight())
            StatusLine(model, 22)
            Text(
                text = model.profile,
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            if (model.exitLabel.isNotEmpty() || model.tailnetIp.isNotEmpty()) {
                Spacer(GlanceModifier.defaultWeight())
                Details(model)
            }
            Spacer(GlanceModifier.defaultWeight())
            ToggleButton(model, height = 48)
        }
    }

    /** A wide card: the same story on the left, the round toggle large on the right. */
    @Composable
    private fun ServiceWide(model: ServiceWidgetModel) {
        Row(
            modifier = widgetSurface()
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                BrandRow(model)
                Spacer(GlanceModifier.defaultWeight())
                StatusLine(model, 24)
                Text(
                    text = model.profile,
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                Spacer(GlanceModifier.defaultWeight())
                Details(model)
            }
            Spacer(GlanceModifier.width(16.dp))
            RoundToggle(model, diameter = 72)
        }
    }

    /** "TailSocks" and the refresh button, quiet, above the state. */
    @Composable
    private fun BrandRow(model: ServiceWidgetModel) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = "TailSocks",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
            IconAction(
                R.drawable.ic_widget_refresh, model.refreshLabel,
                actionRunCallback<RefreshAllActionCallback>(), GlanceTheme.colors.onSurfaceVariant
            )
        }
    }

    /** The exit node and the tailnet address (tap to copy), when there are any. */
    @Composable
    private fun Details(model: ServiceWidgetModel) {
        Column {
            if (model.exitLabel.isNotEmpty()) {
                Text(
                    text = model.exitLabel,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                    maxLines = 1
                )
            }
            if (model.tailnetIp.isNotEmpty()) {
                Row(
                    modifier = GlanceModifier.padding(top = 2.dp).clickable(
                        actionRunCallback<CopyTailnetIpActionCallback>(actionParametersOf(paramTailnetIp to model.tailnetIp))
                    ),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = model.tailnetIp,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                        maxLines = 1
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_copy),
                        contentDescription = model.copyLabel,
                        modifier = GlanceModifier.size(16.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            }
        }
    }

    @Composable
    private fun ToggleButton(model: ServiceWidgetModel, height: Int) {
        Button(
            text = model.toggleLabel,
            onClick = actionRunCallback<ToggleServiceActionCallback>(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (model.on) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                contentColor = if (model.on) GlanceTheme.colors.onError else GlanceTheme.colors.onPrimary
            ),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.fillMaxWidth().height(height.dp),
            maxLines = 1
        )
    }

    /** The coloured dot and the state, at [sizeSp]. */
    @Composable
    private fun StatusLine(model: ServiceWidgetModel, sizeSp: Int) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(text = "●", style = TextStyle(color = toneColor(model.status.tone), fontSize = (sizeSp * 2 / 3).sp))
            Spacer(GlanceModifier.width((sizeSp / 3).dp))
            Text(
                text = model.statusText,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = sizeSp.sp,
                    fontWeight = if (sizeSp >= 18) FontWeight.Bold else FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * The system's update — the widget placed, the 30-minute period, a locale change — repaints
 * from what the app knows without asking the daemon. Glance's own onUpdate would redraw the
 * stored model as it is, stale after a reboot or a killed process.
 */
class ServiceToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServiceToggleWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) =
        WidgetUpdates.repaintFromSystem(this, context)
}

// ----------------------------------------------------------------
// The exit node: a scrolling list under a fixed header
// ----------------------------------------------------------------
class ExitNodeToggleWidget : GlanceAppWidget() {

    private companion object {
        const val ID_DIRECT = 1L
        const val ID_BEST = 2L
        /** Node rows sit above the two fixed ones, whatever their ID hashes to. */
        const val ID_NODES = 0x1_0000_0000L
    }

    override val stateDefinition = WidgetStateDef
    // The list scrolls and fills whatever height the launcher gives it, so the layout does
    // not depend on the size the launcher reports — HyperOS scales widgets, and a row count
    // worked out from the reported size left one node and a dead band in a 2×2.
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        requestModelIfMissing(context, id, ::decodeExitModel)
        provideContent {
            val encoded = currentState(WidgetStateKeys.MODEL)
            val model = remember(encoded) { decodeExitModel(encoded) }
            GlanceTheme {
                if (model == null) LoadingContent() else ExitContent(model)
            }
        }
    }

    @Composable
    private fun ExitContent(model: ExitWidgetModel) {
        Column(
            modifier = widgetSurface()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = model.title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = model.subtitle,
                        style = TextStyle(
                            color = if (model.active) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }
                IconAction(
                    R.drawable.ic_widget_refresh, model.refreshLabel,
                    actionRunCallback<RefreshAllActionCallback>(), GlanceTheme.colors.onSurfaceVariant
                )
            }

            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                item(ID_DIRECT) {
                    ExitRowView(
                        title = "${if (model.active) "○" else "●"}  ${model.directLabel}",
                        detail = null,
                        selected = !model.active,
                        dimmed = false,
                        onClick = selectAction("", "", "")
                    )
                }
                if (model.bestLabel.isNotEmpty()) {
                    item(ID_BEST) {
                        ExitRowView("★  ${model.bestLabel}", null, selected = false, dimmed = false,
                            onClick = actionRunCallback<SelectBestExitNodeActionCallback>())
                    }
                }
                items(model.rows, itemId = { ID_NODES + it.id.hashCode() }) { row ->
                    ExitRowView(
                        title = "${if (row.selected) "●" else "○"}  ${row.name}",
                        detail = if (row.online == false) "${row.ip} · ${model.offlineLabel}" else row.ip,
                        selected = row.selected,
                        dimmed = row.online == false,
                        onClick = selectAction(row.id, row.ip, row.name)
                    )
                }
                if (model.rows.isEmpty()) {
                    item(ID_NODES - 1) {
                        Text(
                            text = model.emptyText,
                            style = TextStyle(color = GlanceTheme.colors.outline, fontSize = 13.sp),
                            modifier = GlanceModifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }

    private fun selectAction(id: String, ip: String, name: String): Action =
        actionRunCallback<SelectExitNodeActionCallback>(
            actionParametersOf(paramExitNodeId to id, paramExitNodeIp to ip, paramExitNodeName to name)
        )

    /** One row: a rounded tile, its name and, for a node, its address under it. */
    @Composable
    private fun ExitRowView(title: String, detail: String?, selected: Boolean, dimmed: Boolean, onClick: Action) {
        val fill = when {
            selected -> GlanceTheme.colors.primary
            dimmed -> GlanceTheme.colors.surfaceVariant
            else -> GlanceTheme.colors.secondaryContainer
        }
        val ink = when {
            selected -> GlanceTheme.colors.onPrimary
            dimmed -> GlanceTheme.colors.outline
            else -> GlanceTheme.colors.onSecondaryContainer
        }
        val tile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GlanceModifier.background(fill).cornerRadius(12.dp)
        } else {
            GlanceModifier.background(ImageProvider(R.drawable.widget_row_bg), colorFilter = ColorFilter.tint(fill))
        }
        // The gap under each row is padding of its own item: a lazy list has no spacing.
        Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Column(
                modifier = tile.fillMaxWidth()
                    .height(if (detail == null) 44.dp else 54.dp)
                    .padding(horizontal = 14.dp)
                    .clickable(onClick),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = title,
                    style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = TextStyle(color = ink, fontSize = 12.sp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** See [ServiceToggleWidgetReceiver]. */
class ExitNodeToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExitNodeToggleWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) =
        WidgetUpdates.repaintFromSystem(this, context)
}

// ----------------------------------------------------------------
// Action callbacks
// ----------------------------------------------------------------

/** The refresh button: a pass now, the exit-node list read afresh, every instance repainted. */
class RefreshAllActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetUpdates.refresh(context, force = true, live = WidgetUpdates.Live.NOW)
    }
}

/**
 * Start/Stop, decided on what is happening when tapped — never on what an older render
 * showed — and shown as Starting…/Stopping… on every instance until the service gets there.
 */
class ToggleServiceActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext
        val start = withContext(Dispatchers.IO) { WidgetUpdates.beginToggle(app) }
        WidgetUpdates.refresh(app, live = WidgetUpdates.Live.NEVER)

        ProxyState.setUserState(app, start)
        val intent = Intent(app, TailscaledService::class.java).apply {
            action = if (start) "START_ACTION" else "STOP_ACTION"
        }
        try {
            // Foreground for both, as the tile does: the stop path enters the foreground first,
            // and a plain start of a service that is not running is refused from the background.
            ContextCompat.startForegroundService(app, intent)
        } catch (e: Exception) {
            Log.w("Widgets", "The service did not take the widget's ${intent.action}: ${e.message}")
            WidgetUpdates.cancelToggle(app)
            WidgetUpdates.refresh(app, live = WidgetUpdates.Live.NEVER)
        }
    }
}

class SelectExitNodeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext
        val id = parameters[paramExitNodeId].orEmpty()
        val ip = parameters[paramExitNodeIp].orEmpty()
        val name = parameters[paramExitNodeName].orEmpty().ifEmpty { ip }

        val result = withContext(Dispatchers.IO) { ExitNodeApply.select(app, id, ip) }
        val res = TailscaledService.localized(app)
        val message = when (result) {
            ExitNodeApply.Result.Applied ->
                if (ip.isEmpty()) res.getString(R.string.widget_toast_exit_disabled)
                else res.getString(R.string.widget_toast_exit_routing_format, name)
            ExitNodeApply.Result.Incomplete -> {
                // Only a row drawn by an older build can say this: draw the list again.
                WidgetUpdates.refresh(app, live = WidgetUpdates.Live.NOW)
                res.getString(R.string.widget_toast_exit_unknown)
            }
            is ExitNodeApply.Result.Refused -> res.getString(R.string.settings_local_api_error_format, result.message)
        }
        toast(message, app)
    }
}

/**
 * "Best exit node" on the widget: asks the daemon for its recommendation and selects that
 * node exactly as its own button would; with none to be had, a toast says why. Asked when
 * tapped, not when drawn: a widget is rendered on its own schedule and the answer moves.
 */
class SelectBestExitNodeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (val best = withContext(Dispatchers.IO) { ExitNodeSuggestion.fetch() }) {
            is ExitNodeSuggestion.Outcome.Suggested -> SelectExitNodeActionCallback().onAction(
                context, glanceId,
                actionParametersOf(paramExitNodeId to best.id, paramExitNodeIp to best.ip, paramExitNodeName to best.name)
            )
            is ExitNodeSuggestion.Outcome.Unavailable ->
                toast(TailscaledService.localized(context).getString(bestExitNodeReasonRes(best.reason)), context.applicationContext)
        }
    }
}

/** The tailnet address on the switch: to the clipboard, with the app's usual toast. */
class CopyTailnetIpActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ip = parameters[paramTailnetIp].orEmpty()
        if (ip.isEmpty()) return
        val app = context.applicationContext
        val res = TailscaledService.localized(app)
        withContext(Dispatchers.Main) {
            app.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(res.getString(R.string.widget_copy_ip), ip))
            Toast.makeText(app, res.getString(R.string.copied_to_clipboard, ip), Toast.LENGTH_SHORT).show()
        }
    }
}
