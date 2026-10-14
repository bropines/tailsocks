---
title: Automation
description: Control TailSocks from Tasker, MacroDroid, adb or an assistant.
---

## Set it up

Settings → **Automation & API** → **Tasker & Automation**:

1. Turn on **Allow External Automation**.
2. **Security Secret Token (required)** — tap **Generate**, then **Copy**.

Every broadcast must carry the token as the string extra `secret` (`token` and `key` work too). Without a token set, every broadcast is refused — there is no way to turn the check off.

## Broadcasts

Send to the broadcast receiver `io.github.bropines.tailscaled/.core.TaskerReceiver`. Each action is `io.github.bropines.tailscaled.action.<NAME>`; the short form `io.github.bropines.tailscaled.<NAME>` works too, and `START` / `STOP` stand for `CONNECT` / `DISCONNECT`.

| Action | Extras | Does |
|---|---|---|
| `CONNECT` | — | starts and connects |
| `DISCONNECT` | — | stops; final, like a manual Stop |
| `TOGGLE` | — | starts or stops |
| `RESTART` | — | restarts the daemon |
| `GET_STATUS` | — | refreshes the widgets and the tile |
| `SET_EXIT_NODE` | `exit_node`: an IP, `best`, or `none` | sets or clears the exit node |
| `SWITCH_ACCOUNT` | `account`: a name or an ID | switches the account |
| `SET_BYEDPI` | `enabled` (boolean), `flags` (string) | the DPI bypass |
| `SET_TUN` | `enabled` (boolean) | VPN (TUN) mode on or off |

`best` takes the exit node Tailscale recommends right now. The status broadcast stays inside the app: other apps cannot read the state this way — use `getStatus` below.

### adb

```sh
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT \
  -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE \
  -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node 100.64.0.1
```

Boolean extras take `--ez`: `--ez enabled true`.

### Tasker

**System → Send Intent**: Action `io.github.bropines.tailscaled.action.CONNECT`, Package `io.github.bropines.tailscaled`, Class `io.github.bropines.tailscaled.core.TaskerReceiver`, Extra `secret:YOUR_TOKEN`, Target **Broadcast Receiver**. MacroDroid takes the same fields.

## Assistants (Android 16+)

On Android 16 and newer TailSocks offers 14 AppFunctions to on-device assistants such as Gemini:

- Read, always allowed: `getStatus`, `getAvailableExitNodes`, `getTailnetPeers`, `getAccounts`.
- Change, only with **Allow External Automation** on: `connect`, `disconnect`, `toggle`, `selectExitNode`, `clearExitNode`, `switchAccount`, `setByeDpi`, `setTunMode`, `setAllowLanAccess`, `setMagicDns`.

They need no token; the system decides who may call them.

## Links to screens

`tailsocks://` links open a screen and change nothing, so they need no token:

| Link | Opens |
|---|---|
| `tailsocks://settings/…` | Settings — see [Settings search and links](/tailsocks/settings-links/) |
| `tailsocks://exitnode` | the exit node picker |
| `tailsocks://scan` | the QR scanner |
| `tailsocks://logs?category=ERROR` | Logs on one category |
| `tailsocks://peers`, `dns`, `netcheck`, `console`, `files`, `taildrive`, `serve`, `permissions`, `licenses` | that screen |

```sh
adb shell am start -a android.intent.action.VIEW -d 'tailsocks://netcheck'
```

The app's own QR scanner opens these links too. The full reference: [AUTOMATION.md](https://github.com/bropines/tailsocks/blob/main/docs/AUTOMATION.md).
