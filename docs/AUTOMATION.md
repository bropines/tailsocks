# ⚡ Tasker & Automation Integration Guide

TailSocks supports comprehensive background automation via **Android Broadcast Intents**. This allows complete control over connections, profiles, exit nodes, DPI bypass, and TUN mode from automation apps like **Tasker**, **MacroDroid**, **Automate**, or **ADB**.

On Android 16+ the same operations are also exposed to on-device assistants through **AppFunctions** (see [Gemini / AppFunctions](#-gemini--appfunctions-android-16) below).

---

## 🔒 Security & Token Protection

Automation is configured in TailSocks under **Settings → APP tab → Tasker & Automation**:

* **Allow External Automation:** Master switch. When off, every broadcast is ignored and every mutating AppFunction returns an error.
* **Security Secret Token (required since 4.0.0):** The broadcast receiver is exported with no permission, so without a token any installed app could stop the VPN or reroute your traffic. Since 4.0.0 the receiver **refuses every intent until a token is set**. Use **Generate** to create one and **Copy** to move it into your automation app.

Every intent must carry the token in one of the following string extras (checked in this order): **`secret`**, **`token`**, or **`key`**. The comparison is constant-time; a missing or wrong value is logged and dropped. The token also applies to read-only actions such as `GET_STATUS`.

> If the token is empty you will see `Automation intent rejected: no secret token configured` in logcat and nothing will happen. Set a token instead of turning the check off — there is no way to disable it.

---

## 🎯 Supported Intent Actions

### Target Receiver Specification
* **Package Name:** `io.github.bropines.tailscaled`
* **Component Class:** `io.github.bropines.tailscaled.core.TaskerReceiver` *(optional; recommended on Android 8+, where implicit broadcasts to manifest receivers are not delivered)*
* **Target Type:** Broadcast Receiver
* **Always add:** string extra `secret` (or `token` / `key`) with your token.

### Control Actions

| Action | Short Alias | Extras / Parameters | Description |
|--------|-------------|---------------------|-------------|
| `io.github.bropines.tailscaled.action.CONNECT` | `io.github.bropines.tailscaled.START` | — | Starts the background daemon and connects TailSocks. |
| `io.github.bropines.tailscaled.action.DISCONNECT` | `io.github.bropines.tailscaled.STOP` | — | Disconnects and stops the background daemon. Equivalent to a manual **Stop**: nothing revives the service afterwards (see [Background behaviour](#-background-behaviour)). |
| `io.github.bropines.tailscaled.action.TOGGLE` | `io.github.bropines.tailscaled.TOGGLE` | — | Starts if stopped, stops if running. |
| `io.github.bropines.tailscaled.action.RESTART` | `io.github.bropines.tailscaled.RESTART` | — | Restarts the daemon in place. |
| `io.github.bropines.tailscaled.action.GET_STATUS` | `io.github.bropines.tailscaled.GET_STATUS` | — | Refreshes the widgets / tile state. The resulting `STATUS_CHANGED` broadcast is not visible to other apps (see below). |
| `io.github.bropines.tailscaled.action.SET_EXIT_NODE` | `io.github.bropines.tailscaled.SET_EXIT_NODE` | `exit_node` (String; alias `exit_node_ip`). Tailscale IP of the peer, or `none` / `disabled` / `off` to clear. | Sets the exit node of the active profile and applies it live if the daemon is running. |
| `io.github.bropines.tailscaled.action.SWITCH_ACCOUNT` | `io.github.bropines.tailscaled.SWITCH_ACCOUNT` | `account` (String; aliases `account_id`, `account_name`). Profile ID or name, case-insensitive. | Switches the active profile; the daemon is restarted if it was running. Unknown names are logged and ignored. |
| `io.github.bropines.tailscaled.action.SET_BYEDPI` | `io.github.bropines.tailscaled.SET_BYEDPI` | `enabled` (Boolean, optional), `flags` (String, optional) | Enables/disables the ByeDPI control-plane bypass and/or replaces its flags, then re-applies settings. |
| `io.github.bropines.tailscaled.action.SET_TUN` | `io.github.bropines.tailscaled.SET_TUN` | `enabled` (Boolean, required) | Switches transparent TUN mode on or off and re-applies settings. Ignored without the `enabled` extra. |

Both the long `…action.X` form and the short alias are declared in the manifest and behave identically.

---

## 📡 Automatic Status Broadcasts (Events)

Whenever TailSocks status changes or `GET_STATUS` is requested, TailSocks broadcasts an event intent to **`io.github.bropines.tailscaled.STATUS_CHANGED`** (alias **`io.github.bropines.tailscaled.STATUS`**). The broadcast is scoped to the app's own package (`setPackage`), so it is only delivered to TailSocks' own widgets and Quick Settings tile; **third-party apps such as Tasker cannot receive it**. `GET_STATUS` therefore only refreshes the in-app widgets. To read the state from another app, use the `getStatus()` AppFunction on Android 16+ (see below). For reference, the extras carried by the internal broadcast are:

| Extra Key | Type | Description | Example |
|-----------|------|-------------|---------|
| `running` | Boolean | True if daemon is running | `true` |
| `status` | String | Status text | `"ACTIVE"`, `"STOPPED"`, `"STARTING"` |
| `account` | String | Active profile name | `"Personal"` |
| `account_id` | String | Active profile ID | `"acc_123456"` |
| `exit_node` | String | Active exit node IP | `"100.64.0.1"` |
| `tun_enabled` | Boolean | TUN mode active state | `true` |
| `byedpi_enabled`| Boolean | ByeDPI proxy state | `true` |

---

## 📱 Automation Examples

### 1. MacroDroid / Tasker
* **Target:** Broadcast Receiver
* **Action:** `io.github.bropines.tailscaled.action.CONNECT`
* **Package Name:** `io.github.bropines.tailscaled`
* **Class:** `io.github.bropines.tailscaled.core.TaskerReceiver`
* **Extra 1:** Key: `secret`, Value: `YOUR_TOKEN`

### 2. ADB Command Examples

```bash
# Disconnect (the token is mandatory for every action)
adb shell am broadcast -a io.github.bropines.tailscaled.action.DISCONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Connect
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Refresh the widgets / tile (the resulting STATUS_CHANGED broadcast stays inside the app)
adb shell am broadcast -a io.github.bropines.tailscaled.action.GET_STATUS -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN

# Set Exit Node / clear it
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node 100.64.0.1
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es exit_node none

# Switch Profile
adb shell am broadcast -a io.github.bropines.tailscaled.action.SWITCH_ACCOUNT -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --es account Work

# Toggle TUN mode / ByeDPI (boolean extras use --ez)
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_TUN -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_BYEDPI -n io.github.bropines.tailscaled/.core.TaskerReceiver --es secret YOUR_TOKEN --ez enabled true --es flags "-s 1 -d split -r"
```

---

## 🔄 Background behaviour

What happens to the connection when the app is not in the foreground is governed by three settings, all under **Settings → APP → System & Backup** unless noted:

* **Auto-reconnect** (off by default): restarts the daemon when the connection does not come up or drops. Three consecutive unhealthy checks are required before a restart, a daemon waiting for you to sign in is never treated as a failure, and **Restart attempts** caps the number of restarts (0 = unlimited, default 3).
* **Revive service in background** (on by default): an inexact alarm checks every 15 minutes that the service is still alive while you want it running, and starts it again after a background kill (OEM task killers). On skins that refuse background starts the refusal is written to the log — grant the app autostart permission there.
* **Force Background Run** (Settings → TS-Core → Flags & Logs, off by default): holds a partial wake lock for the whole session so the daemon keeps servicing WireGuard/DERP keepalives in deep sleep. Costs battery.

Two rules hold regardless of these switches:

* **A manual Stop is final.** Stopping from the app, the notification, the Quick Settings tile, a `DISCONNECT` broadcast or the `disconnect` AppFunction clears the desired-running state and cancels the watchdog before any teardown runs. Neither the sticky restart, the 15-minute watchdog nor auto-reconnect revives the service after it.
* **Swiping the app out of Recents keeps the connection.** The service re-requests its own start and re-arms the watchdog, so removing the task does not disconnect you.

---

## 🤖 Gemini / AppFunctions (Android 16+)

TailSocks registers **14 AppFunctions** with the system (`android.app.appfunctions`), so an on-device assistant such as Gemini can control the app in natural language. This requires **Android 16 (API 36) or newer**; on older releases the service is not started and nothing is indexed. Verify registration with `adb shell dumpsys app_function`.

Functions run off the main thread. **All mutating functions obey the *Allow External Automation* switch** — with it off they return `success = false` and the message *"External automation is disabled in TailSocks settings."* The read-only functions always answer. The broadcast token is **not** required for AppFunctions; access is mediated by the system's `BIND_APP_FUNCTION_SERVICE` permission instead.

| Function | Type | Description |
|----------|------|-------------|
| `getStatus()` | read | Connection state, active account, exit node IP, TUN / ByeDPI / MagicDNS / LAN-access flags. |
| `getAvailableExitNodes()` | read | Peers advertised as exit nodes, with online state and which one is active. |
| `getTailnetPeers()` | read | All tailnet peers with name, IP, OS and online state. |
| `getAccounts()` | read | Configured profiles and the active one. |
| `connect(exitNodeIp)` | mutating | Starts the service, optionally selecting an exit node first; waits up to ~6 s and reports the observed state. |
| `disconnect()` | mutating | Stops the service (final, like a manual Stop). |
| `toggle()` | mutating | `disconnect()` if running, otherwise `connect("")`. |
| `selectExitNode(exitNodeIp)` | mutating | Sets the exit node by Tailscale IP (`off` / `none` / empty clears it) and pushes it to the running daemon. |
| `clearExitNode()` | mutating | Same as `selectExitNode("")`. |
| `switchAccount(accountNameOrId)` | mutating | Switches the active profile by name or ID; restarts the daemon if it was running. |
| `setByeDpi(enabled, flags)` | mutating | Enables/disables the ByeDPI bypass, optionally replacing its flags. |
| `setTunMode(enabled)` | mutating | Switches transparent TUN mode. |
| `setAllowLanAccess(enabled)` | mutating | Switches **LAN Access** (binds SOCKS5/HTTP/DNS to `0.0.0.0`). |
| `setMagicDns(enabled)` | mutating | Switches MagicDNS (`accept_dns`). |
