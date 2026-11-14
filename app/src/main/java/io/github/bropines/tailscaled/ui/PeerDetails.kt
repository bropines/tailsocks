package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The peer details sheet: the swipe between peers, the words it resolves outside its window,
 * and PeerDetailsModal, which holds the sheet's state. A page's content is in PeerDetailsPage.kt,
 * its cards in PeerDetailCards.kt.
 */

// --- PEERS ---

/** How far the finger has to travel before the details sheet turns to the next peer. In dp:
 *  in raw pixels the same flick would turn the page on one device and not on another. */
private val PEER_SWIPE_THRESHOLD = 100.dp
/** A flick faster than this turns the page whatever distance it covered. */
private val PEER_SWIPE_FLING_VELOCITY = 400.dp
/** How much more horizontal than vertical the finger has to be before the page-turn claims
 *  the gesture. A diagonal drag is left to the sheet's own drag-to-dismiss. */
private const val PEER_SWIPE_DIRECTION_RATIO = 2f
/** How much of the drag survives when there is no peer in that direction. */
private const val PEER_SWIPE_RESISTANCE = 0.25f
/** Half a pixel of residual is where a page has visibly landed. The swipe animates raw
 *  pixels, so the library's own 0.01 default — written for a 0..1 fraction — leaves a spring
 *  running for roughly another 165 ms on a phone-width displacement, and the page turn is
 *  only committed once the spring reports done: without this the peer under the finger has
 *  arrived while the ping state, the arrows and the cards still belong to the last
 *  one. */
private const val PEER_SWIPE_SETTLE_THRESHOLD_PX = 0.5f
/** How long a copied row shows its tick before going back to the copy icon. */
private const val PEER_COPIED_ACK_MS = 1500L

/** The narrowest a details card gets in a pane before the pane holds the cards in one column. */
private val PEER_PANE_CARD_MIN_WIDTH = 340.dp

/** Material's spatial spring, told what "finished" means in pixels. Every field but the
 *  threshold is the scheme's, so the motion is still the theme's and not this file's; a spec
 *  that is not a spring (a future scheme could hand back something else) is left alone. */
private fun FiniteAnimationSpec<Float>.settlingInPixels(): FiniteAnimationSpec<Float> =
    (this as? SpringSpec<Float>)?.let {
        spring(it.dampingRatio, it.stiffness, PEER_SWIPE_SETTLE_THRESHOLD_PX)
    } ?: this

/**
 * One page of the details sheet: the peer, whether it is this device, and the one property
 * the daemon's status cannot supply — the Tailscale version the Admin API reports for it.
 * Resolved by PeersScreen once per list load and carried here as a plain value, so a page
 * sliding in under the finger reads it off the object like everything else and asks the
 * network nothing. Null when the Admin API is not set up, did not answer, or does not list
 * the peer: then the version row is not drawn at all.
 */
data class PeerPage(val peer: PeerData, val isSelf: Boolean, val version: String? = null)

/** Every word the details sheet shows, resolved in the parent composition — see
 *  wrapContextWithLocale(): inside the sheet's own window a stringResource follows the
 *  system locale, not the app's. */
internal data class PeerDetailsStrings(
    val prevPeer: String,
    val nextPeer: String,
    val sendFile: String,
    val copyAs: String,
    val tooltipDismiss: String,
    val pinging: String,
    /** "Ping: %1$s" — formatted in the page with String.format, not with stringResource:
     *  two peers are composed at once during a turn and each carries its own figure. */
    val pingResultFormat: String,
    /** What the connection card says in place of a figure on this device's own page. */
    val pingSelf: String,
    val pingSelfCaption: String,
    /** "Measure round trip to %1$s": what the connection card is, for a screen reader. */
    val pingCardFormat: String,
    /** The connection card's caption before anything has been measured: the card is the
     *  thing that measures, and this is where it says so. */
    val tapToMeasure: String,
    val copy: String,
    /** A television's twin of [copy]: the value shown large, for a row or a card's lead. */
    val showLarge: String,
    /** The in-place acknowledgement a copied row shows for a moment. */
    val copied: String,
    val expanded: String,
    val collapsed: String,
    val exitNodeSelected: String,
    val exitNodeOffered: String,
    val online: String,
    val offline: String,
    val osUnknown: String,
    /** How the peer is reached: the route chip, the route card, its explanation. */
    val path: PeerPathWords,
    val latency: String,
    /** The connection card's caption for a ping that came back with nothing. */
    val connPingFailed: String,
    /** The capabilities card's caption; what the Taildrop verdict under it is about. */
    val taildrop: String,
    val taildropOk: String,
    /** The verdict for a refusal code this file does not know and the daemon gave no words for. */
    val taildropBlocked: String,
    val taildropNoNetmap: String,
    val taildropNotRunning: String,
    val taildropMissingCap: String,
    val taildropOffline: String,
    val taildropNoPeerInfo: String,
    val taildropUnsupportedOs: String,
    val taildropNoPeerApi: String,
    val taildropOtherUser: String,
    /** This device's own sheet: the card's verdict, and the reason under the dimmed button. */
    val taildropSelfShort: String,
    val taildropSelf: String,
    /** The traffic card's caption over the sum of both byte counters. */
    val trafficTotal: String,
    val groupAddresses: String,
    val groupRoute: String,
    val groupConnection: String,
    val groupDevice: String,
    val groupCapabilities: String,
    val groupTraffic: String,
    val groupTiming: String,
    val groupOther: String
)

/** How a page sits while the sheet is turning: shifted by [dx], and — from half a page out
 *  — faded and shrunk a little, so the eye follows the page arriving at the centre rather
 *  than the one leaving. Both pages get it, so the incoming one fades up as it lands. */
private fun GraphicsLayerScope.peerPageTransform(dx: Float) {
    translationX = dx
    val halfPage = size.width * 0.5f
    val progress = if (halfPage > 0f) (abs(dx) / halfPage).coerceIn(0f, 1f) else 0f
    alpha = 1f - 0.45f * progress
    scaleX = 1f - 0.05f * progress
    scaleY = scaleX
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PeerDetailsModal(
    /**
     * The peer [offset] steps along the list from the one on screen: 0 is the page being
     * shown, -1 the page a swipe to the right brings in, +1 the page a swipe to the left
     * brings in, and null where the list ends. The sheet asks for at most two steps either
     * way — the two pages a turn has on screen at once, plus whether the arriving page
     * should carry its own arrows — and it draws nothing it was not handed.
     *
     * It takes the peers rather than bare prev/next callbacks because the neighbour has to
     * be *visible* under the finger: a callback can only be fired once the gesture is over,
     * which is exactly the blind drag this replaced.
     */
    peerAt: (offset: Int) -> PeerPage?,
    /** This device's own tailnet address, which is the near end of every latency the sheet
     *  measures. Null when the daemon has not reported a Self yet: then the connection block
     *  shows the far end alone rather than an arrow pointing at nothing. */
    selfAddress: String? = null,
    onDismiss: () -> Unit,
    onSendFileClick: (PeerData) -> Unit = {},
    /** The peer's "Copy as…" picker, opened over this sheet. Null draws no button. */
    onCopyAsClick: ((PeerData) -> Unit)? = null,
    /** Turn to a peer this sheet was handed. By the time this is called the page-turn has
     *  already carried that peer's content to the centre of the sheet. */
    onSelectPeer: (PeerData) -> Unit = {},
    /** Drawn as the detail pane beside the list (see ListDetailLayout) instead of as a sheet
     *  over it: the same pages and the same page-turn, filling the pane, and on a pane wide
     *  enough the cards in two columns. */
    inPane: Boolean = LocalInPane.current
) {
    val page = peerAt(0) ?: return
    val peer = page.peer
    val prevPage = peerAt(-1)
    val nextPage = peerAt(1)

    // Where there is no clipboard (a television) a value is shown instead of copied, large
    // and with a code where it is an address or a link: the row or lead that was chosen.
    val clipboardOffered = Feature.CLIPBOARD_ACTIONS.offered()
    // A sheet over the list on a television is a dialog that takes the remote (TvSheetDialog),
    // drawn as the pane is: the whole window, the cards in columns as far as it is wide.
    val tvDialog = isTv && !inPane
    val asPane = inPane || tvDialog
    var shownDetail by remember(peer.id) { mutableStateOf<PeerDetail?>(null) }
    val shownStrings = rememberShownValueStrings()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberFullSheetState()
    // Keyed on the peer: the sheet deliberately stays composed across a peer change (see
    // swipeOffset below), so an unkeyed pingResult would show peer A's latency on peer B.
    var pingResult by remember(peer.id) { mutableStateOf<String?>(null) }
    // Which cards are open. Not keyed on the peer: both pages of a turn draw the same set,
    // so the arriving peer lands with the cards the finger left open and nothing jumps.
    val cardsOpenAtFirst = LocalPeerCardsOpen.current
    var expandedGroups by remember { mutableStateOf(cardsOpenAtFirst) }
    val onToggleGroup: (PeerDetailGroup) -> Unit = { group ->
        expandedGroups = if (group in expandedGroups) expandedGroups - group else expandedGroups + group
    }
    // The row whose value was just copied, for as long as its tick shows. Keyed on the peer:
    // a tick must not survive onto another peer's row of the same kind.
    var copiedDetail by remember(peer.id) { mutableStateOf<PeerDetailId?>(null) }
    LaunchedEffect(copiedDetail) {
        if (copiedDetail != null) {
            delay(PEER_COPIED_ACK_MS)
            copiedDetail = null
        }
    }

    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    // Page-turn state for the horizontal peer swipe. Both pages are on screen for the whole
    // gesture — the current one at swipeOffset, the arriving one a page width further along
    // in the drag direction — so the turn is one continuous motion with nothing to swap in
    // at the end: when the current page has finished leaving, the page that was sliding in
    // is already centred, and the swap below is a rename, not a movement.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { PEER_SWIPE_THRESHOLD.toPx() }
    val flingVelocityPx = with(density) { PEER_SWIPE_FLING_VELOCITY.toPx() }
    val maxOverscrollPx = with(density) { 56.dp.toPx() }
    /** Where the current page sits, in pixels from the centre. Read in the draw phase only
     *  (inside graphicsLayer), so a drag moves the pages without recomposing them. */
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    /** Which page is sliding in alongside the current one: -1 the previous peer, +1 the
     *  next, 0 nothing (the sheet is at rest, or the list ends that way). This one *is* read
     *  in composition — it decides whether the neighbour is composed at all — so it changes
     *  a couple of times per gesture rather than every frame. */
    var incomingStep by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // Compose exposes no reduced-motion flag here; the system animator duration scale is
    // the closest thing, and 0 is how "remove animations" reaches an app. ValueAnimator
    // keeps that scale in a process-local field, so this is a plain read that also stays
    // current; only the pre-O fallback pays for a binder call, once.
    val animateSwipe = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        android.animation.ValueAnimator.areAnimatorsEnabled()
    } else {
        remember {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }
    }
    // Material's own spatial springs: the default one finishes a turn, the fast one snaps
    // back a drag that decided nothing.
    // The gesture node below is keyed on nothing at all, so the lambda it runs is the one
    // captured at the first composition: everything it needs from a later one has to reach
    // it through state that outlives the recomposition.
    val turnSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultSpatialSpec<Float>().settlingInPixels()
    val returnSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.fastSpatialSpec<Float>().settlingInPixels()
    val currentTurnSpec by rememberUpdatedState(turnSpec)
    val currentReturnSpec by rememberUpdatedState(returnSpec)
    val currentPrev by rememberUpdatedState(prevPage)
    val currentNext by rememberUpdatedState(nextPage)
    val currentOnSelect by rememberUpdatedState(onSelectPeer)
    // At the ends of the list the drag rubber-bands and springs back instead of doing
    // nothing at all, so "there is no next peer" is something you can feel.
    fun resistedOffset(travelled: Float): Float {
        val canMove = (travelled > 0f && currentPrev != null) || (travelled < 0f && currentNext != null)
        return if (canMove) travelled
        else (travelled * PEER_SWIPE_RESISTANCE).coerceIn(-maxOverscrollPx, maxOverscrollPx)
    }

    // Managing the peer's own TailSocks (ui/RemoteManageScreen.kt, experimental): whether the
    // peer offers it is asked here, outside the sheet, and its button draws nothing until the
    // answer is yes. Never of this device, and of an offline peer only what is on disk.
    val manage = rememberRemoteManage(peer, page.isSelf)

    // Once per answer, not once per recomposition: incomingStep is read in composition and
    // the settle flips it several times per page turn, and this parses JSON and matches a
    // regex.
    val pingState = remember(pingResult) { pingStateOf(pingResult) }
    // The list's names for the paths — a relay's node, a region's city — read here, in the
    // parent composition, and handed to both pages of a turn.
    val pathNames = LocalPeerPathNames.current

    // Strings resolved in the parent composition — see wrapContextWithLocale().
    val strings = PeerDetailsStrings(
        prevPeer = stringResource(R.string.peer_details_prev),
        nextPeer = stringResource(R.string.peer_details_next),
        sendFile = stringResource(R.string.peer_send_file),
        // Where nothing can be pasted, the button shows the forms instead of copying one.
        copyAs = stringResource(if (clipboardOffered) R.string.peer_copy_as else R.string.tv_peer_show_as),
        tooltipDismiss = stringResource(R.string.action_close),
        pinging = stringResource(R.string.peer_pinging),
        pingResultFormat = stringResource(R.string.peer_ping_result),
        pingSelf = stringResource(R.string.peer_ping_self),
        pingSelfCaption = stringResource(R.string.peer_ping_self_caption),
        pingCardFormat = stringResource(R.string.peer_cd_ping_card),
        tapToMeasure = stringResource(if (isTv) R.string.tv_peer_press_to_measure else R.string.peer_conn_tap_to_measure),
        copy = stringResource(R.string.action_copy),
        showLarge = stringResource(R.string.tv_value_show),
        copied = stringResource(R.string.peer_copied),
        expanded = stringResource(R.string.peer_cd_expanded),
        collapsed = stringResource(R.string.peer_cd_collapsed),
        exitNodeSelected = stringResource(R.string.peer_exit_node_selected),
        exitNodeOffered = stringResource(R.string.peer_exit_node_offered),
        online = stringResource(R.string.peer_status_online),
        offline = stringResource(R.string.peer_status_offline),
        osUnknown = stringResource(R.string.peer_status_os_unknown),
        path = peerPathWords(),
        latency = stringResource(R.string.peer_conn_latency),
        connPingFailed = stringResource(R.string.peer_conn_ping_failed),
        taildrop = stringResource(R.string.peer_taildrop_caption),
        taildropOk = stringResource(R.string.peer_taildrop_ok),
        taildropBlocked = stringResource(R.string.peer_taildrop_blocked),
        taildropNoNetmap = stringResource(R.string.peer_taildrop_no_netmap),
        taildropNotRunning = stringResource(R.string.peer_taildrop_not_running),
        taildropMissingCap = stringResource(R.string.peer_taildrop_missing_cap),
        taildropOffline = stringResource(R.string.peer_taildrop_offline),
        taildropNoPeerInfo = stringResource(R.string.peer_taildrop_no_peer_info),
        taildropUnsupportedOs = stringResource(R.string.peer_taildrop_unsupported_os),
        taildropNoPeerApi = stringResource(R.string.peer_taildrop_no_peer_api),
        taildropOtherUser = stringResource(R.string.peer_taildrop_other_user),
        taildropSelfShort = stringResource(R.string.peer_taildrop_self_short),
        taildropSelf = stringResource(R.string.peer_taildrop_self),
        trafficTotal = stringResource(R.string.peer_traffic_total),
        groupAddresses = stringResource(R.string.peer_group_addresses),
        groupRoute = stringResource(R.string.peer_group_route),
        groupConnection = stringResource(R.string.peer_group_connection),
        groupDevice = stringResource(R.string.peer_group_device),
        groupCapabilities = stringResource(R.string.peer_group_capabilities),
        groupTraffic = stringResource(R.string.peer_group_traffic),
        groupTiming = stringResource(R.string.peer_group_timing),
        groupOther = stringResource(R.string.peer_group_other)
    )
    // Also the parent's: the sheet's own context would put the toast in the system locale.
    // The toast stays for the case where the copied row has scrolled off under the finger;
    // the tick on the row itself is the acknowledgement for everyone else.
    val onCopyDetail: (PeerDetail) -> Unit = { row ->
        if (clipboardOffered) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(row.label, row.value))
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard, row.label), Toast.LENGTH_SHORT).show()
            copiedDetail = row.id
        } else {
            shownDetail = row
        }
    }
    /** An arrow exists exactly when there is a page to turn to, and turns to that page. */
    fun turnTo(target: PeerPage?): (() -> Unit)? = target?.let { { onSelectPeer(it.peer) } }

    // The page leaving stops at the edge of the sheet, which on a tablet is narrower than the
    // window it sits in.
    val turns = Modifier
        .clipToBounds()
        // Keyed on nothing: a peer change, or a refresh that flips whether there is a peer
        // on either side, must not restart this node — a restart in the middle of a drag
        // leaves horizontalDrag hanging and the content stuck off-centre. The neighbours are
        // read through rememberUpdatedState instead.
        .pointerInput(Unit) {
            // The sheet content, not the window: ModalBottomSheet caps its width at
            // SheetMaxWidth, so on a tablet the window is much the wider of the two.
            fun pageWidth(): Float = size.width.toFloat().takeIf { it > 0f } ?: 1f
            // A page and a bit is all there is to show, so the finger cannot drag
            // the current page past the arriving one and leave the sheet empty.
            // Which neighbour belongs on screen at a given offset: the current page
            // moving right uncovers the sheet's left half, which is where the
            // previous peer lives, and the other way round. At rest the step stands
            // as it is — there is no side to fill.
            fun stepFor(offset: Float): Int = when {
                offset > 0f -> -1
                offset < 0f -> 1
                else -> incomingStep
            }
            fun place(travelled: Float) {
                val offset = resistedOffset(travelled).coerceIn(-pageWidth(), pageWidth())
                swipeOffset = offset
                val step = stepFor(offset)
                if (step != incomingStep) incomingStep = step
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var overSlop = 0f
                // The gesture is claimed only once the finger is clearly moving
                // sideways — twice as much horizontally as vertically. A diagonal
                // drag is left unconsumed, so the ModalBottomSheet keeps its own
                // drag-to-dismiss; the detector keeps asking, so a drag that
                // straightens out later is still picked up.
                val drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                    if (abs(over.x) > abs(over.y) * PEER_SWIPE_DIRECTION_RATIO) {
                        change.consume()
                        overSlop = over.x
                    }
                }
                if (drag != null) {
                    // A settle still in flight is abandoned where it stands. Both
                    // pages are on screen either way, so there is nothing to finish
                    // or unwind: the finger picks the motion up from there.
                    settleJob?.cancel()
                    var travelled = swipeOffset + overSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(drag.uptimeMillis, drag.position)
                    place(travelled)
                    val finished = horizontalDrag(drag.id) { change ->
                        travelled = (travelled + change.positionChange().x)
                            .coerceIn(-pageWidth(), pageWidth())
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        place(travelled)
                    }
                    // Distance or velocity: a fast flick that never covers the
                    // threshold is the natural way to page through a list, and a
                    // slow deliberate crawl past it is the other one.
                    val velocity = tracker.calculateVelocity().x
                    val direction = when {
                        abs(velocity) > flingVelocityPx -> if (velocity > 0f) 1 else -1
                        travelled > swipeThresholdPx -> 1
                        travelled < -swipeThresholdPx -> -1
                        else -> 0
                    }
                    val target = when {
                        !finished -> null
                        direction > 0 -> currentPrev
                        direction < 0 -> currentNext
                        else -> null
                    }
                    val exitTo = if (direction > 0) pageWidth() else -pageWidth()
                    // A rubber-banded page moved at a quarter of the finger's speed,
                    // so it must not be handed the whole of it on release.
                    val resisted = (swipeOffset > 0f && currentPrev == null) ||
                        (swipeOffset < 0f && currentNext == null)
                    val settleVelocity = if (resisted) velocity * PEER_SWIPE_RESISTANCE else velocity
                    // The page drawn alongside now follows the direction that was
                    // committed, not whatever the last place() wrote. A drag one way
                    // ended by a fast flick back the other decides on the velocity
                    // before it has crossed zero, so the two disagree exactly there:
                    // without this the neighbour leaves by the same edge as the page
                    // it is replacing and the sheet turns blank for the whole settle.
                    if (target != null) incomingStep = -direction
                    // Settled from the composition scope, not this one: this scope
                    // dies with the gesture, and the settle outlives the finger.
                    settleJob = scope.launch {
                        if (target != null) {
                            if (animateSwipe) {
                                // Carried on from where the finger left the page, at
                                // the speed it left it — one motion, not a new one.
                                // The step is re-derived every frame, not frozen at
                                // release: these springs are underdamped and carry
                                // the finger's velocity, so the page regularly swings
                                // back across zero on its way out. Whichever half of
                                // the sheet it uncovers has the matching neighbour in
                                // it, all the way to the end.
                                animate(swipeOffset, exitTo, settleVelocity, currentTurnSpec) { value, _ ->
                                    swipeOffset = value
                                    val step = stepFor(value)
                                    if (step != incomingStep) incomingStep = step
                                }
                            }
                            // The arriving page is already centred, so this moves
                            // nothing: it makes that page the current one and zeroes
                            // the offset it is drawn at. One snapshot, so no frame
                            // can fall between the two and show the old page back at
                            // the centre.
                            Snapshot.withMutableSnapshot {
                                swipeOffset = 0f
                                incomingStep = 0
                                currentOnSelect(target.peer)
                            }
                        } else {
                            if (animateSwipe) {
                                animate(swipeOffset, 0f, settleVelocity, currentReturnSpec) { value, _ ->
                                    swipeOffset = value
                                    val step = stepFor(value)
                                    if (step != incomingStep) incomingStep = step
                                }
                            }
                            swipeOffset = 0f
                            incomingStep = 0
                        }
                    }
                }
            }
        }

    // What the sheet or the pane holds: the page on screen and, during a turn, the one
    // arriving beside it; the cards in [cardColumns] columns under each page's header.
    val pages: @Composable BoxScope.(cardColumns: Int) -> Unit = { cardColumns ->
        // The neighbour, drawn a page width away in the drag direction and moving with
        // the current one: what is coming next is on screen from the first millimetre of
        // the drag, which is the whole point. It is measured against the sheet rather
        // than with the sheet, so more open cards on the next peer cannot resize
        // the sheet under the finger.
        val step = incomingStep
        val incoming = if (step != 0) peerAt(step) else null
        if (incoming != null) {
            PeerDetailsPage(
                page = incoming,
                strings = strings,
                pathNames = pathNames,
                // No ping of its own can be in flight on a page nobody has landed on yet.
                ping = PeerPingState.Idle,
                selfAddress = selfAddress,
                expanded = expandedGroups,
                // Nothing has been copied off a page nobody has landed on either.
                copied = null,
                manage = null,
                onPing = {},
                onToggleGroup = onToggleGroup,
                onSendFileClick = { onSendFileClick(incoming.peer) },
                onCopyAsClick = onCopyAsClick?.let { { it(incoming.peer) } },
                // The arriving page's own neighbours, so its arrows are already right
                // when it lands and nothing pops in after the motion ends.
                onPrevPeer = turnTo(peerAt(step - 1)),
                onNextPeer = turnTo(peerAt(step + 1)),
                onCopyDetail = onCopyDetail,
                columns = cardColumns,
                modifier = Modifier
                    .matchParentSize()
                    // Off screen, and a second copy of a peer's whole set of cards: it
                    // is there for the eye during the turn, not for a screen reader,
                    // which reaches the same peer through the page that lands.
                    .clearAndSetSemantics { }
                    .graphicsLayer { peerPageTransform(swipeOffset + step * size.width) }
            )
        }
        PeerDetailsPage(
            page = page,
            strings = strings,
            pathNames = pathNames,
            ping = pingState,
            selfAddress = selfAddress,
            expanded = expandedGroups,
            copied = copiedDetail,
            manage = manage,
            onToggleGroup = onToggleGroup,
            onPing = {
                pingResult = PING_IN_FLIGHT
                scope.launch { pingResult = pingPeer(peer.getPrimaryIp()) }
            },
            onSendFileClick = { onSendFileClick(peer) },
            onCopyAsClick = onCopyAsClick?.let { { it(peer) } },
            onPrevPeer = turnTo(prevPage),
            onNextPeer = turnTo(nextPage),
            onCopyDetail = onCopyDetail,
            columns = cardColumns,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (asPane) Modifier.fillMaxHeight() else Modifier)
                .graphicsLayer { peerPageTransform(swipeOffset) }
        )
    }

    val body: @Composable () -> Unit = {
        if (asPane) {
            // A sheet is a column of cards on any window; a pane beside the list may be as
            // wide as a phone is tall, and there the cards stand in two columns.
            BoxWithConstraints(Modifier.fillMaxSize().then(turns)) {
                pages(columnsFor(maxWidth, PEER_PANE_CARD_MIN_WIDTH, maxColumns = 2))
            }
        } else {
            Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).then(turns)) { pages(1) }
        }
    }
    if (tvDialog) {
        TvSheetDialog(onDismiss) { body() }
    } else {
        SheetOrPane(
            onDismiss = onDismiss,
            inPane = inPane,
            sheetState = sheetState
        ) { body() }
    }

    shownDetail?.let { row ->
        ValueDialog(
            title = peer.getDisplayName(),
            values = listOf(ShownValue(row.label, row.value, qr = row.id in PEER_QR_DETAILS)),
            strings = shownStrings,
            onDismiss = { shownDetail = null }
        )
    }
}
