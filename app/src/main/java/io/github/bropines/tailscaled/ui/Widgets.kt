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
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
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
// The switch: 1×1 round toggle, 2×1/4×1 bar, 2×2 card
// ----------------------------------------------------------------
class ServiceToggleWidget : GlanceAppWidget() {

    companion object {
        private val TINY = DpSize(48.dp, 48.dp)
        private val BAR = DpSize(110.dp, 48.dp)
        private val CARD = DpSize(110.dp, 100.dp)
        private val TALL = DpSize(110.dp, 150.dp)
    }

    override val stateDefinition = WidgetStateDef
    override val sizeMode = SizeMode.Responsive(setOf(TINY, BAR, CARD, TALL))

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
            else -> ServiceCard(model, extras = size.height >= TALL.height)
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
                StatusLine(model)
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

    @Composable
    private fun ServiceCard(model: ServiceWidgetModel, extras: Boolean) {
        Column(
            modifier = widgetSurface()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            horizontalAlignment = Alignment.Horizontal.Start
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "TailSocks",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = model.profile,
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )
                }
                if (extras) {
                    IconAction(
                        R.drawable.ic_widget_refresh, model.refreshLabel,
                        actionRunCallback<RefreshAllActionCallback>(), GlanceTheme.colors.onSurfaceVariant
                    )
                }
            }
            Spacer(GlanceModifier.height(4.dp))
            StatusLine(model)
            if (extras && model.exitLabel.isNotEmpty()) {
                Text(
                    text = model.exitLabel,
                    style = TextStyle(color = GlanceTheme.colors.tertiary, fontSize = 12.sp),
                    maxLines = 1
                )
            }
            if (extras && model.tailnetIp.isNotEmpty()) {
                Row(
                    modifier = GlanceModifier.clickable(
                        actionRunCallback<CopyTailnetIpActionCallback>(actionParametersOf(paramTailnetIp to model.tailnetIp))
                    ),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = model.tailnetIp,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        maxLines = 1
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_copy),
                        contentDescription = model.copyLabel,
                        modifier = GlanceModifier.size(14.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Button(
                text = model.toggleLabel,
                onClick = actionRunCallback<ToggleServiceActionCallback>(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (model.on) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                    contentColor = if (model.on) GlanceTheme.colors.onError else GlanceTheme.colors.onPrimary
                ),
                modifier = GlanceModifier.fillMaxWidth().height(40.dp),
                maxLines = 1
            )
        }
    }

    @Composable
    private fun StatusLine(model: ServiceWidgetModel) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(text = "●", style = TextStyle(color = toneColor(model.status.tone), fontSize = 12.sp))
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = model.statusText,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
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
// The exit node: as many rows as the widget's size holds
// ----------------------------------------------------------------
class ExitNodeToggleWidget : GlanceAppWidget() {

    private companion object {
        const val PADDING = 12
        const val HEADER = 44
        const val ROW = 38
        const val GAP = 6
    }

    override val stateDefinition = WidgetStateDef
    // Exact: the row count follows the real size, so the list is never cut through a row.
    override val sizeMode = SizeMode.Exact

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
        val height = LocalSize.current.height.value.toInt()
        val fit = ((height - 2 * PADDING - HEADER) / (ROW + GAP)).coerceAtLeast(0)

        Column(
            modifier = widgetSurface()
                .padding(horizontal = PADDING.dp, vertical = PADDING.dp)
                .clickable(actionStartActivity<MainActivity>()),
            horizontalAlignment = Alignment.Horizontal.Start
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(HEADER.dp),
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
                            fontSize = 12.sp,
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

            // Direct first, then Best, then the nodes (the selected one leads them).
            var room = fit
            if (room > 0) {
                Spacer(GlanceModifier.height(GAP.dp))
                ExitButton("${if (model.active) "○" else "●"} ${model.directLabel}", selected = !model.active, dimmed = false, onClick = selectAction("", "", ""))
                room--
            }
            if (room > 0 && model.bestLabel.isNotEmpty()) {
                Spacer(GlanceModifier.height(GAP.dp))
                ExitButton("★ ${model.bestLabel}", selected = false, dimmed = false, onClick = actionRunCallback<SelectBestExitNodeActionCallback>())
                room--
            }
            model.rows.take(room).forEach { row ->
                Spacer(GlanceModifier.height(GAP.dp))
                ExitButton(
                    "${if (row.selected) "●" else "○"} ${row.name}",
                    selected = row.selected,
                    dimmed = row.online == false,
                    onClick = selectAction(row.id, row.ip, row.name)
                )
            }
            if (model.rows.isEmpty() && room > 0) {
                Text(
                    text = model.emptyText,
                    style = TextStyle(color = GlanceTheme.colors.outline, fontSize = 12.sp),
                    modifier = GlanceModifier.padding(vertical = 8.dp),
                    maxLines = 2
                )
            }
        }
    }

    private fun selectAction(id: String, ip: String, name: String): Action =
        actionRunCallback<SelectExitNodeActionCallback>(
            actionParametersOf(paramExitNodeId to id, paramExitNodeIp to ip, paramExitNodeName to name)
        )

    @Composable
    private fun ExitButton(text: String, selected: Boolean, dimmed: Boolean, onClick: Action) {
        Button(
            text = text,
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = when {
                    selected -> GlanceTheme.colors.primary
                    dimmed -> GlanceTheme.colors.surfaceVariant
                    else -> GlanceTheme.colors.secondaryContainer
                },
                contentColor = when {
                    selected -> GlanceTheme.colors.onPrimary
                    dimmed -> GlanceTheme.colors.outline
                    else -> GlanceTheme.colors.onSecondaryContainer
                }
            ),
            modifier = GlanceModifier.fillMaxWidth().height(ROW.dp),
            maxLines = 1
        )
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
