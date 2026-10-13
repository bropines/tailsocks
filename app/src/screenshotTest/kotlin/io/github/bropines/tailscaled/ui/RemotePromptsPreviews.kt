package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppPermission
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.PermState

// The Permissions screen as a paired device's request opens it (core/RemoteDevicePrompts.kt): the
// card above the rows while the permission is missing, once it is given, and on a device with no
// page to give it on — on a phone and on a 1080p television, in English and in Russian. The
// renderer has no system to ask, so the rows under the card are all "unknown", and nothing on the
// television is focused: the card's explanation is folded, as it is before the remote rests on it.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
internal annotation class PromptPhone

@Preview(name = "3-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "4-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
internal annotation class PromptTv

private fun ask(permission: AppPermission, state: PermState, noPage: Boolean = false) =
    PermissionAsk(permission, from = "pixel-9-pro", demo = PermissionAsk.Demo(state, noPage))

private val storageMissing = ask(AppPermission.STORAGE, PermState.DENIED)
private val storageGranted = ask(AppPermission.STORAGE, PermState.GRANTED)
private val storageNoPage = ask(AppPermission.STORAGE, PermState.DENIED, noPage = true)

@Composable
private fun Phone(ask: PermissionAsk) = AdaptiveShowcase(null) { PermissionsScreen(onBack = {}, ask = ask) }

@Composable
private fun Tv(ask: PermissionAsk) = AdaptiveShowcase(null, device = DeviceClass.TV) { PermissionsScreen(onBack = {}, ask = ask) }

@PreviewTest @PromptPhone @Composable
fun PromptAskPhone() = Phone(storageMissing)

@PreviewTest @PromptPhone @Composable
fun PromptAskGrantedPhone() = Phone(storageGranted)

@PreviewTest @PromptPhone @Composable
fun PromptAskNoPagePhone() = Phone(storageNoPage)

/** Notifications off: the one a request is most often about on a device that has just been set up. */
@PreviewTest @PromptPhone @Composable
fun PromptAskNotificationsPhone() = Phone(ask(AppPermission.NOTIFICATIONS, PermState.DENIED))

@PreviewTest @PromptTv @Composable
fun PromptAskTv() = Tv(storageMissing)

@PreviewTest @PromptTv @Composable
fun PromptAskGrantedTv() = Tv(storageGranted)

@PreviewTest @PromptTv @Composable
fun PromptAskNoPageTv() = Tv(storageNoPage)
