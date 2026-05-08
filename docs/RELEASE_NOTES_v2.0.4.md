## TailSocks v2.0.4-beta - The Local API Revolution 🚀

This release marks a complete transformation of TailSocks, transitioning to a modern, CLI-less architecture and introducing major new capabilities like Serve, Funnel, Taildrop, and Multi-account management.

### 🏗 Architectural Revolution (LocalAPI)
*   **Zero CLI Calls:** The app no longer relies on executing the `tailscale` binary for standard operations. All management (Start, Login, Prefs, Status) is handled via the native **Unix Socket (LocalAPI v0)** for instant UI responsiveness and improved reliability.
*   **Modular Go Core:** The `appctr` bridge has been completely refactored into independent modules (`localapi`, `status`, `netcheck`, `taildrop`), making the core engine cleaner and more robust.
*   **State Integrity:** Implemented a **"Reset-then-Apply"** pattern for configurations, ensuring the daemon's internal state always matches the Android UI.

### 👥 Multi-Account Management
*   **Profile Isolation:** Support for multiple independent Tailscale accounts. Switch between work and personal profiles with one tap.
*   **Independent State:** Each account maintains its own machine keys, login sessions, and preferences in secure, isolated directories.
*   **Smart Transitions:** Switching accounts automatically handles daemon restarts and clears stale routing rules to prevent leaks.

### 🌐 Tailscale Serve & Funnel 2.0
*   **Overhauled UI:** A new chip-based interface for effortless configuration of HTTPS proxies, static paths, and text handlers.
*   **Virtual Services (`svc:`):** Native support for hosting services on virtual, independent hostnames with dedicated TailVIPs and DNS names.
*   **Smart Link Generator:** Automatically builds ready-to-use `https` or `tcp` URLs for your hosted services.
*   **Proxy Protocol:** Support for PROXY protocol (v1/v2) headers for raw TCP forwarding.
*   **Wildcard Hostnames:** Support for `*` host mapping in node-scoped rules.

### 📂 Taildrop: Seamless File Sharing
*   **Taildrop Hub:** A dedicated management center to view, open, and save files received from any device in your Tailnet.
*   **Universal Send:** Send files to any node in your network directly from the app or via the Android system share sheet.
*   **Storage Access Framework (SAF):** Pick any folder on your device as the permanent Taildrop destination—TailSocks handles the permissions automatically.
*   **Engine Stability:** Uses a pure-Go `FileOps` implementation to ensure high-speed transfers without JNI-related memory crashes.

### 📱 UI/UX & Quality of Life
*   **Compact Dashboard:** Reorganized the main menu into a 2x4 grid to ensure the entire UI fits on one screen without scrolling.
*   **Swipeable Navigation:** Tabs in "Files" and "Serve" now support smooth horizontal swiping.
*   **Native Pull-to-Refresh:** Added list refresh support across all key screens (Peers, Logs, Files, Serve).
*   **Modern Account Switcher:** Replaced the old dropdown with a sleek `ModalBottomSheet` for managing profiles.
*   **Live Search:** Added real-time search and filtering for the Peer list.

### 💾 Backup & Data Portability
*   **Profile Import/Export:** Export individual account settings to JSON for easy sharing or migration.
*   **Full App Backup (ZIP):** A new **"Backup (ZIP)"** feature in Global Settings creates a complete archive of all app data, including node state and machine keys.

### 🔧 Fixes & Optimizations
*   **Battery Optimization:** Added a direct shortcut to system Battery Optimization settings to help users prevent background sleep issues.

---
*Built with Tailscale Core v1.98.1*

**Full Changelog**: https://github.com/bropines/tailsocks/compare/v1.7.1...v2.0.4-beta
