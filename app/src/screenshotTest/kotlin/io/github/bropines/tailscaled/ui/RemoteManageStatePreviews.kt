package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import kotlinx.serialization.json.put

// The managing page (ui/RemoteManage*.kt) in every state a device can put it in, and through a
// change that is confirmed by itself. The fixtures are RemoteManagePreviews.kt's.

// ─── Every state has a face ──────────────────────────────────────────────────────────────────

/** The first question is still on its way. */
@PreviewTest @ManagePhone @Composable
fun ManageLoading() = ManagePage(RemoteManageUi.Managing("living-room-tv", tv = true, owner = true, loading = true))

/** Never reached: what is left is to try again, or to forget it. */
@PreviewTest @ManagePhone @Composable
fun ManageNeverReached() = ManagePage(RemoteManageUi.Managing("living-room-tv", tv = true, owner = true, problem = ManageProblem.OFFLINE))

/** Offline: what it said last stays, dimmed, under the sentence and its one action. */
@PreviewTest @ManagePhone @Composable
fun ManageOffline() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.OFFLINE))

@PreviewTest @ManageTv @Composable
fun ManageOfflineTv() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.OFFLINE), tv = true)

/** Online, and the port is closed to this device. */
@PreviewTest @ManagePhone @Composable
fun ManageNotAccepting() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.NOT_ACCEPTING))

/** 401: the pairing was revoked there. Nothing of the device is shown any more. */
@PreviewTest @ManagePhone @Composable
fun ManageRevoked() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.REVOKED))

/** 401 for a ManageDemo.guest whose time is up. */
@PreviewTest @ManagePhone @Composable
fun ManageExpired() = ManagePage(ManageDemo.guest.copy(problem = ManageProblem.EXPIRED))

/** 503: nothing was changed, and that may be said. */
@PreviewTest @ManagePhone @Composable
fun ManageBusy() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.BUSY))

/** 504: the outcome is open. The device was read again, and the page shows what it says now. */
@PreviewTest @ManagePhone @Composable
fun ManageUnclear() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.UNCLEAR))

/** 429. */
@PreviewTest @ManagePhone @Composable
fun ManageSlowDown() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.SLOW_DOWN))

/** 409 with the app's own sentence. */
@PreviewTest @ManagePhone @Composable
fun ManageRefused() = ManagePage(ManageDemo.fullOwner.copy(problem = ManageProblem.REFUSED, problemDetail = "this device has no settings page for it"))

/** Full control was switched off at the device while the page was open. */
@PreviewTest @ManagePhone @Composable
fun ManageNeedsFull() = ManagePage(ManageDemo.basicOwner.copy(problem = ManageProblem.NEEDS_FULL))

// ─── A change that is confirmed by itself ────────────────────────────────────────────────────

private fun guard(phase: GuardPhase, key: String = "exit_node") = GuardState("g7Jk2", key, phase, ManageDemo.NOW + 47_000)

/** Sent, applied there for now, and being confirmed: nothing is asked of the person. */
@PreviewTest @ManagePhone @Composable
fun ManageGuardChecking() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.CHECKING)))

@PreviewTest @ManagePhone @Composable
fun ManageGuardApplied() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.APPLIED)))

/** No answer: the device will put the old value back by itself, and the page says when. */
@PreviewTest @ManagePhone @Composable
fun ManageGuardSilent() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.SILENT)))

@PreviewTest @ManageTv @Composable
fun ManageGuardSilentTv() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.SILENT)), tv = true)

@PreviewTest @ManageTablet @Composable
fun ManageGuardSilentTablet() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.SILENT, "accept_routes")))

/** The time ran out in silence: it is back to the old value by now, and has not said so yet. */
@PreviewTest @ManagePhone @Composable
fun ManageGuardAway() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.REVERTED_AWAY), problem = ManageProblem.OFFLINE))

/** It answers again: what the value is now. */
@PreviewTest @ManagePhone @Composable
fun ManageGuardReverted() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.REVERTED)))

@PreviewTest @ManagePhone @Composable
fun ManageGuardRevertedSwitch() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.REVERTED, "tun_mode")))

@PreviewTest @ManagePhone @Composable
fun ManageGuardUnclear() = ManagePage(ManageDemo.fullOwner.copy(guard = guard(GuardPhase.UNCLEAR, "accept_dns")))

