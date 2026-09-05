# Changelog

All notable changes to the TailSocks project will be documented in this file. This project follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) standard.

## [4.0.0] - Unreleased

### Security
- **Automation was open to any app.** The Tasker/broadcast receiver honoured commands with no token by default, so any installed app could disable the VPN or reroute traffic. It now requires a secret token before acting on anything.
- **Root Mode ran untrusted names as root.** A tailnet peer's self-reported name was pasted into a root shell every minute, so a hostile device on your tailnet could run code as root on your phone. Peer names and settings fields are now treated as untrusted.
- Proxy credentials no longer leak to unrelated servers, the CLI install no longer leaves `/system` writable, stopping the root daemon no longer risks other apps' processes, and the API token and node keys are excluded from device-to-device transfer and cloud backup.
- **Root Mode boot script hardened.** The proxy environment it loads at boot is now written by root into `/data/adb/tailsocks/` and parsed rather than executed, so a file the app (or a restored backup) can write is never run as root; the script and the CLI wrapper no longer search the whole device for a library called `libtailscale.so`, which could have started another app's binary as root.
- **Backup restore is allow-listed.** Only preference files, daemon state and the sent-files history are restored; any other entry, or a path that escapes the app directory, aborts the restore before anything is written.
- **Tailnet names can no longer hijack real domains.** Root Mode publishes into `/etc/hosts` only names inside the tailnet's MagicDNS zone, treats a peer's self-reported hostname as a single label, and lets the first node keep a name — a peer calling itself `accounts.google.com` used to take over that domain for every app.
- **ByeDPI flags are allow-listed** to desync/tuning options: flags that bind the proxy to other addresses, fork, or read and write files (including ones arriving through automation, AppFunctions or a restored backup) are ignored and reported in the log.
- Files shared into the app from other apps are saved under a sanitised name, so a hostile content provider cannot write outside the working directory.

### Changed
- Control-proxy usernames and passwords are percent-encoded when the URL is built. A `/`, `?`, `#` or `%` in a password used to make the URL unparsable, and the daemon then silently connected **without** the proxy.

### Fixed
- **Connection stability**: closed a file-descriptor leak that built up until the proxy stopped working (worst in Root Mode), stopped a restart from killing the daemon it had just started, and stopped a background DNS proxy from being resurrected after the service was stopped.
- **DNS correctness**: internal tailnet names are no longer leaked to public resolvers, AAAA lookups no longer return bogus addresses, and split-DNS servers with a port or a DoH URL are no longer mangled.
- **Split DNS to a resolver on the tailnet works in TUN mode**: tailscaled's own forwarder reached a peer-hosted resolver through a plain OS socket, which has no route into the tailnet in userspace mode, so every such query timed out (`dns udp query: ... context deadline exceeded`). The daemon now dials tailnet resolvers through its own network stack (patch 14).
- **Split DNS to a resolver on the tailnet works in Root Mode**: the policy-routing mark is applied after the kernel has chosen the source address, so the daemon's queries left `tailscale0` with the Wi-Fi address and never got an answer. A destination rule for the tailnet ranges now steers them into table 1099 with the tailnet address as source.
- **Root Mode logs**: the daemon log was root-only, so the Logs screen showed nothing for the daemon; it is readable again, and "not installed" probes no longer log as errors. The Logs screen now reads only the tail of the daemon log instead of the whole file every two seconds, parses multi-line daemon entries correctly (no more glued lines and fake timestamps), clears the root-owned file through `su`, caps clipboard copies to what Android can hold (Save still exports everything), and stops yanking the list to the bottom while you are reading further up.
- **Taildrive in the system Files app**: browsing other nodes' shares failed with "SOCKS: authentication failed" whenever the SOCKS5 proxy had a password — the credential callback only answered proxy-type requests, and Android's SOCKS client asks as a server request. Credentials are now scoped by the SOCKS5 protocol and endpoint instead.
- **Taildrive shares registered too early**: shares were pushed to the daemon the moment its socket appeared, before the netmap with the node's `drive:share` capability had arrived, so the daemon answered 403 "sharing not enabled" at every start (sharing kept working only because the daemon remembers shares from the previous run). Registration now waits for the backend to reach Running.
- **Control proxy behind a hostname**: the daemon's pinned resolver refused to look up the proxy host (`dnscache: unexpected hostname`), so control-plane connections through an HTTP(S) proxy failed intermittently. The app has always pre-resolved the proxy host into `TS_STATIC_HOSTS`, but nothing in the daemon read it; it does now (patch 15).
- **Crashes**: fixed a Web UI crash, a foreground-service crash when applying settings while stopped, and a use-after-close when stopping the TUN tunnel.
- **Netstack routing fix restored**: a patch that keeps Taildrive/WebDAV working in userspace mode had been silently blanked during the v1.102.1 upgrade and is back.
- **AppFunctions revived**: the Gemini on-device integration service had been left abstract since 3.3.0, so none of the 14 `@AppFunction` entry points could ever execute. The service now bridges execute requests to the generated invoker, runs functions off the main thread, and all mutating functions respect the automation kill-switch. The service also declares the metadata properties the system indexer requires, so the functions are actually registered with Android (verified with `dumpsys app_function`).
- **Crash on Stop with the minified build**: R8 shrank an unused JNI method that the TUN library registers by name at load time, so stopping the proxy crashed the app the moment it touched the TUN class — even for users who never use TUN mode. Native methods are now kept outright, the TUN class no longer takes the process down if its library fails to load, and a release build fails if R8 ever drops a JNI method again.
- **Manual stop is final**: the stop request is now recorded before any teardown work, so a manual stop can no longer be lost to a crash and undone by the 15-minute watchdog or a sticky restart. Stopping also no longer starts the TUN service just to stop it, which used to leave a stale notification behind. Stop and start requests that overlap (a quick stop-then-start, a stop during a slow start, a restart or auto-reconnect racing a manual stop) now resolve to whatever the user asked for last instead of leaving a dead service with the watchdog armed, or a daemon running under no service.
- **In-app updater**: the downloaded APK could not be handed to the package installer (the file provider did not cover the download directory) and the fallback installer session never surfaced its confirmation dialog, so tapping "Install" did nothing. Both paths work now.
- **Widgets after an update**: widget class names are kept stable across builds so the home-screen widgets refresh right after installing a new version.
- **Go bridge hygiene**: goroutines left over from a previous daemon run can no longer configure the next one with stale options, the state snapshot is reset between runs (no more stale "Running" or login URL right after a restart), idle HTTP transports of the DNS-over-HTTPS, Taildrive and bus clients are closed instead of leaked, and Taildrop transfers are streamed to disk without the 30-second limit that used to cut large files off.
- Serve TCP targets can be entered again, editing a Serve rule keeps its PROXY setting, sending a file to an offline device reports the failure instead of "Sent!", deleting an account cleans up its daemon and stored keys, and blocking work was moved off the UI thread in several screens.

### Added
- **What's new after updates**: the first launch after an update shows this changelog's newest section; it can be turned off in Settings and opened any time from About.
- **Automation token generator**: a Generate button next to the automation secret creates a random 32-character token, and a Copy button puts it on the clipboard.
- **LAN Access**: New switch in Network settings that opens the SOCKS5 proxy, the HTTP proxy and the local DNS server to the local network (`0.0.0.0`) instead of this device only, so other devices can route through your tailnet. Shows the address to connect to and warns when the SOCKS5 proxy has no password.
- **Root Mode routing check**: A button in Root Mode settings that shows the live routing and firewall state of `tailscale0`, and failures now appear in the ROOT tab of the Logs screen instead of failing silently.
- **Auto-reconnect**: Optionally restarts the daemon when the connection does not come up or drops, with a configurable attempt limit. Waiting for you to sign in is not treated as a failure.
- **Background revival**: Optionally checks every 15 minutes that the service is still alive and starts it again after a background kill. On skins that block background starts the refusal is written to the log, so it is clear that autostart permission is what is missing.
- **System-wide DNS switch for Root Mode**: DNS redirection to MagicDNS can now be turned off, so another VPN or DNS filtering app can keep the system resolver.
- **Backups now record which version produced them**, and a restore refuses an archive made by a newer app version or in a newer format instead of overwriting the profile with data it cannot read. Backups made by earlier versions still restore.

### Fixed
- **Connection failed on the first attempt and worked on the second.** The app decided the account needed a new login while the daemon was still contacting the control plane, which discarded a working session and asked for a browser sign-in. It now waits for the daemon's own verdict, and never interrupts a session that is starting or already connected.
- **Device name sent with stray characters.** Spaces and line breaks in the device name reached the control plane as part of the node name; the name is now cleaned up when entered and repaired for existing profiles.
- **"Keep running in background" never did anything.** The switch was saved in one place and read from three others, so the wake lock was never taken — and when it was, it expired after ten minutes. It now holds for the whole session, which is what kept the connection dying overnight.
- **Connection dropping in deep sleep** is now noticed as soon as the device wakes instead of on the next scheduled check.
- **Root Mode: conflict with another VPN.** The firewall mark used for tailnet traffic overwrote Android's own routing mark, which broke routing when another VPN owned the default network. A dedicated mark bit is used instead, leaving the system's mark untouched.
- **Root Mode: external sites did not resolve.** System-wide DNS redirection also captured the Tailscale resolver's own upstream queries, so anything outside the tailnet looped back and timed out. Upstream resolvers are now excluded from the redirect.
- **Root Mode: DNS redirection with MagicDNS off.** Redirection is no longer installed when "Accept DNS" is disabled, which previously left the device without working DNS.
- **Root Mode: duplicate firewall rules.** Rules are now grouped and replaced as a unit instead of accumulating on every start; leftovers from earlier versions are removed automatically.
- **Root Mode: freezes when stopping or restarting.** Stopping no longer blocks the interface while root commands run, and restarting no longer shuts the service down before bringing it back up.
- **Root Mode: false "Active" state.** A daemon that fails to start is now reported as such instead of showing a running connection.
- **Root Mode: settings screen stutter.** Opening the Root Mode tab no longer freezes while it checks for root.
- **Quick Settings tile**: The tile showed a state it would not act on, so a tap could disconnect when it appeared disconnected. It now tracks the real connection, updates live while the panel is open, keeps its icon on all skins, unlocks the device first when needed, and opens the app if the system refuses a background start.
- **Battery and traffic**: The app no longer keeps contacting the Tailscale service before it starts or after it stops, and the tailnet host list is refreshed on a timer instead of on every status update.
- **Wrong values in settings and diagnostics**: The proxy address, DNS and route options were read from the wrong place, so the DNS test and the exported debug report showed defaults that were never in use.

## [3.5.4] - 2026-08-13

### Fixed
- Fixed text alignment and vertical clipping in search and input fields across screens and dialogs with single-line nowrap layout and compact sizing.
- Modernized borders of device name and Split Routes info blocks on the DNS page using rounded corners (8.dp).
- Fixed DNS server latency testing to perform real end-to-end network resolution.
- Restored native Back-to-Home minimize animation on the main dashboard screen.

## [3.5.3] - 2026-08-13

### Added
- Added built-in local DNS server health test with latency measurement.

### Changed
- Re-designed and modernized the DNS page with premium status cards.
- Delegated back gestures to standard system-native transitions, resolving UI freezes and revealing the actual previous screen.
- Reduced the height and padding of search input fields across the entire application.
- Simplified Serve and Funnel rules page by removing the logs tab and log polling background loop.
- Added automatic configuration refresh on opening Serve and Funnel screens.

## [3.5.2] - 2026-08-13

### Fixed
- Fixed settings full backup and restore permission denied issues when Root Mode is active.

## [3.5.1] - 2026-08-13

### Fixed
- Fixed compilation errors in Go core when building under Android targets with Tailscale v1.102.1.

## [3.5.0] - 2026-08-13

### Added
- **Settings Restructuring**: Reorganized settings tabs into an intuitive flow (`App`, `Network`, `TS-Core`, `Root Mode`, `DPI Bypass`, `Profile`), grouping TUN and proxy controls under Network, and service advertisements under TS-Core.
- **Root TUN Migration**: Enabling Root Mode while TUN mode is active automatically migrates the connection to native Linux kernel TUN (`tailscale0`).

### Fixed
- **Root Mode Native TUN (`tailscale0`)**: Enabled Linux kernel router support in Go core, allowing Root Mode to create native `tailscale0` network interfaces without occupying Android VPN slot (`tun0`).
- **Root Mode SELinux Sockets**: Fixed a crash/timeout issue on physical Android devices where SELinux Enforcing mode blocked connection between the app interface and the Root daemon socket.
- **Root Mode System-Wide DNS & Loop Bypass**: Fixed a critical DNS loop issue where native daemon requests to Split DNS servers inside the Tailnet (like custom DNS servers) were recursively hijacked by our own system-wide DNS redirection rules, causing DNS timeouts and queue blocks. Added explicit routing bypass for the CGNAT IP range (`100.64.0.0/10`) to allow direct resolution.
- **Root Mode Deferral of DNS Interception**: Deferred system-wide DNS redirection until the daemon reaches a fully authenticated `Running` state, resolving `err name not resolved` issues in the browser when attempting to log in on startup.
- **Root Mode IPTables Cleanups**: Fixed accumulation of duplicate iptables rules by ensuring aggressive cleaning loops run on every service state transition.



## [3.4.0] - 2026-08-13


### Added
- **Predictive Back Gestures**: Added smooth predictive back animations across all app screens.
- **Modern Segmented Control & Navigation**: Redesigned tab selectors and chips with smooth sliding animations and log category color coding.
- **TailFiles & Taildrive Upgrades**: Reorganized Files screen into clean sub-tabs with swipe gestures, and added full storage sharing and folder selector options for Taildrive.
- **Internal HTTP Proxy Master Toggle**: Added a master switch in settings to easily enable or disable the internal HTTP proxy.
- **Onboarding & Proxy Improvements**: Added port randomizer buttons, proxy authentication settings, and automatic hostname generation.
- **Uninstall Data Retention**: Added support for preserving user settings and profile data when uninstalling the app on supported Android versions.
- **Netcheck Controls**: Added a direct service start button when the daemon is stopped.

### Fixed
- **Root Mode Stability & Fixes**: Fixed Root authentication, resolved WSA startup ANR freezes, ensured smooth transition to Running state, included Root logs in log exports, auto-restarted daemon on mode toggle, and automatically cleaned up autostart scripts when Root mode is disabled.
- **Taildrive WebDAV Proxy**: Fixed WebDAV proxy connection errors when sharing drives.
- **UI & Layout Fixes**: Fixed text truncation and layout overflow in DNS lookup fields.
- **Localization**: Fully restored Russian translation coverage across all new screens and features.
- **Log Noise Reduction**: Demoted repetitive background status logs to reduce log spam.

## [3.3.0] - 2026-08-11

### Added
- **Android AppFunctions API Support (On-Device AI Agent Integration for Gemini)**:
  - Integrated `androidx.appfunctions:1.0.0-alpha10` with KSP compiler (`com.google.devtools.ksp:2.2.21-2.0.4`) to expose TailSocks tools to Android 16+ on-device AI assistants.
  - Implemented `TailSocksFunctions` providing `@AppFunction` entry points: `getStatus`, `getAvailableExitNodes`, `getTailnetPeers`, `getAccounts`, `connect`, `disconnect`, `toggle`, `selectExitNode`, `clearExitNode`, `switchAccount`, `setByeDpi`, `setTunMode`, `setAllowLanAccess`, and `setMagicDns`.
  - Added `TailSocksAppFunctionService` registered in `AndroidManifest.xml` with `BIND_APP_FUNCTION_SERVICE` permission.

### Fixed
- **App Update Installer Foreground Dialog Fix**:
  - Replaced legacy `Intent(ACTION_VIEW)` with Android `PackageInstaller.Session` API in `MainActivity.kt` and `Utils.kt`.
  - Resolves issue where launching an APK update collapsed the app to background without showing the system installation dialog.

## [3.2.0] - 2026-08-11

### Added
- **New Pure SVG Vector Icons & Adaptive Launcher Icon**:
  - Refactored app launcher icons to clean modern SVG vectors with adaptive icon support (`ic_launcher_background`, `ic_launcher_foreground`, and `ic_launcher_monochrome` for Android 13+ themed icons).
  - Added dedicated Quick Settings Tile vector drawable (`ic_qs_tile.xml`) containing only the sock and tail without grid/background for clean rendering on Xiaomi / MIUI / HyperOS and stock Android control panels.
  - Stored original clean SVG source file and layer SVGs in `assets/icons/`.

## [3.1.8-beta] - 2026-08-06

### Added
- **Refactored Native Root Mode (`su`)**:
  - Dual-engine architecture supporting seamless switching between hybrid userspace mode and native system root daemon with direct TUN routing (`tailscale0`).
  - Autostart boot script for Magisk / KernelSU / APatch (`service.d/tailscaled.sh`) extracted to app assets with dynamic account state directory resolution (`files/states/`).
  - Atomic Go core patch `12-socket-permissions.patch`: `tailscaled` daemon creates `tailscaled.sock` with `0666` permissions natively on startup without external watcher scripts or `chmod` loops.
  - Daemon liveness probe via real `LocalSocket` probe (`isDaemonAlive`).
- **Auto-Update of Root & CLI Scripts on App Upgrade**: Added `MY_PACKAGE_REPLACED` broadcast handler in `BootReceiver` and `AndroidManifest.xml`. Upgrading the TailSocks APK automatically refreshes `service.d/tailscaled.sh` and CLI overlay `/product/bin/tailscale` from updated app assets without user intervention.
- **Instant Mountable `/product/bin/tailscale` CLI Overlay**: `RootUtils.setTailscaleCliInstalled` mounts the CLI wrapper into `tmpfs` `/product/bin` with SELinux context `u:object_r:system_file:s0` and removes leftover Magisk `disable` flags. The `tailscale` CLI command under `su` works instantly in any shell without rebooting.
- **Added Root Mode Settings**:
  - Dedicated Root Mode settings tab (EXPERIMENTAL).
  - Script path display, "Reinstall Autostart", and "Clear Logs" action buttons.
  - Automatic root daemon log rotation on startup (>2 MB → retains last 500 lines).

### Fixed
- **Taildrive Layout & Scrolling**: Refactored `TaildriveActivity` layout to a single `LazyColumn`. Fixed a bug where expanding the Taildrive Proxy settings card covered the shared folders list and blocked vertical scrolling down.
- **Unified IPN Bus, Core & DNS Architecture**:
  - Extracted IPN Bus streaming listener into dedicated `appctr/bus.go` module.
  - Aggregated network map (`NetMap`), daemon state (`State`), peer nodes, `MagicDNS` domain, and Split DNS routes into thread-safe in-memory `busState`.
  - Eliminated HTTP polling spam to `/localapi/v0/status`: `GetBackendState()` and `GetSelfDNSName()` read state directly from `busState` without network requests.
  - Implemented exponential backoff (`2s -> 4s -> 8s -> max 30s`) in `IPNBusListener` for transient daemon restarts.
  - Removed dead `dnsCache` and redundant `syncNetMapFromBus()` extra HTTP stream.
  - Fixed `State` field type (`*int`) and `BusHealth.Warnings` schema (`map[string]struct` per Tailscale core `health.State` spec).
  - Added direct fallback querying of `/localapi/v0/dns-config` in `GetDnsStatusJSON()` to load splits before receiving the first `NetMap`.
  - Fixed handling of Split DNS routes with empty resolver lists (mapped to `100.100.100.100` MagicDNS).
  - Removed redundant DNS cache reset on Refresh button press in `DnsActivity.kt`.
- **WSA & Outbound Proxy DNS Fixes**:
  - Implemented `TS_STATIC_HOSTS` override and pre-resolution of Outbound Proxy domain to IP via direct UDP DNS (`1.1.1.1`): resolves Outbound Proxy DNS deadlock on Android/WSA while preserving original TLS SNI.
  - Exported `TS_DNS_FALLBACK="1.1.1.1,8.8.8.8"` for reliable external daemon DNS queries.

## [3.1.7] - 2026-08-05
### Added
- **Tailscale Core Upgrade (`v1.102.1`)**: Updated `tailscaled` daemon core to Tailscale `v1.102.1` across all 4 native architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) with atomic Go bridge patches.
- **Glance DataStore Widgets**:
  - **Service Toggle Widget (2×2)**: Compact Material 3 card displaying profile name, status (`● Running` / `○ Stopped`), active Exit Node IP, and instant ON/OFF toggle.
  - **Vertical Exit Node Selector Widget (2×3 / 2×4)**: Dynamic list of available Exit Nodes with one-tap switching and `● Direct / Off` routing control.
  - **Refresh Button (`↻`)**: Manual header control for instant background daemon status and netmap synchronization.
- **MIUI & HyperOS Widget Picker Previews**: Added RemoteViews XML preview layouts (`widget_preview_service.xml`, `widget_preview_exit_node.xml`) using compliant `<FrameLayout>` weight spacers to ensure accurate widget picker previews on Xiaomi HyperOS and MIUI launchers.

### Fixed
- **Sub-30ms Reactive Widget State**: Configured widget action callbacks to update Glance DataStore (`currentState<Preferences>()`) first before disk I/O, delivering instant reactive button feedback.
- **Exit Node Double-Selection Bug**: Fixed selection logic matching by enforcing strict primary IP verification (`node.ip == exitNodeIp`).
- **Persistent Exit Node Caching**: Added persistent accumulation of discovered exit nodes to prevent temporary list loss during daemon startup or netmap sync.

## [3.1.6] - 2026-07-25
### Fixed
- Fixed in-app APK auto-updater requesting root permissions on rooted devices by explicitly targeting the system `PackageInstaller` (`com.google.android.packageinstaller` / `com.android.packageinstaller`), bypassing third-party root package manager intent interceptors.
- Added APK update caching and download verification: prevents re-downloading already cached valid APKs for the target release version, cleans incomplete `.tmp` files on network error, and validates downloaded package integrity before launching installation.

## [3.1.5] - 2026-07-25
### Added
- Added **Admin API Control Server Proxy Auto-Resolution**: Admin API requests (`api.tailscale.com`) now automatically inherit the active Control Server Proxy (CP Proxy / ByeDpi / HTTP / SOCKS5) by default (`CONTROL_PLANE` mode) to prevent `403 Forbidden` and blocked DNS errors in restricted regions.
- Migrated **Admin API HTTP Engine to OkHttp**: Refactored `TailscaleApiClient` to `OkHttpClient` with pre-emptive HTTP `Proxy-Authorization` headers, SOCKS5 authentication, and automatic connection fallback.
- Added **Haptic Feedback**: Integrated tactile vibration responses (`HapticFeedbackType.LongPress`) across UI long-press interactions (account cards, console presets).
- Implemented **Daemon Readiness Checkpoint**: Added explicit `waitForDaemonReady()` check before initializing auxiliary services (Taildrive, Tags, Routes, TUN mode) to prevent startup race conditions.
- Updated **Tailscale Core**: Bumped Tailscale core to official release **`v1.98.9`** across all 4 architectures with host Go toolchain compatibility fixes.
- Added **In-App Updater**: Integrated direct APK updater and downloader with progress UI, ABI-aware release asset parsing, and `FileProvider` package installer.
- Added **Russian Documentation Localization**: Created complete Russian documentation for `readme`, `ARCHITECTURE`, `AUTOMATION`, `BUILDING`, `SERVE_FUNNEL_GUIDE`, `ADGUARD`, and `ROADMAP`.

### Fixed
- Fixed `unexpected end of stream` errors on HTTP/HTTPS proxies with authentication by sending pre-emptive `Proxy-Authorization` headers on CONNECT requests.
- Preserved active login sessions across daemon restarts and account switches by avoiding redundant LocalAPI login mutations on authenticated profiles.
- Fixed Headscale/ControlURL authorization routing during unauthenticated session initialization.
- Updated developer credits in About dialog (App Developer & Patch Developer: Bropines, Anet Patch: Asutorufa).

## [3.1.4] - 2026-07-24
### Added
- Added **Native Root Mode (`su`)**: Supports running Tailscale daemon with root privileges for direct TUN routing and Magisk/KernelSU autostart service.
- Created **Go LocalAPI SDK (`appctr/api.go`)**: Added a strongly-typed `LocalClient` struct covering 100% of LocalAPI v0 endpoints and migrated all Go subsystems (`auth`, `drive`, `taildrop`, `status`) to it.
- Created **Kotlin LocalAPI Clients**: Implemented `LocalApiClient.kt` (direct Unix `LocalSocket`) and `KotlinGoApiClient.kt` (JNI bridge).
- Redesigned **Account Picker UI**: Long-pressing account cards smoothly reveals inline **Rename** and **Delete** actions in 100% full-width layout. Fixed navigation bar padding and sheet height (`skipPartiallyExpanded = true`).

### Fixed
- Fixed Headscale/ControlURL authorization routing during unauthenticated session initialization.
- Preserved active login sessions across account switches and daemon restarts by avoiding redundant LocalAPI login mutations on already authenticated profiles.
- Enforced per-account preference isolation (`appctr_${id}`) for Exit Node selections.
- Added custom login server input field directly to the Add Account dialog.

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
