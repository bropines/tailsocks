---
name: tailsocks-android-ui
description: Expertise in Jetpack Compose, Material 3, and Android Service integration for TailSocks. Use when designing UI components, managing app state, or handling background service lifecycle.
---

# TailSocks Android UI Master

Expert in building modern, responsive Android UIs using Jetpack Compose and Material 3, specifically tailored for network utility applications.

## Design System: Material 3
- **Theming:** Use `MaterialTheme` for colors, typography, and shapes. Ensure dark/light mode compatibility.
- **Components:** Prefer `Scaffold`, `TopAppBar`, `FloatingActionButton`, and `Card` for a consistent Android experience.
- **Icons:** Use `MaterialIcons.Rounded` for a modern, soft look.

## State Management & Service Integration
- **TailscaledService:** The UI interacts with a background service. Use `StateFlow` or `MutableState` to observe connection status.
- **Auto-Refresh (The Holy Crutch):** Implement a 15-second background loop in the service that triggers `ApplySettings` to keep tags and policies synced.
- **JSON Parsing:** Use `Gson` to parse `tailscale status --json` for populating node lists and exit node selectors.

## Networking UI Logic
- **IP Randomization:** When generating local proxy addresses, use the full `127.0.0.0/8` range. Format: `127.x.y.z:port`.
- **Exit Nodes:** Provide a dedicated selector UI that filters nodes with `ExitNode: true` from the status JSON.
- **Logs Viewer:** Implement a performant list (e.g., `LazyColumn`) to display Go logs streamed from `appctr`. Use `Card` with monospace font for log entries.

## Navigation & Structure
- **Navigation Compose:** Use `NavHost` for switching between Main, Logs, Settings, and About screens.
- **Dynamic Versions:** Never hardcode version strings. The UI should display `BuildConfig.VERSION_NAME` which is derived from Git.
- **Build Tags:** Be aware of `TAGS` in `appctr`. If a feature (like SSH) is omitted in the Go core, ensure the UI doesn't provide settings for it.
