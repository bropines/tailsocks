# ⚡ Tasker & Automation Integration Guide

TailSocks supports comprehensive background automation via **Android Broadcast Intents**. This allows complete control over connections, profiles, exit nodes, DPI bypass, and TUN mode from automation apps like **Tasker**, **MacroDroid**, **Automate**, or **ADB**.

---

## 🔒 Security & Token Protection

Automation can be secured directly in TailSocks under **Settings → Tasker & Automation**:

* **Allow External Automation:** Master switch to enable/disable broadcast processing.
* **Security Secret Token (Optional):** Define a secret passkey (e.g. `my_secret_token_123`). When configured, `TaskerReceiver` will strictly reject any intent unless it carries a matching `secret`, `token`, or `key` extra string parameter.

---

## 🎯 Supported Intent Actions

### Target Receiver Specification
* **Package Name:** `io.github.bropines.tailscaled`
* **Component Class:** `io.github.bropines.tailscaled.core.TaskerReceiver` *(optional)*
* **Target Type:** Broadcast Receiver

### Control Actions

| Action | Short Alias | Extras / Parameters | Description |
|--------|-------------|---------------------|-------------|
| `io.github.bropines.tailscaled.action.CONNECT` | `io.github.bropines.tailscaled.START` | `secret` *(optional)* | Starts background daemon and connects TailSocks. |
| `io.github.bropines.tailscaled.action.DISCONNECT` | `io.github.bropines.tailscaled.STOP` | `secret` *(optional)* | Disconnects and stops background daemon. |
| `io.github.bropines.tailscaled.action.TOGGLE` | `io.github.bropines.tailscaled.TOGGLE` | `secret` *(optional)* | Toggles connection state. |
| `io.github.bropines.tailscaled.action.RESTART` | `io.github.bropines.tailscaled.RESTART` | `secret` *(optional)* | Restarts background daemon. |
| `io.github.bropines.tailscaled.action.GET_STATUS` | `io.github.bropines.tailscaled.GET_STATUS` | `secret` *(optional)* | Triggers immediate broadcast response with current metrics. |
| `io.github.bropines.tailscaled.action.SET_EXIT_NODE` | `io.github.bropines.tailscaled.SET_EXIT_NODE` | `exit_node` (String: IP or `"none"` to clear) | Sets active profile exit node IP. |
| `io.github.bropines.tailscaled.action.SWITCH_ACCOUNT` | `io.github.bropines.tailscaled.SWITCH_ACCOUNT` | `account` (String: Account ID or Name) | Switches active TailSocks profile. |
| `io.github.bropines.tailscaled.action.SET_BYEDPI` | `io.github.bropines.tailscaled.SET_BYEDPI` | `enabled` (Boolean), `flags` (String) | Configures ByeDPI control plane proxy. |
| `io.github.bropines.tailscaled.action.SET_TUN` | `io.github.bropines.tailscaled.SET_TUN` | `enabled` (Boolean) | Configures system-wide TUN mode. |

---

## 📡 Automatic Status Broadcasts (Events)

Whenever TailSocks status changes or `GET_STATUS` is requested, TailSocks broadcasts an event intent to **`io.github.bropines.tailscaled.STATUS_CHANGED`** (alias **`io.github.bropines.tailscaled.STATUS`**).

You can use **Intent Received** triggers in Tasker/MacroDroid to listen to `io.github.bropines.tailscaled.STATUS_CHANGED` and read the following Extras:

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

### 1. MacroDroid / Tasker (With Secret Token)
* **Target:** Broadcast
* **Action:** `io.github.bropines.tailscaled.action.CONNECT`
* **Package Name:** `io.github.bropines.tailscaled`
* **Extra 1:** Key: `secret`, Value: `"my_secret_token_123"`

### 2. ADB Command Examples

```bash
# Connect with security token
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT --es secret "my_secret_token_123" -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Query current status
adb shell am broadcast -a io.github.bropines.tailscaled.action.GET_STATUS -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Set Exit Node
adb shell am broadcast -a io.github.bropines.tailscaled.action.SET_EXIT_NODE --es exit_node "100.64.0.1" -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Switch Profile
adb shell am broadcast -a io.github.bropines.tailscaled.action.SWITCH_ACCOUNT --es account "Work" -n io.github.bropines.tailscaled/.core.TaskerReceiver
```
