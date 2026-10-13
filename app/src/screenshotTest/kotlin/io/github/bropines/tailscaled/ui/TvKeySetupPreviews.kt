package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.KeyKind
import io.github.bropines.tailscaled.core.KeyRoad
import io.github.bropines.tailscaled.core.KeySendEnd
import io.github.bropines.tailscaled.core.KeySendStep
import io.github.bropines.tailscaled.core.SetupTarget
import io.github.bropines.tailscaled.core.TvKeyStep
import io.github.bropines.tailscaled.core.TvKeyStop
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * A television signed in with a key from a phone, both ends, every step
 * (ui/TvKeySetup.kt, ui/KeySendSheet.kt), in English and in Russian.
 *
 * The television's are drawn as a television — the class handed in through
 * AdaptiveShowcase — and once as a phone showing the TV interface, where the
 * code and the words stack. The phone's are the sheet's content without the
 * sheet: the renderer has no windows, and a sheet is one. Nothing here is
 * focused, a render cannot press a key; where the remote starts is in the
 * emulator's screenshots.
 */

/** A phone with the TV interface switched on by hand (Settings → Appearance → Interface). */
@Preview(name = "1-phone-as-tv", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-as-tv-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class PhoneAsTv

/** The sheet on a phone, in both languages, and on its side. */
@Preview(name = "send-phone", device = "spec:width=393dp,height=852dp,dpi=420")
@Preview(name = "send-phone-ru", device = "spec:width=393dp,height=852dp,dpi=420", locale = "ru")
annotation class SendLanguages

// A code as the bridge makes one for a single address: 89 characters, the prefix among them.
private const val DEMO_CODE = "TSP2:1F8Q2ZK9M4X7C0VB3N6H5J2L8P1R4T7W0Y3A6D9G2S5E8U1I4O7KQZ3M6X9C2V5B8N1H4J7L0P3R6T9W2Y5A"

// The same for the relay alone: 83, and a sparser picture.
private const val DEMO_CODE_RELAY = "TSP2:1F8Q2ZK9M4X7C0VB3N6H5J2L8P1R4T7W0Y3A6D9G2S5E8U1I4O7KQZ3M6X9C2V5B8N1H4J7L0P3R6T"

// As a session mostly is: on a local network, and with the relay for a phone that is not.
private val waiting = TvKeyStep.Code(DEMO_CODE, "K7Q2M", listOf("192.168.1.50"), secondsLeft = 580, refused = 0, relay = true)
private val offer = TvKeyStep.Offer("pixel-9-pro", "192.168.1.23", "527184", KeyKind.TAILSCALE_AUTH, "", secondsLeft = 171)

@Composable
private fun Tv(step: TvKeyStep, currentServer: String = "") = AdaptiveShowcase(data = null, device = DeviceClass.TV) {
    TvKeySetupContent(step = step, currentServer = currentServer, onConfirm = {}, onReject = {}, onRenew = {}, onClose = {})
}

// ─── The television ─────────────────────────────────────────────────────────

/** Where the sign-in screen offers it. */
@PreviewTest @TvBothLanguages @Composable
fun TvKeyEntry() = AdaptiveShowcase(data = null, device = DeviceClass.TV) {
    Box(Modifier.padding(24.dp)) { TvKeySetupButton(onClick = {}) }
}

/** No code yet: the session is there and asks for the relay, a few seconds. */
@PreviewTest @TvBothLanguages @Composable
fun TvKeyOpening() = Tv(TvKeyStep.Opening)

@PreviewTest @TvBothLanguages @PhoneAsTv @Composable
fun TvKeyCode() = Tv(waiting)

/** Wired and wireless at once, and somebody on the network knocking. */
@PreviewTest @TvBothLanguages @Composable
fun TvKeyCodeKnocked() = Tv(waiting.copy(addresses = listOf("192.168.1.50", "192.168.1.51", "10.0.7.2"), secondsLeft = 9, refused = 3))

/** A television with no address on a local network: the relay alone leads to it, and it has no address to be named by. */
@PreviewTest @TvBothLanguages @PhoneAsTv @Composable
fun TvKeyCodeRelayOnly() = Tv(waiting.copy(text = DEMO_CODE_RELAY, addresses = emptyList()))

/** No relay to be had — a television without the internet: here the phone does have to be on its network. */
@PreviewTest @TvBothLanguages @PhoneAsTv @Composable
fun TvKeyCodeNoRelay() = Tv(waiting.copy(relay = false))

@PreviewTest @TvBothLanguages @PhoneAsTv @Composable
fun TvKeyOffer() = Tv(offer)

/** A key that came through the relay: its sender has no address, and the road stands in its place. */
@PreviewTest @TvBothLanguages @PhoneAsTv @Composable
fun TvKeyOfferRelay() = Tv(offer.copy(address = "", road = KeyRoad.RELAY))

/** The same from a phone that gave no name. */
@PreviewTest @TvBothLanguages @Composable
fun TvKeyOfferRelayUnnamed() = Tv(offer.copy(sender = "", address = "", road = KeyRoad.RELAY))

/** A key of another server than the television uses: the line to think twice about. */
@PreviewTest @TvBothLanguages @PhoneAsTv @Composable
fun TvKeyOfferOtherServer() = Tv(
    offer.copy(sender = "Samsung Galaxy S24 Ultra of the living room", keyKind = KeyKind.HEADSCALE_AUTH, server = "https://headscale.example.com:8443", secondsLeft = 59)
)

/** The server the television already uses, named again by the phone. */
@PreviewTest @TvBothLanguages @Composable
fun TvKeyOfferSameServer() = Tv(
    offer.copy(keyKind = KeyKind.TAILSCALE_CLIENT, server = "https://headscale.example.com"),
    currentServer = "https://headscale.example.com"
)

/** No name, no prefix the app knows, and a television on a server of its own that stays. */
@PreviewTest @TvBothLanguages @Composable
fun TvKeyOfferBare() = Tv(offer.copy(sender = "", keyKind = KeyKind.OTHER), currentServer = "https://headscale.example.com")

@PreviewTest @TvBothLanguages @Composable
fun TvKeySigningIn() = Tv(TvKeyStep.SigningIn)

@PreviewTest @TvBothLanguages @Composable
fun TvKeySignedIn() = Tv(TvKeyStep.SignedIn)

@PreviewTest @TvBothLanguages @PhoneAsTv @Composable
fun TvKeyStopNoNetwork() = Tv(TvKeyStep.Stopped(TvKeyStop.NO_NETWORK))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopCannotListen() = Tv(TvKeyStep.Stopped(TvKeyStop.CANNOT_LISTEN))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopTooManyAttempts() = Tv(TvKeyStep.Stopped(TvKeyStop.TOO_MANY_ATTEMPTS))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopNetworkChanged() = Tv(TvKeyStep.Stopped(TvKeyStop.NETWORK_CHANGED))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopServiceStopped() = Tv(TvKeyStep.Stopped(TvKeyStop.SERVICE_STOPPED))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopCodeExpired() = Tv(TvKeyStep.Stopped(TvKeyStop.CODE_EXPIRED))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopNotAnswered() = Tv(TvKeyStep.Stopped(TvKeyStop.NOT_ANSWERED))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopRejected() = Tv(TvKeyStep.Stopped(TvKeyStop.REJECTED))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopKeyGone() = Tv(TvKeyStep.Stopped(TvKeyStop.KEY_GONE))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopNotSignedIn() = Tv(TvKeyStep.Stopped(TvKeyStop.NOT_SIGNED_IN))

@PreviewTest @TvBothLanguages @Composable
fun TvKeyStopUnknown() = Tv(TvKeyStep.Stopped(TvKeyStop.UNKNOWN))

// ─── The phone ──────────────────────────────────────────────────────────────

// What a scanned code mostly says: an address on the local network, and the relay behind it.
private val bothTarget = SetupTarget(ok = true, id = "K7Q2M", addresses = listOf("192.168.1.50"), port = 51234, relay = true)

// A television with no local address, and one that could not have the relay.
private val relayTarget = SetupTarget(ok = true, id = "K7Q2M", relay = true)
private val lanTarget = bothTarget.copy(relay = false)
private const val DEMO_KEY = "tskey-auth-kAbCdEf1CNTRL-0123456789abcdefghijklmnopqrstuv"
private const val DEMO_ADDRESS = "192.168.1.50:51234"

@Composable
private fun Send(
    step: KeySendStep,
    key: String = "",
    server: String = "",
    dark: Boolean = false,
    target: SetupTarget = bothTarget,
) = TailSocksTheme(
    appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false
) {
    // The sheet's own container colour, as ModalBottomSheet draws it.
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        KeySendContent(
            target = target,
            step = step,
            key = key,
            onKey = {},
            server = server,
            onServer = {},
            onPaste = {},
            onSend = {},
            onCancel = {},
            onNext = {},
            onStopWaiting = {},
        )
    }
}

@Composable
private fun SendEnded(how: KeySendEnd, address: String = DEMO_ADDRESS, target: SetupTarget = bothTarget) =
    Send(KeySendStep.Ended(how, address), target = target)

/** Where the key will go, in words: the local network first, the relay if that fails. */
@PreviewTest @SendLanguages @Composable
fun KeySendFormEmpty() = Send(KeySendStep.Form)

/** A television with no local address: no address on the line, and the relay the only road. */
@PreviewTest @SendLanguages @Composable
fun KeySendFormRelayOnly() = Send(KeySendStep.Form, target = relayTarget)

/** A code without a relay: the local network and nothing else. */
@PreviewTest @SendLanguages @Composable
fun KeySendFormLanOnly() = Send(KeySendStep.Form, target = lanTarget)

/** A key pasted and a server of the phone's own filled in: what Send goes out with, the key shown by its kind and length. */
@PreviewTest @SendLanguages @Preview(name = "send-phone-landscape", device = "spec:width=852dp,height=393dp,dpi=420") @Composable
fun KeySendFormFilled() = Send(KeySendStep.Form, key = DEMO_KEY, server = "https://headscale.example.com", dark = true)

/** An older Headscale key has no prefix: it goes as it is, and the sheet says so. */
@PreviewTest @SendLanguages @Composable
fun KeySendFormUnknownKind() = Send(KeySendStep.Form, key = "0123456789abcdef0123456789abcdef0123456789abcdef")

/** The wrong kind of key and an address that is none: Send is not offered. */
@PreviewTest @SendLanguages @Composable
fun KeySendFormRefused() = Send(KeySendStep.Form, key = "tskey-api-kAbCdEf1CNTRL-0123456789abcdef", server = "ftp://example")

@PreviewTest @SendLanguages @Composable
fun KeySendFormSpaces() = Send(KeySendStep.Form, key = "tskey-auth-kAbCdEf1CNTRL 0123456789")

/** The first moment of a send, before the bridge has named a road. */
@PreviewTest @SendLanguages @Composable
fun KeySendSending() = Send(KeySendStep.Sending())

@PreviewTest @SendLanguages @Composable
fun KeySendSendingLan() = Send(KeySendStep.Sending(KeyRoad.LAN))

/** The long one: up to half a minute, said so, and with a way out. */
@PreviewTest @SendLanguages @Composable
fun KeySendSendingRelay() = Send(KeySendStep.Sending(KeyRoad.RELAY))

@PreviewTest @SendLanguages @Composable
fun KeySendCompare() = Send(KeySendStep.Compare("527184", DEMO_ADDRESS))

/** Sent through the relay: no address under the check code, the road instead. */
@PreviewTest @SendLanguages @Composable
fun KeySendCompareRelay() = Send(KeySendStep.Compare("527184", "", KeyRoad.RELAY))

@PreviewTest @SendLanguages @Composable
fun KeySendCompareDark() = Send(KeySendStep.Compare("004217", DEMO_ADDRESS), dark = true)

@PreviewTest @SendLanguages @Composable
fun KeySendConfirmed() = SendEnded(KeySendEnd.CONFIRMED)

@PreviewTest @SendLanguages @Composable
fun KeySendRejected() = SendEnded(KeySendEnd.REJECTED)

@PreviewTest @SendLanguages @Composable
fun KeySendExpired() = SendEnded(KeySendEnd.EXPIRED)

@PreviewTest @SendLanguages @Composable
fun KeySendCancelled() = SendEnded(KeySendEnd.CANCELLED)

@PreviewTest @SendLanguages @Composable
fun KeySendLost() = SendEnded(KeySendEnd.LOST)

@PreviewTest @SendLanguages @Composable
fun KeySendTaken() = SendEnded(KeySendEnd.TAKEN)

@PreviewTest @SendLanguages @Composable
fun KeySendUnsupported() = SendEnded(KeySendEnd.UNSUPPORTED)

@PreviewTest @SendLanguages @Composable
fun KeySendClosed() = SendEnded(KeySendEnd.CLOSED, address = "")

/** Both roads tried: nothing on the local network, and no television behind the relay. */
@PreviewTest @SendLanguages @Composable
fun KeySendUnreachable() = SendEnded(KeySendEnd.UNREACHABLE, address = "")

/** A code without a relay: the local network was the only road, and the hint says what it takes. */
@PreviewTest @SendLanguages @Composable
fun KeySendUnreachableNoRelay() = SendEnded(KeySendEnd.UNREACHABLE, address = "", target = lanTarget)

/** Only a code without a relay ends this way. */
@PreviewTest @SendLanguages @Composable
fun KeySendWrongNetwork() = SendEnded(KeySendEnd.WRONG_NETWORK, address = "", target = lanTarget)

/** Nothing on the local network, and the phone itself does not get to the relay. */
@PreviewTest @SendLanguages @Composable
fun KeySendRelayUnavailable() = SendEnded(KeySendEnd.RELAY_UNAVAILABLE, address = "")

@PreviewTest @SendLanguages @Composable
fun KeySendNoAnswer() = SendEnded(KeySendEnd.NO_ANSWER)

@PreviewTest @SendLanguages @Composable
fun KeySendInvalidCode() = SendEnded(KeySendEnd.INVALID_CODE, address = "")

@PreviewTest @SendLanguages @Composable
fun KeySendNewerCode() = SendEnded(KeySendEnd.NEWER_CODE, address = "")

@PreviewTest @SendLanguages @Composable
fun KeySendInvalidKey() = SendEnded(KeySendEnd.INVALID_KEY, address = "")

@PreviewTest @SendLanguages @Composable
fun KeySendInvalidServer() = SendEnded(KeySendEnd.INVALID_SERVER, address = "")

@PreviewTest @SendLanguages @Composable
fun KeySendBusy() = SendEnded(KeySendEnd.BUSY, address = "")

@PreviewTest @SendLanguages @Composable
fun KeySendUnknown() = SendEnded(KeySendEnd.UNKNOWN)
