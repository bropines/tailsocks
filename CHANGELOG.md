# Changelog

All notable changes to the TailSocks project will be documented in this file. This project follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) standard.

## [3.1.4] - 2026-07-24
### Added
- Added **Native Root Mode (`su`)**: Allows running the Tailscale daemon with root privileges for direct kernel TUN routing and firewall management (resolves #3).
- Added **Magisk / KernelSU Autostart Service**: Supports installing an independent system boot script in `service.d`.
- Added Root Mode & System Service card in Settings UI.
- Created **`appctr/api.go` Production-Grade LocalAPI Module**: Refactored into a professional Go SDK with `LocalClient` struct, godoc comments, custom sentinel errors (`ErrDaemonNotRunning`, `ErrBadRequest`), and 100% LocalAPI v0 coverage (`/status`, `/profiles`, `/start`, `/prefs`, `/netcheck`, `/ping`, `/whois`, `/derp/map`, `/drive/shares`, `/file-targets`, `/serve-config`, `/set-dns`). Fully migrated all Go subsystem files (`auth.go`, `appctr.go`, `status.go`, `drive.go`, `taildrop.go`, `localapi.go`) to use `api.go` functions as a single unified source of truth.
- Created **`LocalApiClient.kt` (Pure Kotlin)**: Standalone LocalAPI client connecting directly to `tailscaled.sock` Unix Domain Socket via Android's native `android.net.LocalSocket` and raw HTTP/1.1 streaming without JNI/Go dependencies.
- Created **`KotlinGoApiClient.kt` (Go JNI Bridge)**: Dedicated Kotlin coroutine wrapper for `appctr/api.go` Go JNI bindings serving as a unified single source of truth for daemon configuration.
- Redesigned **Account Picker UI with Inline Editing & Navigation Padding**: Long-pressing an account card smoothly compresses its container in 100% full-width layout (`weight(1f)`), revealing inline **Rename** and **Delete** buttons directly in the row. Added `navigationBarsPadding()` so the bottom `+ Add` button sits cleanly above system gesture controls.

### Fixed
- Fixed **Account Picker Sheet Height**: Passed `rememberModalBottomSheetState(skipPartiallyExpanded = true)` to `ModalBottomSheet` in `MainActivity.kt` so the account picker opens fully expanded instead of snapping to a half-screen state.
- Fixed custom control plane server (Headscale / ControlURL) authorization routing by conditionally passing `UpdatePrefs.ControlURL` via `/localapi/v0/start` in `appctr` for unauthenticated sessions (`NeedsLogin`).
- Preserved existing authenticated sessions across account switches and application restarts by skipping redundant `/start` and `/login-interactive` calls when `BackendState` is `Running` or `Starting`.
- Fixed StatusCard visual state during interactive login so the main top button remains highlighted green (`ACTIVE`) while the daemon process is running.
- Enforced strict per-account isolation (`appctr_${id}`) for Exit Node selection (`exit_node_id` & `exit_node_ip`) in `MainActivity.kt` to prevent leaking exit nodes across profiles.
- Added optional custom control server (`LoginServer`) input field directly to the Add Account modal in `MainActivity.kt`.
- Removed persistent `do_reset` setting on `login_server` preference updates in `SettingsActivity.kt` to prevent accidental wiping of logged-in sessions.

## [3.1.3] - 2026-07-22
### Added
- Added `TaskerReceiver` and Broadcast Intent filters (`CONNECT`, `DISCONNECT`, `TOGGLE`, `RESTART`, `GET_STATUS`, `SET_EXIT_NODE`, `SWITCH_ACCOUNT`, `SET_BYEDPI`, `SET_TUN`) to enable Tasker, MacroDroid, and third-party background automation integration.
- Added Tasker & Automation Security settings card under Settings with master enable switch and optional security secret token authentication (`secret` parameter validation).
- Implemented real-time status event broadcasting (`io.github.bropines.tailscaled.STATUS_CHANGED`) providing `running`, `status`, `account`, `exit_node`, `tun_enabled`, and `byedpi_enabled` metrics for automation listeners.
- Created comprehensive Tasker and MacroDroid automation guide in `docs/AUTOMATION.md`.

## [3.1.2] - 2026-07-02
### Changed
- Replaced app launcher icon with a new optimized design (reduced SVG size by 33%).
- Updated adaptive icon background and foreground layers.
- Preserved the old logo assets in the repository files.

## [3.1.1] - 2026-06-11
### Security
- Fixed a partial path validation bypass vulnerability in `TailsocksFileProvider` by enforcing trailing slash validation on parent directories during child document validation.

## [3.1.0] - 2026-06-09
### Added
- Integrated native JNI implementation of ByeDPI C-library to bypass deep packet inspection (DPI) on the Tailscale control plane without spawning separate processes.
- Implemented randomized IPv4 loopback binding (random IP address in `127.2.2.2/8` and random port) for ByeDPI startup, protecting the local proxy from simple port scanning on the device.
- Added a dedicated "DPI Bypass" settings tab, displaying real-time status and active loopback IP/Port of ByeByeDPI proxy.
- Added HTTPS proxy support, parsing of proxy URI links, and one-click export/copy of current proxy configuration under Settings.
- Added `LoggedOut` state to the dashboard showing a custom card when the daemon needs authorization, offering direct shortcuts to DPI bypass/proxy settings and hints for Auth Key / OAuth login.
- Added a Compose-based `FirstStartActivity` onboarding experience to guide users through initial setup, permissions, ByeByeDPI activation, and Auth Key/OAuth login options (with explicit warning that standard browser login might block/button might not load immediately).
- Auto-exclude 6 popular Russian service packages from the TUN interface by default to prevent issues with state/banking/media services when VPN is active.
- Added support for custom proxy presets in settings and onboarding slides to easily save, apply, and delete proxy configurations.
- Added localized warning banner under ByeByeDPI settings to explain that it overrides Control Proxy configurations.
- Added compact authentication status cards on the dashboard with collapsible input fields for Auth Keys.
- Added "Force IPv4 (Disable IPv6)" option to DPI Bypass settings tab, passing the `-X` flag to JNI ByeDPI to resolve and connect only via IPv4 and prevent handshake/checksum errors on IPv6 endpoints (e.g. controlplane.tailscale.com).
- Added ConnectionIssueCard to guide previously logged-in users to configure proxy/bypass parameters when coordination server is unreachable.
- Made dashboard shortcut menu cards (Console, Peers, Logs, Settings) always visible for improved accessibility and debugging.
- Redesigned Outbound Proxy settings: renamed HTTP Proxy configuration to HTTP Proxy (Internal), added helper descriptions, and aligned options to support username/password authentication similar to SOCKS5.

### Fixed
- Fixed `tailscaled` daemon crashes (exit status 2) by removing the unsupported `--login-server` command-line argument and passing the custom Control plane URL via LocalAPI preferences (`ControlURL`).
- Fixed peer ping duration parsing to isolate only the numeric ms value, resolving incorrect values on hostnames containing "ms" (e.g., "ams-node").
- Replaced the text "Parse" button with a Paste icon button that reads the clipboard (or uses field content) for instant import.
- Deferred dashboard NeedsLogin/NoState status transitions by 10 seconds to prevent temporary network state flicker.
- Fixed StatusCard state mapping during connection issues to display the service as ACTIVE instead of STOPPED while the daemon is running.
- Fixed proxy URI parser to automatically strip fragment/hash suffixes (e.g. #tags) before processing.

## [3.0.2] - 2026-06-06
### Fixed
- Fixed release builds on GitHub Actions where the `hev-socks5-tunnel` submodule was not checked out, causing `libhev-socks5-tunnel.so` to be missing from the packaged APK and rendering TUN mode unavailable.
- Prevented application crash on account switch or startup when `libhev-socks5-tunnel.so` is missing by wrapping `System.loadLibrary` in a try-catch block and checking load status.

## [3.0.1] - 2026-06-06
### Fixed
- Fixed app crash (`ForegroundServiceDidNotStartInTimeException`) when starting or stopping the service by ensuring startForeground is called for all execution paths in TailscaledService.onStartCommand.

## [3.0.0-beta] - 2026-06-06
### Added
- Added TUN Mode status indicator and traffic routing details banner on the main dashboard.
- Added ability to customize the TUN interface IPv4 gateway address (with subnet prefix length support) under the Network settings tab.
- Added full IPv6 routing support in TUN Mode (binding fd00::1 to the interface and routing fd7a:115c:a1e0::/48 Tailscale IP range) with a dedicated switch to completely disable IPv6 routing when necessary.
- Implemented automatic exclusion of all TailSocks-related package variants from the TUN VPN tunnel to prevent routing loops, hiding them from the Excluded Apps settings screen to simplify configuration.
- Integrated remote Taildrive share mounting directly into Android Storage Access Framework (SAF) via DocumentsProvider (dynamically resolving the active account to immediately show remote shares in the root folder, bypassing unnecessary account folders), tunneling WebDAV traffic transparently through the SOCKS5 proxy.
- Added support for dynamic creation, deletion, renaming, moving, and read/write streaming of files within Taildrive directories without caching to disk.
- Added experimental TUN Mode VPN integration based on high-performance native C library `hev-socks5-tunnel`.
- Implemented full tunnel and split tunnel routing modes, automatically switched based on the active Exit Node selection (similar to the official Tailscale app).
- Added support for per-app VPN exclusions (disallowed applications list).
- Added support for customizable IP/CIDR range bypass exclusions.
- Added native JNI bindings configuration via ndk-build and externalNativeBuild.
- Integrated transparent trampoline `TunPermissionActivity` to request system VPN permission.
- Integrated TUN mode configuration UI under the Network settings tab.

### Changed
- Updated Exit Node selector UI in settings profile tab to use Material 3 Cards with OS-specific icons, aligning it with the main dashboard layout.

### Fixed
- Fixed app crash and restart (`MissingForegroundServiceTypeException`) on Android 14+ when enabling TUN mode by declaring specialUse FGS type in manifest and startForeground calls.
- Fixed UI settings visibility allowing users to configure TUN bypass preferences (excluded IPs and apps) prior to enabling the VPN.
- Fixed application hang/freeze (ANR) on TUN mode deactivation by closing the TUN file descriptor first to unblock native read/writes and running the JNI `TProxyStopService` asynchronously in a background thread.
- Fixed empty/incomplete app list in the excluded apps picker on Android 11+ by requesting the `QUERY_ALL_PACKAGES` permission and updating the query filter to include system applications with launch intents (e.g., Chrome, YouTube).
- Fixed DNS SERVFAIL errors when Exit Node is disabled by passing TS_DNS_FALLBACK env var with fallback DNS servers to the daemon.
- Fixed WebDAV custom HTTP methods (PROPFIND, MKCOL, MOVE) throwing ProtocolException on Android by implementing a reflection-based method override.
- Fixed WebDAV proxy authentication failure (SOCKS authentication failed) by registering a global Authenticator with the active SOCKS5 proxy credentials.
- Fixed Taildrive directory traversal SecurityException (not a descendant of root) in Android SAF by overriding `isChildDocument` in `TailsocksFileProvider`.
- Fixed Android network security policy blocking Taildrive HTTP traffic to `100.100.100.100` by enabling cleartext HTTP traffic in the manifest.

## [2.3.1-beta] - 2026-06-06
### Changed
- admin: Implement in-memory caching for API audit logs
- ui: Replace TabRows with chip-based swipeable pagers and add syntax highlighting in logs

## [2.3.0-beta] - 2026-06-02
### Added
- Added premium Material 3 dashboard interface for Network Diagnostics (Netcheck), displaying structured cards for protocol capabilities, NAT type, and public IP/port details.
- Added sorted DERP latency list with visual latency meter quality bars, nearest/preferred region markers, and region name resolution.
- Enriched JNI netcheck payload in Go bridge with full DERP map regions metadata mapped from tailcfg.DERPMap.
- Added in-memory caching for API audit logs in Admin Console to prevent redundant queries across activity sessions.

## [2.2.1-beta] - 2026-06-02
### Fixed
- Fixed localization desync in biometric prompts, system toasts, and non-Compose dialogs by overriding `attachBaseContext` in all Activities to apply the user-selected locale.

## [2.2.0-beta] - 2026-06-02
### Added
- Added Crowdin-compatible localization system, extracting hardcoded strings from both the core UI and the Admin Console into standard Android XML resources.
- Added comprehensive Russian translations (values-ru/strings.xml) and set up the Crowdin integration configuration (`crowdin.yml`).
- Added in-app language selection interface (chips) and configuration logic with dynamic recreation.
- Added automatic status bar and navigation bar system color styling to match the current theme.

### Fixed
- Fixed language selection chips in SettingsActivity to update the highlighted state instantly when clicked.
- Fixed console activity status bar and navigation bar solid white background bug by removing Scaffold systemBarsPadding and implementing proper edge-to-edge drawing.
- Fixed system status bar color styling not applying when a custom locale is set by resolving the Activity using findActivity() in TailSocksTheme.
- Fixed rememberLauncherForActivityResult crash by proxying activity owners in LocaleContextWrapper and explicitly providing LocalActivityResultRegistryOwner in TailSocksTheme.
- Fixed startActivity crash when launching settings with a custom locale context wrapper.
- Fixed layout alignment, center-aligned status description on main screen, and corrected various text wraps.
- Corrected Serve & Funnel translation to original English terms.

## [2.1.4-beta] - 2026-06-02
### Added
- Added comprehensive Tailscale public Admin API (api.tailscale.com/api/v2) integration to manage tailnet resources.
- Integrated profile-specific Admin API credential storage mapped to tailnet domains for shared credentials across profiles.
- Implemented a dedicated "Admin Console" dashboard with multiple tabs: Devices, DNS, Users, Services, Webhooks, Audit Logs, Web Links, and Settings.
- Added biometric authentication lock screen (fingerprint/face with device credential fallback) upon entering the Admin Console.
- Added interactive User Management (suspension, restoration, manual approval, deletion, and role configuration) inside the Users tab.
- Added Device Key Expiry controls and advertised subnet routing / Exit Node activation toggles inside the Device Detail sheet.
- Added visual ACL-parsed tag selector chips to the Edit Device Tags dialog.
- Added 60-second in-memory caching and request throttling to avoid redundant API network queries during navigation.
- Added Split DNS (Domain-specific Nameservers) and DNS Search Paths management under the DNS tab.
- Added Tailnet settings management (Device Approval, User Approval, Auto-updates, Default Key Expiry duration, Network Flow Logging, Regional Routing, Posture Identity Collection).
- Added Users list tab showing roles (Owner, Admin, Member), statuses, and device counts.
- Added OAuth Client Credentials integration to automatically fetch and refresh short-lived API access tokens in the background.
- Added Proxy settings (Direct, Local SOCKS5, Custom SOCKS5) to route API traffic (including routing via the active VPN / Exit Node).
- Added a shortcut button in the main screen's top app bar for immediate access to the Admin Console.
- Added interactive Tailscale Webhooks management (list, create, delete, and test pinging endpoints).
- Added Tailscale Virtual Services tracking and device-bound approvals/disapprovals.
- Added confirmation dialog before applying user role changes.
- Added Configuration Audit Logs viewer tab with range selector (1–30 days), action filters (CREATE, UPDATE, DELETE), keyword search, and 60-second cache throttling.
- Added Web Links tab consolidating secure-only web administration actions (Billing, SSO/IdP, ACLs, Tailnet Lock, Apps, Domain Rename).
- Added customizable device sorting modes (by Name A-Z, Name Z-A, Last Seen, Update Available).
- Added virtual services manual approval explanatory banner inside the Services tab.
- Added Auth Keys management relocated to a secure, on-demand sub-panel inside the Settings section.
- Added pending client update indicator badges (blue dot) to device list items and info card to device detail sheets.
- Integrated HorizontalPager with swipe gesture support and synchronized FilterChip tab bar for Admin Console navigation.
- Reimplemented device detail sheet rows as individual copyable blocks wrapped inside SelectionContainers.

### Changed
- Restructured the entire Kotlin codebase into modular `admin`, `core`, `models`, and `ui` packages.
- Split the monolithic `AdminApiActivity.kt` into 8 separate focused UI files under the `admin` package.
- Compacted the User Role selection picker to a 2-column grid of styled cards.
- Added horizontal padding to long node names and user login names in detail sheets to prevent screen edge clipping.
- Replaced text-based "Update" badge in device list with a small blue dot indicator next to the status marker.
- Replaced the gear proxy icon in the TopAppBar with a router icon.
- Lifted audit logs state to `AdminApiDashboardScreen` to preserve data across tab switches.

### Fixed
- Fixed false positive expired key indicator badges when key expiry is disabled on devices.

## [2.1.3-beta] - 2026-05-31
### Added
- Added native ARM 32-bit (`armeabi-v7a`) compilation and packaging support to the build system for compatibility with older EMUI/Android architectures.
- Added native Intel x86 and x86_64 compilation and packaging support to the build system for compatibility with emulators and x86-based Android devices.

## [2.1.2-beta] - 2026-05-31
### Added
- Added secure, high-performance WebDAV reverse-proxy helper in Taildrive to map the remote Quad100 (`100.100.100.100:8080`) gateway to a custom local port (default `33445`).
- Added robust Basic Authentication support to the local WebDAV proxy with a cryptographically secure, random password generator on first-time enable.
- Integrated absolute WebDAV `Destination` and `Host` header rewriting inside the reverse proxy to guarantee seamless rename, copy, and move operations from external file managers.
- Supported customizable local proxy IP addresses (e.g. `127.99.33.1` or any custom loopback address) in Taildrive proxy configuration.
- Fixed MagicDNS name resolution lookup inside the DNS diagnostic tool by automatically appending the tailnet suffix to short names.
- Fixed Tailnet-internal DNS fallback routing in userspace mode by forwarding queries to CGNAT IP addresses over SOCKS5 TCP tunnels instead of direct UDP.
- Redesigned AMOLED mode as a separate toggle to allow pure black theme when auto-switching via System theme.
- Migrated legacy `amoled` theme preference to `dark` theme with `amoled_mode` enabled.

## [2.1.1-beta] - 2026-05-28
### Added
- Added remote ProfilePicURL synchronization from LocalAPI status with automatic background caching.
- Added native circular avatar bitmap rendering in account switcher, falling back to color-coded provider badges (GitHub, Google, Headscale).
- Added OS-themed visual icons and custom card design for peer items in the Share overlay sheet.

### Changed
- Renamed the "Style" settings tab to "APP" to better reflect its purpose.
- Redesigned the "Send to..." file sharing overlay sheet into a modern contoured layout with an explicit bordered account selector.
- Redesigned the peer details bottom sheet by integrating real-time ping latency directly into the primary ping button and restoring the classic paper plane send icon.
- Upgraded cards in details and share overlays to outlined container cards using `surfaceContainerLow` for pristine contrast on AMOLED themes.
- Unified peer OS icon and color-coded styling across device lists, taildrop, and details dialogs.

### Fixed
- Fixed DNS caching when switching profiles/accounts by flushing split DNS cache, nodes cache, and resetting MagicDNS suffix.
- Fixed direct WebDAV connectivity to Taildrive at `100.100.100.100:8080` in userspace-networking mode by implementing loopback routing of self-addressed packets in netstack when a fake TUN device is used.
- Fixed `unexpected end of stream` errors when opening remote peer shares by routing remote peer WebDAV traffic through the `tsdial.Dialer` while keeping local loopback traffic routed normally.
- Fixed outbound TCP/UDP Source IP selection in netstack by automatically binding outgoing connections to the node's local Tailscale IP.

## [2.1.0-beta] - 2026-05-28
### Added
- Added experimental Taildrive support utilizing the WebDAV file server integrated into the Tailscale Go core.
- Added `MANAGE_EXTERNAL_STORAGE` permission (All Files Access) to allow sharing any folder from the device storage.
- Added dedicated Taildrive management screen (`TaildriveActivity`) to toggle the file server and manage shares.
- Added a Taildrive entry card to the main dashboard.
- Registered physical path mappings for Storage Access Framework (SAF) folder selections.
- Redesigned the entire Settings screen using modern scrollable tabs (Style, Network, Core, Profile) to completely eliminate long scroll pages.
- Implemented a custom styling and theme engine supporting System, Light, Dark, and Amoled (pure black) modes.
- Added 7 beautiful color presets (Default, Lavender, Emerald, Sapphire, Amber, Monochrome, TokioNight) with live visual color previews in Settings.
- Support for Material You dynamic colors on Android 12+ devices.

### Fixed
- Fixed a 404 Not Found issue (empty directories in Windows File Explorer) by performing case-insensitive matching on the requested share names in the WebDAV file server, aligning with the daemon's lowercased share registration.


## [2.0.9-beta] - 2026-05-28
### Added
- Added custom tags (`--advertise-tags`) and subnet routes (`--advertise-routes`) configuration to the profile settings screen.
- Real-time display of actually applied device tags fetched from the LocalAPI status response in the settings screen.
- Interactive tag suggestion chips inside the configuration dialog showing all unique active tags found across network hosts.
- Added peer tags indicator to the network devices list and details modal.
- Added swipe and button-based horizontal navigation to switch between peers in the details modal.

### Changed
- Made the peer details modal header more compact by reducing paddings, button heights, and title font sizes to fit more info on screen.
- Restricted the maximum content height of the peer details bottom sheet to 70% of the screen height (using heightIn on the inner Column) to prevent it from covering the entire screen while remaining anchored to the bottom.
- Refactored `TailsocksFileProvider` (DocumentsProvider) to expose only the `taildrop` folder for each account, preventing unauthorized access to other private application files and configs.

## [2.0.8-beta] - 2026-05-25
### Added
- Reimplemented Home Screen widgets using Jetpack Glance (Material 3 styling with dynamic colors).
- Introduced 4 new Glance widgets: Service Toggle (2x1), Exit Node Toggle (2x1), detailed Stats Dashboard (3x3), and Serve & Funnel status (2x1) with a "Purge Rules" shortcut.
- Support for immediate manual state refresh by clicking on the background of any widget.
- Added native initial/preview layouts to resolve widget preview and load inflation failures on HyperOS devices.

## [2.0.7-beta] - 2026-05-25
### Changed
- Omit `HTTP_PROXY` and `HTTPS_PROXY` environment variables when SOCKS5 proxy is used to avoid routing conflicts.
- Inject `NO_PROXY` environment variable containing the proxy host/IP to prevent connection loops.
- Modularize Tailscale source patches into separate atomic patch files.
- Add `recreate_patches.sh` utility to automatically generate atomic patches.
- Update `build.sh` to apply all patches in alphabetical order using `patch -p1`.

### Fixed
- Fixed SOCKS5 proxy support in userspace-networking mode on Android by ensuring `wrapDialer` is called even when network namespace (`netns`) routing is disabled.

## [2.0.6-beta] - 2026-05-24
### Added
- Persistent command history in Terminal console stored locally in `console_cmd_history.dat` (decoupled from clear screen action).
- Support for inserting preset commands into terminal input by long-pressing preset buttons.
- Native LocalAPI Let's Encrypt TLS certificate pair export in Serve & Funnel screen.

### Changed
- Moved text wrapping and console clearing buttons to the TopAppBar.
- Removed deprecated Auto-Refresh switch from Global Flags Settings.

### Fixed
- Serve/Funnel HTTP 404 error by replacing wildcard asterisk host key with node's FQDN in serve-config.
- Serve/Funnel HTTP 404 error when exporting TLS certificates by enabling the `/cert` endpoint compilation on Android.

## [2.0.5-beta] - 2026-05-23
### Added
- Active account name display in Quick Settings tile subtitle.
- Account switcher bottom sheet triggered directly from Quick Settings tile settings.
- Battery optimization warning card on main screen with direct settings shortcut.
- TCP-based backend health monitoring in Serve & Funnel screens.
- Serve & Funnel log viewer tab filtering requests.
- Account dropdown selection and refresh button in Share screen.

### Refactored
- Extracted Go HTTP local client and state helpers, removed unused aliases.
- Replaced tailscale up CLI call with LocalAPI /start.

### Fixed
- Version name calculation in Gradle and CI builds by fetching full git history with tags.
- Suffix tolerance in the application update checker version comparison.
- Restricted CI build triggers to execute only on Go, Kotlin, and Actions file changes.

## [2.0.4-beta] - 2026-05-18
### Added
- Quick Exit Node switcher on the main dashboard.
- Forced settings synchronization on Go core startup.
- Detailed system info in log exports.

### Fixed
- Exit Node routing (switched to StableID).
- Consolidated `debug` and `dev` build types.
- Flattened CI artifact structure.

## [2.0.0-beta] - 2026-05-07
### Added
- **Major Serve & Funnel Overhaul**: Completely redesigned the architecture for exposing services.
- **Enhanced Serve UI**: Introduced a chip-based interface for managing handlers, protocols, and host mappings.
- **Wildcard Host Mapping**: Support for `*` hostnames in node-scoped rules.
- **Improved State Management**: Reimplemented LocalAPI synchronization with a two-step "reset-then-apply" pattern to ensure configuration consistency.
- **Auto-generated Links**: The UI now automatically constructs and allows copying of service URLs based on the current configuration.

### Fixed
- **Serve Persistence**: Resolved issues where Serve configurations would desync or fail to persist after daemon restarts.
- **Funnel Visibility**: Fixed a bug preventing Funnel status from being correctly reflected in the UI.
- **TCPPortHandler Serialization**: Corrected the protocol field mapping for raw TCP handlers.

## [1.11.0] - 2026-05-07
### Added
- **Advertise Services on Android**: Enabled support for publishing Tailscale Services (`svc:`) and Serve/Funnel configurations to the coordination server.
- **C2N Protocol**: Re-enabled Client-to-Node (C2N) protocol in the core build to support remote service discovery.
- **Immediate Hostinfo Update**: Added a trigger to update Hostinfo (ServicesHash) immediately after Serve configuration changes via LocalAPI.

### Fixed
- **Android Netmon**: Fixed a potential panic and incorrect IP masking in the network monitoring layer for Android 10+.
- **SOCKS5 Support**: Enabled SOCKS support in the Tailscale core specifically for the Android environment.
- **Taildrop FS**: Improved robustness of Taildrop file operations on Android to avoid JNI panics by using a dedicated Go-based filesystem provider.
- **VIP Service Advertisement (Critical)**: Fixed `SetServeConfig` to send `PATCH /prefs` with `AdvertiseServices` + `AdvertiseServicesSet: true` after every service-scoped configuration update. Previously, the daemon's `vipServicesFromPrefsLocked` had no knowledge of the service from the Prefs side, causing the coordination server to receive an incomplete `/vip-services` response and never activating the VIP DNS entry. This mirrors the exact behavior of the official `tailscale serve --service=svc:*` CLI command.
- **AdvertiseServices Two-Step Sync**: The `AdvertiseServices` PATCH now uses a reset-then-apply pattern (Step A: clear with `[]`; Step B: apply new list), mirroring the same pattern used in `SetServeConfig`. This prevents stale `svc:` entries from persisting across renames or deletions.
- **ServeConfig HostPort Key Inconsistency**: Fixed a bug where node-scoped rules were rendered using `":port"` keys but saved using `"*:port"`, causing them to appear reset to default after a refresh. All keys are now normalized to `"*:port"` for node-scoped and `"fqdn:port"` for service-scoped rules.
- **Robust ServeConfig ETag Handling**: Improved the Go bridge to fetch a fresh ETag via `GET` if the `POST` reset response doesn't provide one, ensuring consistent `If-Match` headers and preventing silent apply failures.
- **ServeConfig Payload Sanitization**: Stripped Tailsocks-specific `etag` field from the JSON payload sent to the daemon. Re-enabled `Services` field transmission as it is required for VIP service advertisement in this version.
- **Serve Rule Edit Logic**: Fixed UI desync in `ServeActivity.kt` where editing a rule would create a duplicate instead of updating the existing one. Added `oldPort` and `oldServiceName` tracking to ensure the previous rule is correctly deleted before applying the update.

### Changed
- **Code Language**: All Russian-language comments across the `appctr/` Go package have been translated to English for consistency and maintainability.


## [1.10.0] - 2026-05-06
### Added
- **Full CLI Elimination:** The application now controls the Tailscale daemon lifecycle exclusively via the native LocalAPI (`/start`, `/logout`, `/prefs`). External binary calls are reduced to 0 for standard operations.
- **Tailscale Serve & Funnel:** Comprehensive UI for exposing local ports to the Tailnet or the public internet. Supports both raw TCP and Web (HTTPS) modes.
- **Tailscale Virtual Services:** Support for creating named services (e.g., `svc:webapp`) with dedicated TailVIPs and DNS names, managed directly from the Android UI.
- **Smart Link Generator:** Integrated DNS resolver in the Serve UI that automatically builds and copies ready-to-use URLs for hosted services, including automatic `https` switching for Funnel.
- **Rule Lifecycle Management:** Added ability to temporarily disable/enable rules without deleting them, and full editing support for existing configurations.
- **Modular Go Core:** Massive refactoring of the `appctr` module. Separated logic into `localapi.go`, `status.go`, `netcheck.go`, `dns.go`, and `taildrop.go` for better maintainability.

### Fixed
- **Optimized Settings Sync:** `ApplySettings` now uses high-speed `PATCH` requests to LocalAPI instead of restarting the configuration via CLI. This results in instant UI responsiveness and cleaner logs.
- **Reliable DNS Sync:** Increased IPN Bus Listener mask to `4095` to capture all network topology changes in real-time.
- **Profile-Specific Exit Nodes:** Fixed "phantom" exit nodes when switching accounts. Settings are now strictly isolated and force-cleared via API during profile transitions.
- **Diagnostics Reliability:** Rewrote `BackendState` detection to use robust JSON parsing, fixing intermittent "Unknown" state errors.
- **Native Logout:** Replaced the legacy CLI reset mechanism with a clean, native API logout dialog.
- **Stability Guard:** Added panic recovery in Go-JNI bridge to prevent the entire Android app from crashing during unexpected network events (e.g., server port scans).

## [1.9.0] - 2026-04-25
### Added
- **Native Local API Integration:** Replaced major CLI calls (`status`, `netcheck`, `dns query`) with high-performance HTTP requests to the `tailscaled` Unix socket.
...
