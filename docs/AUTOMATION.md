# ⚡ Tasker & Automation Integration Guide

TailSocks supports external background connection control via **Android Broadcast Intents**. This allows complete automation of TailSocks connections (start, stop, toggle, restart) based on system events such as connecting to specific Wi-Fi networks, SIM card changes, app launches, or schedules.

Integration works seamlessly with popular Android automation apps including **Tasker**, **MacroDroid**, **Automate (LlamaLab)**, and via **`adb`**.

---

## 🎯 Intent Specification

### Target Receiver Settings
* **Package Name:** `io.github.bropines.tailscaled`
* **Component Class:** `io.github.bropines.tailscaled.core.TaskerReceiver` *(optional)*
* **Target Type:** Broadcast Receiver

### Supported Actions

| Action | Short Alias | Description |
|--------|-------------|-------------|
| `io.github.bropines.tailscaled.action.CONNECT` | `io.github.bropines.tailscaled.START` | Starts the background service and establishes the TailSocks connection. |
| `io.github.bropines.tailscaled.action.DISCONNECT` | `io.github.bropines.tailscaled.STOP` | Safely stops the service and disconnects TailSocks. |
| `io.github.bropines.tailscaled.action.TOGGLE` | `io.github.bropines.tailscaled.TOGGLE` | Toggles the connection state (connects if disconnected, disconnects if connected). |
| `io.github.bropines.tailscaled.action.RESTART` | `io.github.bropines.tailscaled.RESTART` | Performs a full restart of the TailSocks daemon. |

---

## 📱 Tasker Setup Guide

1. Open **Tasker** and go to the **Tasks** tab.
2. Create a new task (e.g., `TailSocks Connect`).
3. Tap **`+`** to add an action → Select **System** → **Send Intent**.
4. Fill in the fields:
   * **Action**: `io.github.bropines.tailscaled.action.CONNECT`
   * **Target**: Switch to `Broadcast Receiver`
   * **Package**: `io.github.bropines.tailscaled`
   * **Class**: `io.github.bropines.tailscaled.core.TaskerReceiver` *(optional)*
5. Tap Back to save. You can now trigger this task from any Tasker Profile (Wi-Fi connected, location, time, etc.).

---

## 🤖 MacroDroid Setup Guide

MacroDroid is **fully supported**. Follow these steps:

1. Open **MacroDroid** and tap **Add Macro**.
2. In the **Actions** section, tap **`+`**.
3. Select **Device Actions** → **Send Intent**.
4. Select **Broadcast**.
5. Configure the parameters:
   * **Action**: `io.github.bropines.tailscaled.action.CONNECT`
   * **Package Name**: `io.github.bropines.tailscaled`
   * **Class Name**: `io.github.bropines.tailscaled.core.TaskerReceiver` *(optional)*
6. Tap **OK**. Add your desired triggers (e.g. *Wi-Fi SSID Connected* or *App Launched*) and save the macro.

---

## ⚙️ Automate (LlamaLab) Setup Guide

1. Create a new Flow.
2. Add a **Broadcast send** block.
3. Configure the block properties:
   * **Action**: `"io.github.bropines.tailscaled.action.CONNECT"` (in quotes).
   * **Package**: `"io.github.bropines.tailscaled"`
   * **Receiver class**: `"io.github.bropines.tailscaled.core.TaskerReceiver"`
4. Connect the block to your trigger and start the flow.

---

## 💻 ADB (Command Line) Usage

You can send broadcast intents directly from PC or Termux via `adb shell`:

```bash
# Connect TailSocks
adb shell am broadcast -a io.github.bropines.tailscaled.action.CONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Disconnect TailSocks
adb shell am broadcast -a io.github.bropines.tailscaled.action.DISCONNECT -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Toggle connection
adb shell am broadcast -a io.github.bropines.tailscaled.action.TOGGLE -n io.github.bropines.tailscaled/.core.TaskerReceiver

# Restart TailSocks daemon
adb shell am broadcast -a io.github.bropines.tailscaled.action.RESTART -n io.github.bropines.tailscaled/.core.TaskerReceiver
```
