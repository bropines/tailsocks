---
title: Automation
description: Control TailSocks from Tasker, MacroDroid, Automate, adb or an assistant.
---

Other apps can connect and disconnect TailSocks, pick the exit node, switch the account, and turn the DPI bypass or VPN mode on and off — with broadcasts, which Tasker, MacroDroid, Automate and adb all send. On Android 16 and newer an assistant can do the same through AppFunctions.

## Set it up

Settings → **Automation & API** → **Tasker & Automation** (on a TV: Settings → Advanced → Automation & API):

1. Turn on **Allow External Automation** — the master switch. Off, every broadcast is ignored and every AppFunction that changes something returns an error.
2. **Security Secret Token (required)** — tap **Generate**, then **Copy**, and paste it into your automation app.

Every broadcast must carry the token in a string extra: `secret`, `token` or `key`, checked in that order. A missing or wrong token is logged and the broadcast dropped — read-only ones like `GET_STATUS` included.

Without a token set, every broadcast is refused, and logcat says `Automation intent rejected: no secret token configured`. There is no way to turn the check off: the receiver is open to every app on the device, and without the token any of them could stop your connection or reroute your traffic.

## Broadcasts

Send each one to:

| Field | Value |
|---|---|
| Package | `io.github.bropines.tailscaled` |
| Class | `io.github.bropines.tailscaled.core.TaskerReceiver` — optional, but recommended: Android 8 and newer may drop a broadcast that does not name its receiver |
| Target | Broadcast Receiver |
| Extra | `secret`: your token |

Each action is `io.github.bropines.tailscaled.action.<NAME>`. The short form `io.github.bropines.tailscaled.<NAME>` works the same; for `CONNECT` and `DISCONNECT` it is `START` and `STOP`.

| Action | Extras | Does |
|---|---|---|
| `CONNECT` | — | Starts the daemon and connects. |
| `DISCONNECT` | — | Disconnects and stops the daemon. Final, like a manual Stop: nothing brings the service back afterwards. |
| `TOGGLE` | — | Starts if stopped, stops if running. |
| `RESTART` | — | Restarts the daemon in place. |
| `GET_STATUS` | — | Refreshes the widgets and the Quick Settings tile. |
| `SET_EXIT_NODE` | `exit_node` (string; also `exit_node_ip`): a peer's Tailscale IP, `best`, or `none` / `disabled` / `off` | Sets or clears the active profile's exit node, live if the daemon runs. `best` takes the one Tailscale recommends right now: it needs the daemon running, and with no recommendation nothing changes. |
| `SWITCH_ACCOUNT` | `account` (string; also `account_id`, `account_name`): a profile's ID or name, in any case | Switches the active profile; the daemon restarts if it was running. An unknown name is logged and ignored. |
| `SET_BYEDPI` | `enabled` (boolean, optional), `flags` (string, optional) | Turns the ByeDPI bypass on or off and/or replaces its flags, then applies the settings. |
| `SET_TUN` | `enabled` (boolean, required) | Turns VPN (TUN) mode on or off and applies the settings. Ignored without `enabled`. |

### The status broadcast

When the state changes, or on `GET_STATUS`, TailSocks sends `io.github.bropines.tailscaled.STATUS_CHANGED` (also `…STATUS`). It is addressed to TailSocks itself (its Quick Settings tile; the widgets are refreshed directly), so **Tasker and other apps cannot receive it**. To read the state from another app, use the `getStatus` [AppFunction](#assistants-android-16). For reference, its extras:

| Extra | Type | Example |
|---|---|---|
| `running` | boolean — the daemon runs | `true` |
| `status` | string | `ACTIVE`, `STOPPED`, `STARTING` |
| `account` | string — the active profile's name | `Personal` |
| `account_id` | string — its ID | `acc_123456` |
| `exit_node` | string — the exit node's IP | `100.64.0.1` |
| `tun_enabled` | boolean — VPN (TUN) mode | `true` |
| `byedpi_enabled` | boolean — the ByeDPI bypass | `true` |

### adb

```sh
# Disconnect (the token is required for every action)
adb shell am broadcast -a io.github.bropines.tailscaled.action.DISCONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Connect
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Refresh the widgets and the tile
adb shell am broadcast -a io.github.bropines.tailscaled.action.GET_STATUS -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Set the exit node, take the recommended one, clear it
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node 100.64.0.1
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node best
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node none

# Switch the account
adb shell am broadcast -a io.github.bropines.tailscaled.action.SWITCH_ACCOUNT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es account Work

# VPN (TUN) mode and ByeDPI (boolean extras take --ez)
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_TUN -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_BYEDPI -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true --es flags "-o1 -a1 -r-5+se"
```

### Tasker and MacroDroid

**System → Send Intent** in Tasker:

- Action: `io.github.bropines.tailscaled.action.CONNECT`
- Package: `io.github.bropines.tailscaled`
- Class: `io.github.bropines.tailscaled.core.TaskerReceiver`
- Extra: `secret:YOUR_TOKEN`
- Target: **Broadcast Receiver**

MacroDroid's Send Intent takes the same fields, with the extra as key `secret` and value `YOUR_TOKEN`.

## Assistants (Android 16+)

On Android 16 and newer TailSocks registers 14 AppFunctions with the system, so an on-device assistant such as Gemini can control it in plain words. On older Android nothing is registered. To check: `adb shell dumpsys app_function`.

They need no token: the system decides who may call them. The read-only ones always answer; the ones that change something obey **Allow External Automation** — with it off they return `success = false` and *External automation is disabled in TailSocks settings.*

| Function | Kind | Does |
|---|---|---|
| `getStatus()` | read | The connection state, the active account, the exit node's IP, and whether VPN mode, ByeDPI, MagicDNS and LAN access are on. |
| `getAvailableExitNodes()` | read | The peers offering to be an exit node, whether each is online, and which one is in use. |
| `getTailnetPeers()` | read | Every peer in the tailnet, with name, IP, OS and whether it is online. |
| `getAccounts()` | read | The profiles, and the active one. |
| `connect(exitNodeIp)` | change | Starts the service, first selecting an exit node if one is given; waits up to about 6 s and reports what it sees. |
| `disconnect()` | change | Stops the service. Final, like a manual Stop. |
| `toggle()` | change | `disconnect()` if running, otherwise `connect("")`. |
| `selectExitNode(exitNodeIp)` | change | Sets the exit node by Tailscale IP — `best` takes the one Tailscale recommends, `off`, `none` or empty clears it — and hands it to the running daemon. |
| `clearExitNode()` | change | The same as `selectExitNode("")`. |
| `switchAccount(accountNameOrId)` | change | Switches the active profile by name or ID; restarts the daemon if it was running. |
| `setByeDpi(enabled, flags)` | change | Turns the ByeDPI bypass on or off, replacing its flags if given. |
| `setTunMode(enabled)` | change | Turns VPN (TUN) mode on or off. |
| `setAllowLanAccess(enabled)` | change | Turns **Expose proxies to local network** on or off: SOCKS5, HTTP and DNS listen on `0.0.0.0`. |
| `setMagicDns(enabled)` | change | Turns **Use MagicDNS** on or off. |

## Links to screens

A `tailsocks://` link opens a screen and does nothing else: it changes no setting and starts nothing, so it needs no token. `tailcat/add` only fills in a new TailCat connection; saving it is your tap.

| Link | Opens |
|---|---|
| `tailsocks://serve` | Serve & Funnel |
| `tailsocks://exitnode` | the exit node picker, on the main screen |
| `tailsocks://scan` | the QR scanner, over the main screen |
| `tailsocks://tailcat` | TailCat |
| `tailsocks://tailcat/add?cmd=<address or connect command>` | a new TailCat connection, filled in (URL-encode `cmd`) |
| `tailsocks://logs?category=ERROR` | Logs on one category: `ALL`, `ERROR`, `CORE`, `TAILSCALE`, `TAILCAT`, … |
| `tailsocks://settings/<section>` | a page of Settings |
| `tailsocks://settings/<section>/<row>` | a page of Settings, with one row in view and marked |
| `tailsocks://settings?q=<words>` | Settings, with `<words>` typed into the search (URL-encoded) |
| `tailsocks://peers`, `dns`, `netcheck`, `console`, `files`, `taildrive`, `permissions`, `licenses` | that screen |

Every Settings page and row, with its link: [Settings search and links](/tailsocks/settings-links/#every-setting). A screen the device does not have — TailCat on a TV, for one — opens the main screen instead.

```sh
adb shell am start -a android.intent.action.VIEW -d 'tailsocks://logs?category=TAILCAT'
```

### As QR codes

The app's QR scanner — in the main screen's top bar, in TailCat's top bar, its empty list and a connection's editor, or `tailsocks://scan` — reads the camera or a picture from the gallery:

- A `tailsocks://` link opens exactly as if it had been tapped.
- A TailCat address or connect command opens a new connection's editor, filled in.
- Anything else is only shown, with Copy and, for a web address, Open. Nothing in a code is acted on without a tap.

A TailCat QR code — a connection card's, or the server's *Connect to this phone* — has two tabs: **Link**, a `tailsocks://tailcat/add?cmd=…` that any phone's camera opens in TailSocks, and **Command**, the `tailcat` lines a computer runs. The scanner reads both. The camera permission is asked for when the scanner opens; the gallery needs none.

## In the background

Whether the connection survives with the app out of sight is up to four switches in Settings → **Background & permissions**:

- **Auto-reconnect** — off by default. Restarts the daemon when the connection does not come up or drops: after three unhealthy checks in a row, never while it waits for you to sign in. **Restart attempts (0 = unlimited)** caps it, 3 by default.
- **Revive service in background** — on by default. Every 15 minutes checks that the service is alive while you want it running, and starts it again after the system or a vendor's task killer stopped it. Turning it on asks for *Alarms & reminders*, which makes the check exact and lets it start the service from the background; without it the check comes later. If the start is refused anyway, a notification reconnects in one tap, and the next time you open the app it points once at the vendor's autostart setting.
- **Keep the connection awake** — off by default. Holds a wake lock for the whole session, so keepalives go out in deep sleep. Costs battery.
- **Auto-start on Boot** — off by default. Starts the app after a reboot if it was running before.

Two rules hold whatever the switches say:

- **A manual Stop is final.** Stopping from the app, the notification, the tile, a `DISCONNECT` broadcast or the `disconnect` AppFunction means nothing above brings the service back until you start it.
- **Swiping the app away from Recents keeps the connection.**
