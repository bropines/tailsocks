# A. Root Mode: finish verification on real devices (WSA reboot autostart, Redmi/APatch run)

Part of the [TailSocks backlog handoff](../HANDOFF_BACKLOG.md). Read that file first — it carries the ground rules and the device list.

## Why it matters

Root Mode exit-node routing (table 52 plus a pref-200 catch-all gated on the daemon's
SO_MARK line) was proven only for a MANUAL start on WSA, in commit 180f951. Two paths
still ship untested: the service.d script bringing the daemon up alone after a reboot
with the app merely attaching to it, and the whole flow on the APatch phone (Redmi).
Reading the code also shows the boot-started daemon runs with a different flag set than
the app-started one (no --socks5-server, no --outbound-http-proxy-listen,
--tun=tailscale0 forced), so after every reboot Taildrive in Files and any LAN/proxy
client are broken until the app restarts the daemon by hand.

## What is true today

- WORKING TREE IS DIRTY AND MOVING. At the start of this verification HEAD was 3800f43
  with a clean tree; while it ran, another session edited TailscaledService.kt and
  SettingsActivity.kt (both mtime 18:05) and created docs/HANDOFF_BACKLOG.md plus
  docs/handoff/*.md (18:04). `git diff` currently shows exactly the two-line Context fix
  of task 1 (applyTailscale0Routing gains `this@TailscaledService`, dumpRoutingState
  gains `context`), uncommitted, with CHANGELOG.md untouched. Run `git status --short`
  and `git diff` FIRST; all line numbers below are for the current working tree, and if
  one no longer matches, re-locate by the named function or constant.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:180`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1651`,
  `docs/HANDOFF_BACKLOG.md:83`
- The boot-script asset is templated (%PKG_NAME%, %DAEMON_BIN% replaced with the package
  name and applicationInfo.nativeLibraryDir/libtailscale.so) by
  RootUtils.setServiceScriptInstalled, copied through su to
  /data/adb/service.d/tailscaled.sh with mode 755. Install status is detected by file
  existence only — isServiceScriptInstalled runs `[ -f "$SERVICE_SCRIPT_PATH" ] && echo
  'exists'` — so there is no version marker and no content check.
  Evidence: `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:13`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:925`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:935`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:936`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:941`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:942`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:957`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:958`
- BootReceiver (BOOT_COMPLETED, QUICKBOOT_POWERON, MY_PACKAGE_REPLACED; declared
  non-exported, with the RECEIVE_BOOT_COMPLETED permission) starts TailscaledService
  only when ProxyState.isUserLetRunning (pref `desired_running`) and (force_bg or the
  action is MY_PACKAGE_REPLACED); on a boot without force_bg it instead resets
  desired_running=false. Off the main thread it then re-installs the service script and
  the CLI wrapper whenever Root Mode is on AND they are already present, so an outdated
  script is refreshed for the NEXT boot and never detected as outdated for the current
  one.
  Evidence: `app/src/main/AndroidManifest.xml:12`,
  `app/src/main/AndroidManifest.xml:119`, `app/src/main/AndroidManifest.xml:121`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:17`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:24`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:31`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:38`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:45`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:50`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:53`,
  `app/src/main/java/io/github/bropines/tailscaled/core/ProxyState.kt:9`
- tailscaled.sh (293 lines, 265 non-empty) exits 1 when /data/data/$PKG is absent;
  resolves STATE_DIR as files/states/default, else the directory of the first
  tailscaled.state found, else files/states/root; waits for sys.boot_completed (up to 60
  x 1 s) ONLY when the baked DAEMON_BIN is not executable, then derives the binary from
  `pm path` for this package; parses the root-owned
  /data/adb/tailsocks/control_proxy.env (owner uid must be 0; only `export NAME='value'`
  lines, names restricted to [A-Z_0-9]); rotates the log above 2 MB to the last 500
  lines; records LOG_START (byte size); appends the run marker `TailSocks: daemon
  start`; then launches the daemon.
  Evidence: `app/src/main/assets/scripts/tailscaled.sh:10`,
  `app/src/main/assets/scripts/tailscaled.sh:19`,
  `app/src/main/assets/scripts/tailscaled.sh:21`,
  `app/src/main/assets/scripts/tailscaled.sh:25`,
  `app/src/main/assets/scripts/tailscaled.sh:39`,
  `app/src/main/assets/scripts/tailscaled.sh:41`,
  `app/src/main/assets/scripts/tailscaled.sh:42`,
  `app/src/main/assets/scripts/tailscaled.sh:65`,
  `app/src/main/assets/scripts/tailscaled.sh:68`,
  `app/src/main/assets/scripts/tailscaled.sh:70`,
  `app/src/main/assets/scripts/tailscaled.sh:82`,
  `app/src/main/assets/scripts/tailscaled.sh:94`,
  `app/src/main/assets/scripts/tailscaled.sh:105`
- DIVERGENCE. The boot script starts the daemon with `--statedir --socket
  --tun=tailscale0` only: no --socks5-server, no --outbound-http-proxy-listen, and TUN
  is hard-coded regardless of the `root_tun_enabled` preference (default true).
  RootUtils.startRootDaemon, used when the app starts the daemon itself, adds
  --socks5-server (unless the address is empty or "none"), --tun=tailscale0 or
  --tun=userspace-networking depending on tunMode, and --outbound-http-proxy-listen when
  set; TailscaledService.buildStartOptions fills those from
  GlobalSettings.getSocks5BindAddr (DEFAULT_SOCKS5 = 127.0.0.1:48115) and
  getHttpProxyBindAddr, and passes tunMode = isRootTunEnabled.
  Evidence: `app/src/main/assets/scripts/tailscaled.sh:108`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:320`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:321`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:324`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:326`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:329`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:667`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:671`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:817`,
  `app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt:137`,
  `app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt:314`
- `--socks5-server` is a plain tailscaled flag with default "" (cmd/tailscaled/proxy.go,
  flag registration); TS_SOCKS5_SERVER / TS_OUTBOUND_HTTP_PROXY_LISTEN are read only by
  cmd/containerboot, never by tailscaled. So the boot script cannot enable the SOCKS5
  listener through an environment variable — it must build the flag. SOCKS5 credentials,
  in contrast, DO travel by environment: patch 02 makes the SOCKS server read
  TS_SOCKS5_USER / TS_SOCKS5_PASS, which appctr/daemon.go sets for the non-root path.
  RootUtils.startRootDaemon never exports them, so the root daemon's SOCKS5 listener is
  unauthenticated today.
  Evidence: `appctr/tailscale_src/cmd/tailscaled/proxy.go:34`,
  `appctr/patches/02-socks5-auth.patch:12`, `appctr/daemon.go:105`,
  `appctr/tailscale_src/cmd/containerboot/settings.go:121`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:267`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:296`
- The Go bridge depends on that SOCKS5 port: the Taildrive proxy refuses to start
  without it (`SOCKS5 proxy is not running`), the app DNS proxy tries SOCKS first and
  only then direct UDP, and the DNS status screen reads it. AttachExternal stores the
  CONFIGURED address in GConfig unconditionally, so after a reboot the bridge keeps
  dialling 127.0.0.1:48115 on a daemon that has no listener there.
  Evidence: `appctr/appctr.go:596`, `appctr/appctr.go:615`, `appctr/drive.go:178`,
  `appctr/drive.go:180`, `appctr/dns.go:308`, `appctr/dns.go:311`, `appctr/status.go:33`
- File modes diverge too: the script chmods the log 0666 right after launch and, once
  the socket appears, the socket 0777, chcon u:object_r:app_data_file:s0, and the state
  dir 0777. The app's launcher uses log 0644, socket 0666, state dir 0700. Both invoke
  `magiskpolicy --live "allow untrusted_app magisk unix_stream_socket connectto" ||
  supolicy --live ... || true` by bare name — no absolute path, no `su`-domain variant.
  Evidence: `app/src/main/assets/scripts/tailscaled.sh:109`,
  `app/src/main/assets/scripts/tailscaled.sh:110`,
  `app/src/main/assets/scripts/tailscaled.sh:114`,
  `app/src/main/assets/scripts/tailscaled.sh:115`,
  `app/src/main/assets/scripts/tailscaled.sh:116`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:343`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:344`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:347`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:349`
- Once tailscale0 exists (polled up to 30 x 1 s) the script installs the 4.0 layout:
  table 1099 route for 100.64.0.0/10, the two pref-100 rules (fwmark 0x1000000/0x1000000
  and `to 100.64.0.0/10`), TAILSOCKS_MARK, then TAILSOCKS_DNS gated on neither
  accept_dns nor root_dns_redirect being "false" in shared_prefs/tailsocks_global.xml;
  purges stale 5210/5230/5250/5270 rules in both families; waits up to 20 x 1 s for
  `netns: SO_MARK 0x2000000 set on tailscaled sockets` in the log after LOG_START
  (excluding lines containing `TailSocks:`); then adds `ip rule add fwmark 0x0/0x2020000
  iif lo lookup 52 priority 200`, reads it back and deletes it again if the mask was
  dropped, saves rp_filter to /data/adb/tailsocks/rp_filter.orig and sets all=2. The two
  negative branches append `TailSocks: exit node unavailable: ...` to the daemon log.
  Evidence: `app/src/main/assets/scripts/tailscaled.sh:121`,
  `app/src/main/assets/scripts/tailscaled.sh:136`,
  `app/src/main/assets/scripts/tailscaled.sh:138`,
  `app/src/main/assets/scripts/tailscaled.sh:143`,
  `app/src/main/assets/scripts/tailscaled.sh:171`,
  `app/src/main/assets/scripts/tailscaled.sh:174`,
  `app/src/main/assets/scripts/tailscaled.sh:208`,
  `app/src/main/assets/scripts/tailscaled.sh:230`,
  `app/src/main/assets/scripts/tailscaled.sh:231`,
  `app/src/main/assets/scripts/tailscaled.sh:244`,
  `app/src/main/assets/scripts/tailscaled.sh:248`,
  `app/src/main/assets/scripts/tailscaled.sh:261`,
  `app/src/main/assets/scripts/tailscaled.sh:268`,
  `app/src/main/assets/scripts/tailscaled.sh:270`
- The daemon side: patch 16 (net/netns/netns_android.go, useBypassMark) probes SO_MARK
  once, returns early unless uid==0, and logs exactly one of `netns: SO_MARK unavailable
  (...)` or `netns: SO_MARK 0x2000000 set on tailscaled sockets (root bypass)`. Patch 13
  forces ipRuleAvailable on Android so routes always land in table 52
  (tailscaleRouteTable = 52) and adds purgeDesktopIPRulesAndroid, called from the
  Android branch of addIPRules at Up. cmd/tailscaled logs
  `wgengine.NewUserspaceEngine(tun "...") ...` on every start, which RootUtils uses as a
  second run boundary next to the run marker.
  Evidence: `appctr/patches/16-android-somark.patch:61`,
  `appctr/patches/16-android-somark.patch:62`,
  `appctr/patches/16-android-somark.patch:69`,
  `appctr/patches/16-android-somark.patch:73`,
  `appctr/patches/13-android-osrouter.patch:59`,
  `appctr/patches/13-android-osrouter.patch:165`,
  `appctr/patches/13-android-osrouter.patch:199`,
  `appctr/tailscale_src/wgengine/router/osrouter/router_linux.go:1657`,
  `appctr/tailscale_src/cmd/tailscaled/tailscaled.go:762`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:85`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:86`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:101`
- App start in Root Mode: RootUtils.isDaemonAlive does a real LocalSocket connect, then
  /status is fetched; the app ATTACHES (`Root daemon is already running. Attaching to
  existing socket with full options.` then Appctr.attachExternal) only when the status
  parsed and does not contain BackendState "NoState". Otherwise, if the socket file
  exists, it logs `Root daemon socket is stale or unconfigured. Stopping leftover daemon
  and restarting.`, runs stopRootDaemon (which first runs cleanupTailscale0Routing) and
  starts its own daemon with the full flag set. The app's state dir is
  files/states/<activeAccount.id>, while the script picks `default` or the first state
  file it finds.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:641`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:643`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:646`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:653`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:654`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:657`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:658`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:662`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:775`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:224`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:898`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:899`
- THE DEFECT THIS BRIEF STARTED FROM, AND ITS CURRENT STATE. runRefreshTick applies
  routing once the backend is Running (only while Root Mode AND Root TUN are on). At
  commit 3800f43 it called `RootUtils.applyTailscale0Routing(dnsRedirect, bypass)` with
  no Context, so applyTailscale0Routing fell back to lastDaemonLogFile — set only by
  startRootDaemon in this process. After a reboot where the app merely attached, that is
  null, the verdict is `no daemon log to check`, a WARN `exit node unavailable: daemon
  does not mark sockets (...)` is logged and the app installs no pref 200 (it also does
  not delete the script's rule: catchAllInstall, which contains the only pref-200 delete
  on the apply path, runs only when marks==true). Settings -> Check Routing had the same
  gap and printed `(daemon log path unknown)`. BOTH call sites now pass a Context in the
  working tree, uncommitted and unbuilt.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:164`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:180`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:454`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:461`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:466`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:467`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:469`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:503`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:695`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:767`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:799`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:828`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:860`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1651`
- Stop path: shutdownDaemon detaches the bridge in Root Mode and calls
  removeRootArtifacts(killDaemon = !rootMode || shouldKillRootDaemonOnStop (pref
  root_kill_daemon_on_stop, default true)); removeRootArtifacts runs
  cleanupTailscale0Routing only when isRootRoutingInstalled is set.
  cleanupTailscale0Routing removes the pref-100 and pref-200 rules, the chains, table
  1099 and the legacy/stale rules, restores rp_filter and deliberately leaves table 52
  to the daemon. onDestroy without stopMe keeps the daemon alive when the service script
  is installed, but still removes the rules.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:959`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:967`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:976`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:992`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:1002`,
  `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt:1281`,
  `app/src/main/java/io/github/bropines/tailscaled/core/GlobalSettings.kt:317`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:621`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:625`
- Root Mode UI: isRootAvailable runs `id` through su with a 10 s timeout and requires
  `uid=0`; the Root Mode dialog calls it off the main thread and shows `Root access (su)
  not granted or unavailable` on failure. A LaunchedEffect probes script/CLI presence
  off the main thread; the autostart switch is "Autostart Magisk/KernelSU Service
  (EXPERIMENTAL)" and a "Reinstall Script" button re-runs
  setServiceScriptInstalled(context, true); the TUN switch is labelled "Native Linux TUN
  (tailscale0)"; Daemon Status polls isDaemonAlive every 3 s and shows `Running (socket
  responding)` / `Not running / socket not responding`. ProxyState.isActualRunning
  probes the same socket for the dashboard.
  Evidence: `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:237`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:238`,
  `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt:240`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1308`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1347`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1362`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1444`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1483`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1520`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1608`,
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1637`,
  `app/src/main/res/values/strings.xml:757`, `app/src/main/res/values/strings.xml:764`,
  `app/src/main/java/io/github/bropines/tailscaled/core/ProxyState.kt:36`
- The "Force Background Run" switch (settings_force_bg_title) is what writes the
  `force_bg` preference BootReceiver reads — the code comment calls it "Keep running in
  background", but no such label exists in the UI.
  Evidence:
  `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt:1285`,
  `app/src/main/res/values/strings.xml:862`,
  `app/src/main/java/io/github/bropines/tailscaled/core/BootReceiver.kt:22`
- Diagnostic tooling predates the 4.0 layout. tools/root-debug.sh filters `ip rule list`
  on `1099|0x1000000`, so the pref-200 rule and table 52 are invisible; it prints table
  1099 only; its only marked route lookup uses the app mark 0x1000000 on
  100.100.100.100, never the bypass mark 0x2000000 on 8.8.8.8; it has no rp_filter,
  run-marker, SO_MARK, service.d or env-file section (only the last 40 daemon log
  lines). tools/root-debug-session.sh takes its snapshots under `su -c` but starts and
  stops the service with a plain `am start-foreground-service`, which a release build
  refuses from a non-root adb shell.
  Evidence: `tools/root-debug.sh:58`, `tools/root-debug.sh:59`,
  `tools/root-debug.sh:61`, `tools/root-debug.sh:69`, `tools/root-debug.sh:71`,
  `tools/root-debug.sh:103`, `tools/root-debug-session.sh:41`,
  `tools/root-debug-session.sh:53`, `tools/root-debug-session.sh:67`
- What was verified before this brief: commit 180f951's message records a manual start
  on WSA (Magisk, x86_64) with the pref-200 rule present, table 52 gaining and losing
  `default dev tailscale0` with the exit node, `ip route get 8.8.8.8` -> tailscale0
  unmarked / eth0 with mark 0x2000000, and the external IP being the exit node's.
  docs/ROOT.md documents the boot script in section 1, the BootReceiver refresh in
  section 3, the routing mechanism and the script's 20 s wait in section 4, Check
  Routing in section 5, Stop behaviour in section 6, and the tools/ scripts in
  "Diagnostic scripts" further down.
  Evidence: `docs/ROOT.md:9`, `docs/ROOT.md:15`, `docs/ROOT.md:26`, `docs/ROOT.md:30`,
  `docs/ROOT.md:104`, `docs/ROOT.md:106`, `docs/ROOT.md:142`, `docs/ROOT.md:156`,
  `docs/ROOT.md:239`, `docs/research/root-exit-node-design.json:166`,
  `docs/research/root-exit-node-design.json:268`,
  `docs/research/root-exit-node-design.json:281`,
  `docs/research/root-exit-node-design.json:284`
- Build artifacts on disk are current and DO contain the SO_MARK core: release APKs for
  all ABIs are dated 2026-09-05 16:54 (HEAD 3800f43 is 16:52, the Go/patch commit
  180f951 is 16:28), appctr/tmp/appctr.aar and the jniLibs libtailscale.so are 16:03,
  and `strings` finds `set on tailscaled sockets` in all four jniLibs binaries and in
  lib/x86_64/libtailscale.so inside app-x86_64-release.apk, whose bundled
  assets/scripts/tailscaled.sh already carries the run marker. Gradle's
  verifyGoBridgeFresh (fails a release when any appctr/*.go or patch is newer than the
  AAR) therefore passed for that build. build.sh applies patches with `patch -p1 --batch
  --forward -F0`, builds with ts_omit_iptables and copies daemon and CLI into
  app/src/main/jniLibs/<abi>/; recreate_patches.sh regenerates patch 16 from
  net/netns/netns_android.go.
  Evidence: `appctr/build.sh:70`, `appctr/build.sh:90`, `appctr/build.sh:166`,
  `appctr/build.sh:170`, `appctr/patches/recreate_patches.sh:98`,
  `appctr/patches/recreate_patches.sh:103`, `app/build.gradle.kts:253`,
  `app/build.gradle.kts:268`
- agents.md points at docs/HANDOFF_BACKLOG.md for "device verification of Root Mode".
  That file now exists (created 18:04 by the concurrent session, untracked) and its
  section 3.A links this topic file, docs/handoff/backlog-a-root-devices.md, which is
  where results belong. Both agents.md and CLAUDE.md are also modified and uncommitted.
  Evidence: `agents.md:158`, `docs/HANDOFF_BACKLOG.md:83`, `docs/HANDOFF_BACKLOG.md:88`

## Already done since the brief was written

Two of the tasks below were closed while this file was being produced. They are
kept for their context but need no work:

- **A1** — the attach path now passes a Context, so the SO_MARK check reads the
  daemon log by its canonical path and the pref-200 catch-all is installed for a
  daemon the boot script started. Commit `b96cea2`.
- **A7** — the SELinux rule is no longer a fixed `allow untrusted_app magisk …`
  applied at every start and every boot. Both domains are read from `/proc` at
  runtime and one `connectto` rule is injected only after a connect was actually
  denied (`RootUtils.allowSocketConnect`). Commit `22a49ae`. What remains is
  exactly the device check: on the APatch Redmi, confirm the domains resolve and
  that a policy tool accepts the rule. If the real denial is not `connectto` on
  `unix_stream_socket`, paste the `dmesg | grep avc` line and fix the rule.

Verified on WSA today, after those commits: Root Mode reaches Running with no
policy rule preinstalled and no AVC denial, and a legacy Taildrop file is
migrated into `files/taildrop/<account>`.

## Tasks

### - [x] A1. Land and prove the attach-path Context fix (already in the working tree)

**Effort** S &nbsp;·&nbsp; **Files** `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt`, `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt`, `CHANGELOG.md`

**Steps**

1. Run `git status --short` and `git diff`. Expect exactly two code hunks:
   TailscaledService.kt runRefreshTick now calls
   `RootUtils.applyTailscale0Routing(dnsRedirect, bypass, this@TailscaledService)` (line
   180) and SettingsActivity.kt line 1651 now calls
   `RootUtils.dumpRoutingState(context)`. If they are absent, make those two edits; if
   they are present, do NOT redo them.
2. Verify the effect in RootUtils: with a Context, applyTailscale0Routing and
   dumpRoutingState resolve the log through rootDaemonLogFile(context)
   (RootUtils.kt:466, 829, 878) instead of lastDaemonLogFile, and soMarkVerdict then
   bounds the run by the script's `TailSocks: daemon start` marker or by
   `wgengine.NewUserspaceEngine(tun` (RootUtils.kt:799).
3. Add the CHANGELOG.md line under [4.0.0] - Unreleased / Fixed (it is still missing):
   after a reboot the app attached to the daemon the autostart script had started but
   could not check whether that daemon marks its sockets, so it logged `exit node
   unavailable` and never installed the exit-node rule itself; it now reads the daemon
   log by its canonical path, and Check Routing shows the same verdict instead of
   `(daemon log path unknown)`.
4. No Go change, so appctr/build.sh is not needed. Build an APK (release needs the four
   KEYSTORE_* variables) and commit locally; never push.
5. Then run the WSA reboot checklist in the next task — that is what actually proves the
   fix.

**Done when**

- After a reboot with the service script installed and the app attaching, the ROOT log
  contains `tailscale0 routing applied (table 1099, exit-node catch-all pref 200 ->
  table 52: installed, ...)` and NOT `exit node unavailable: daemon does not mark
  sockets (no daemon log to check)`.
- Settings -> Root Mode -> Check Routing after that reboot prints the last `TailSocks:
  daemon start` line, the `netns: SO_MARK 0x2000000 set on tailscaled sockets (root
  bypass)` line and `gate: daemon marks sockets (daemon logged ...)`.
- A manual start on WSA behaves as before (startRootDaemon still records the byte
  offset, RootUtils.kt:261-262).

**How to test**

- adb -s 127.0.0.1:58526 install -r app/build/outputs/apk/release/app-x86_64-release.apk
- adb -s 127.0.0.1:58526 shell su -c 'am start-foreground-service -n
  io.github.bropines.tailscaled/.core.TailscaledService -a START_ACTION'
- adb -s 127.0.0.1:58526 logcat -d | grep -E 'Attaching to existing socket|routing
  applied|exit node unavailable'
- adb -s 127.0.0.1:58526 shell su -c "ip rule show | grep -E '^200:.*fwmark
  (0x0|0)/0x2020000.*lookup 52'"

**Risks**

- Functionally safe: with a Context the verdict can only become better informed, and a
  daemon started by hand (no run marker) is still bounded by the
  `wgengine.NewUserspaceEngine(tun` line.
- The log is root-owned, 0666 when the script wrote it and 0644 when the app did; if it
  were unreadable the verdict becomes `cannot read ...` and pref 200 is withheld, which
  is the safe direction.
- Another session is editing these two files; re-read them before editing and do not
  clobber its work.

### - [ ] A2. WSA (Magisk, x86_64): verify reboot autostart via service.d and the app's attach (force_bg on and off)

**Effort** M &nbsp;·&nbsp; **Files** `docs/handoff/backlog-a-root-devices.md`, `docs/HANDOFF_BACKLOG.md`, `docs/ROOT.md`, `docs/ROOT_RU.md`, `CHANGELOG.md`

**Steps**

1. Preconditions: install app-x86_64-release.apk. In the app enable Root Mode (grant su
   in Magisk), keep "Native Linux TUN (tailscale0)" ON, switch ON "Autostart
   Magisk/KernelSU Service (EXPERIMENTAL)", keep "Terminate Root Daemon on Stop" ON.
   Start, log in, select an exit node, and reproduce the manual state from commit
   180f951 (pref-200 rule present, table 52 holds `default dev tailscale0`).
2. Before rebooting, under su: `ls -l /data/adb/service.d/tailscaled.sh` (755, root);
   `grep -n '^PKG=\|^DAEMON_BIN=' /data/adb/service.d/tailscaled.sh` and `test -x` that
   path (must be the real /data/app/.../lib/x86_64/libtailscale.so); `ls -lZ
   /data/adb/tailsocks/; cat /data/adb/tailsocks/control_proxy.env` (root-owned 0600,
   only `export NAME='...'` lines: TS_LOGS_DIR, TS_NO_LOGS_NO_SUPPORT, TS_AUTH_ONCE,
   TS_DNS_FALLBACK, plus TS_TAILDROP_DIR and proxy variables when configured).
3. Variant A (app attaches at boot): Settings -> "Force Background Run" ON, service
   running. Reboot with `adb -s 127.0.0.1:58526 shell su -c reboot`; WSA usually
   terminates instead, so the author restarts it from Windows, then `adb connect
   127.0.0.1:58526`. Wait for `getprop sys.boot_completed` = 1 plus roughly a minute.
4. Run the checklist below and record every result. Then Variant B: "Force Background
   Run" OFF, reboot again — BootReceiver then does not start the service and resets
   desired_running (BootReceiver.kt:38-40). Run the checklist again, then open the app:
   the dashboard must show the daemon alive (ProxyState.isActualRunning probes the
   socket) and pressing Start must attach without launching a second daemon.
5. Outdated-script check: `su -c "sed -i
   's|^DAEMON_BIN=.*|DAEMON_BIN=\"/nonexistent/libtailscale.so\"|'
   /data/adb/service.d/tailscaled.sh"`, reboot. Expected: the script waits for
   sys.boot_completed, derives the binary from `pm path` (tailscaled.sh:39-47) and still
   starts the daemon; and after BOOT_COMPLETED the app rewrites the script
   (BootReceiver.kt:50-51), so `grep '^DAEMON_BIN=' /data/adb/service.d/tailscaled.sh`
   shows the real path again and its `stat -c %y` is newer than the boot. App-update
   check: `adb install -r` the same APK -> MY_PACKAGE_REPLACED -> script mtime changes
   and the service resumes even with force_bg off (commit 3800f43).
6. Write the outcome (pass/fail per check, device, date, versionName which carries the
   git hash) into docs/handoff/backlog-a-root-devices.md and tick section 3.A in
   docs/HANDOFF_BACKLOG.md; touch CHANGELOG.md only if behaviour changed.

**Done when**

- After the reboot exactly one libtailscale.so process exists, started by the script
  (its cmdline has `--tun=tailscale0` and no `--socks5-server`), and its socket answers
  (Settings -> Root Mode shows `Running (socket responding)`).
- The daemon log for this boot contains `TailSocks: daemon start`, then `netns: SO_MARK
  0x2000000 set on tailscaled sockets (root bypass)`, and no `TailSocks: exit node
  unavailable` line after the marker.
- `ip rule show` has both pref-100 rules and a pref-200 line matching `fwmark
  (0x0|0)/0x2020000 ... lookup 52`, and no 5210-5270 rules; table 1099 holds
  `100.64.0.0/10 dev tailscale0 metric 1`; table 52 holds peer /32s plus `default dev
  tailscale0` while an exit node is set; `ip route get 8.8.8.8` picks tailscale0 while
  `ip route get 8.8.8.8 mark 0x2000000` picks eth0;
  /proc/sys/net/ipv4/conf/all/rp_filter is 2 with /data/adb/tailsocks/rp_filter.orig
  present if any interface was strict; TAILSOCKS_MARK and TAILSOCKS_DNS exist.
- Variant A: logcat shows `Root daemon is already running. Attaching to existing socket
  with full options.` and never `Root daemon socket is stale or unconfigured`; the
  `TailSocks: daemon start` count in the log does not grow when the app starts; the
  pref-200 rule is still present after the app's apply.
- Tailnet ping and exit-node egress work after the reboot; Stop with Terminate ON
  removes the pref 100/200 rules and table 1099, restores rp_filter and kills the
  daemon.

**How to test**

- Setup inside `adb -s 127.0.0.1:58526 shell su`: PKG=io.github.bropines.tailscaled;
  D=/data/data/$PKG; LOG=$D/logs/tailscaled.log; SOCK=$D/files/tailscaled.sock; CLI=$(ls
  /data/app/*/$PKG-*/lib/x86_64/libtailscale_cli.so | head -1); TS="$CLI --socket=$SOCK"
- 1. pgrep -af libtailscale.so -> exactly one line of the form `.../libtailscale.so
  --statedir=$D/files/states/<dir> --socket=$SOCK --tun=tailscale0`. If none: `tail -30
  $LOG` (look for `TailSocks daemon binary not found`), `ls -l /data/adb/service.d/`,
  `magisk -v`.
- 2. M=$(grep -n 'TailSocks: daemon start' $LOG | tail -1 | cut -d: -f1); tail -n +$M
  $LOG | grep -E 'netns: SO_MARK|TailSocks: exit node|wgengine.NewUserspaceEngine' ->
  expect the NewUserspaceEngine line and `netns: SO_MARK 0x2000000 set on tailscaled
  sockets (root bypass)`, and no `exit node unavailable`. If it says `did not report
  SO_MARK support within 20s`, compare the timestamps of the marker and the SO_MARK
  line: a daemon slower than 20 s at boot means the wait at tailscaled.sh:230 is too
  short.
- 3. ip rule show; ip -6 rule show; ip route show table 1099; ip route show table 52; ip
  route get 8.8.8.8; ip route get 8.8.8.8 mark 0x2000000; ip route get 100.100.100.100;
  cat /proc/sys/net/ipv4/conf/all/rp_filter; ls -l /data/adb/tailsocks/; iptables -t
  mangle -S TAILSOCKS_MARK; iptables -t nat -S TAILSOCKS_DNS; iptables -S FORWARD | grep
  tailscale0
- 4. (Variant A) adb -s 127.0.0.1:58526 logcat -d | grep -E 'already running|stale or
  unconfigured|Applying Root|routing applied|exit node unavailable|service-script'; grep
  -c 'TailSocks: daemon start' $LOG before and after the app start (must be equal); ip
  rule show | grep '^200:'
- 5. $TS status | head -5; $TS ping -c 2 <peer-ip>; $TS dns query example.com A; ping
  -c1 google.com; if a fetch tool exists (busybox wget or curl) check the egress IP
  against the exit node's.
- 6. Divergence evidence: `netstat -ltn 2>/dev/null | grep 48115` or `ss -ltn | grep
  48115` -> expect NOTHING after a script start; then open Files -> Taildrive -> a peer
  share and expect it to fail while attached to a script-started daemon. Record both for
  the parity decision.
- 7. Stop: su -c 'am start-foreground-service -n
  io.github.bropines.tailscaled/.core.TailscaledService -a STOP_ACTION'; sleep 10; ip
  rule show | grep -E '^(100|200):' (empty); ip route show table 1099 (empty); cat
  /proc/sys/net/ipv4/conf/all/rp_filter (restored); ls /data/adb/tailsocks/ (no
  rp_filter.orig); pgrep -c -f libtailscale.so (0).

**Risks**

- `su -c reboot` kills the WSA VM; the author must restart it from Windows and re-run
  `adb connect 127.0.0.1:58526`.
- WSA has no global IPv6, so `TailSocks: IPv6 exit-node catch-all not installed` and the
  app's matching INFO line are expected, not failures.
- If BOOT_COMPLETED reaches the app before the daemon leaves NoState, the app kills and
  restarts it (TailscaledService.kt:646-658); that shows up as a second run marker plus
  `stale or unconfigured` in logcat — record it and see the boot-race task.
- The exit-node choice is a daemon preference and survives reboots, so egress goes
  through the exit node immediately after boot; if that node is offline the device has
  no internet until the exit node is cleared.

### - [ ] A3. Redmi (Android 16, APatch): preflight, root detection, SELinux/socket access, SO_MARK, then reboot autostart

**Effort** L &nbsp;·&nbsp; **Files** `docs/handoff/backlog-a-root-devices.md`, `docs/HANDOFF_BACKLOG.md`, `docs/ROOT.md`, `docs/ROOT_RU.md`, `app/src/main/assets/scripts/tailscaled.sh`, `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt`

**Steps**

1. Preflight in `adb -s 192.168.1.83:5555 shell`: `su -c id` (uid=0 plus the SELinux
   context), `su -c 'echo $PATH; which magiskpolicy supolicy; ls /data/adb/ap/bin'`, `su
   -c 'ls -ld /data/adb/service.d; ls -l /data/adb/service.d'`, `su -c 'which ip
   iptables ip6tables; ip -V; iptables -V'`, `getenforce`, `getprop ro.crypto.type
   ro.crypto.state`, `su -c 'cat /proc/sys/net/ipv4/conf/all/rp_filter
   /proc/sys/net/ipv4/conf/wlan0/rp_filter'`.
2. Install with `adb -s 192.168.1.83:5555 install -r
   app/build/outputs/apk/release/app-arm64-v8a-release.apk` (never uninstall). Enable
   Root Mode (the author grants su in the APatch manager) and confirm logcat shows
   `RootUtils: Root check exitCode=0 output=uid=0...`. Switch on Autostart, start, log
   in, select an exit node, and take a baseline with tools/root-debug.sh (`adb push
   tools/root-debug.sh /data/local/tmp/; adb shell su -c 'sh
   /data/local/tmp/root-debug.sh'`).
3. SELinux/socket check: `su -c "cat /proc/$(pidof libtailscale.so)/attr/current"`, `su
   -c ls -lZ $SOCK` (expect u:object_r:app_data_file:s0 and mode 777 for a script start,
   666 for an app start), `logcat -d -b all | grep -i 'avc: *denied' | grep -iE
   'connectto|tailscale'`. Settings -> Root Mode must read `Running (socket
   responding)`; if it reads `Not running / socket not responding` while pgrep shows the
   daemon, the app is denied connectto — go to the sepolicy task.
4. SO_MARK under APatch: `su -c "grep 'netns: SO_MARK' $LOG | tail -1"` must be the `set
   on tailscaled sockets (root bypass)` variant. If it is `unavailable (...)`, SO_MARK
   needs CAP_NET_ADMIN in the su domain: record the su context and `su -c 'grep Cap
   /proc/$(pidof libtailscale.so)/status'`, confirm the app correctly withheld pref 200
   (`ip rule show | grep '^200:'` empty), and escalate — exit nodes cannot be made safe
   on that root solution until the policy is fixed.
5. Repeat WSA checklist steps 3-5 with wlan0 in place of eth0, and add `ip -6 rule show
   | grep '^200:'` and `ip -6 route show table 52`: a missing v6 `default dev
   tailscale0` while wlan0 has a global v6 address is a v6 leak — report it, do not fix
   it here.
6. Reboot with `adb -s 192.168.1.83:5555 reboot`; `adb wait-for-device`; poll `getprop
   sys.boot_completed`. BEFORE unlocking the phone run `su -c 'pgrep -af
   libtailscale.so; ls -d /data/data/io.github.bropines.tailscaled; tail -5
   /data/data/io.github.bropines.tailscaled/logs/tailscaled.log'` — this is what decides
   whether file-based encryption blocks the script before first unlock. Then unlock,
   wait a minute and run the full WSA checklist with CLI=$(ls
   /data/app/*/$PKG-*/lib/arm64/libtailscale_cli.so | head -1).
7. Check BOOT_COMPLETED delivery on HyperOS: grep logcat for `Attaching to existing
   socket`. If the service never starts at boot, look at Security -> Manage apps ->
   TailSocks -> Autostart and `appops get io.github.bropines.tailscaled | grep -i boot`,
   and record which it was.
8. Record every result next to the WSA ones in docs/handoff/backlog-a-root-devices.md;
   any fix goes in its own local commit.

**Done when**

- Root Mode can be enabled on the Redmi and the app reaches the daemon socket (Daemon
  Status responding, no connectto AVC denials).
- The daemon log shows `netns: SO_MARK 0x2000000 set on tailscaled sockets (root
  bypass)`; `ip rule show` has the pref-100 pair and the pref-200 catch-all; `ip route
  get 8.8.8.8 mark 0x2000000` picks wlan0; exit-node egress works; `tailscale status`
  reports no router health warning.
- After `adb reboot` and unlock, the service.d script alone brings the daemon up with
  the same rules and the app attaches (one run marker for the boot, no `stale or
  unconfigured`).
- Every deviation from the WSA result is written down with the exact command output.

**How to test**

- adb -s 192.168.1.83:5555 shell su -c id -> `uid=0(root) ... context=u:r:<domain>:s0`
- adb -s 192.168.1.83:5555 shell su -c 'ls -l /data/adb/service.d/tailscaled.sh; grep -c
  "TailSocks: daemon start" /data/adb/service.d/tailscaled.sh' -> the file exists (755)
  and the marker line is present once
- adb -s 192.168.1.83:5555 shell su -c "grep 'netns: SO_MARK'
  /data/data/io.github.bropines.tailscaled/logs/tailscaled.log | tail -1"
- adb -s 192.168.1.83:5555 shell su -c 'ip rule show' -> two `100: ... lookup 1099`
  lines, one `200: ... fwmark 0x0/0x2020000 iif lo lookup 52`, then netd's own rules
- adb -s 192.168.1.83:5555 shell su -c 'ip route get 8.8.8.8; ip route get 8.8.8.8 mark
  0x2000000'
- adb -s 192.168.1.83:5555 reboot; adb -s 192.168.1.83:5555 wait-for-device; adb -s
  192.168.1.83:5555 shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2;
  done; su -c "pgrep -af libtailscale.so"'
- adb -s 192.168.1.83:5555 logcat -d | grep -E 'Attaching to existing socket|stale or
  unconfigured|routing applied|exit node unavailable'

**Risks**

- The Redmi is a real phone: an exit node left selected while that node is offline cuts
  its internet — clear it at the end.
- HyperOS may not deliver BOOT_COMPLETED without an autostart grant, in which case
  variant A cannot occur; variant B (headless daemon plus a manual Start) still
  validates the script.
- If the APatch su domain lacks CAP_NET_ADMIN for SO_MARK, exit nodes cannot be made
  safe there without a policy change — do not remove the gate to force pref 200 on.

### - [ ] A4. Bring tools/root-debug.sh and the session driver up to the 4.0 layout

**Effort** S &nbsp;·&nbsp; **Files** `tools/root-debug.sh`, `tools/root-debug-session.sh`, `docs/ROOT.md`, `docs/ROOT_RU.md`

**Steps**

1. In tools/root-debug.sh replace the filtered `policy rules` section (lines 57-59) with
   full `ip rule list` and `ip -6 rule list`, and add: `ip route show table 52`, `ip -6
   route show table 52`, `ip route show table all | grep tailscale0`, `ip route get
   8.8.8.8` and `ip route get 8.8.8.8 mark 0x2000000`, rp_filter for every interface
   plus /data/adb/tailsocks/rp_filter.orig, and a stale-rule check (`ip rule show | grep
   -E '^52[0-9]{2}:'`).
2. Add a `boot script` section: `ls -l /data/adb/service.d/tailscaled.sh`, its `^PKG=`
   and `^DAEMON_BIN=` lines with a `test -x` verdict, `ls -lZ /data/adb/tailsocks/`, the
   env file with everything after the first `=` masked (it can hold a proxy password),
   `getprop sys.boot_completed`, and the daemon's environment variable NAMES only (`tr
   '\0' '\n' < /proc/<pid>/environ | cut -d= -f1 | sort`).
3. Add a `daemon log gate` section: the line number of the last `TailSocks: daemon
   start`, then the lines after it matching `netns: SO_MARK|TailSocks: exit
   node|wgengine.NewUserspaceEngine|router config failed`.
4. In tools/root-debug-session.sh wrap the four `am start-foreground-service` / `am
   startservice` invocations (lines 53-54 and 67-68) in `su -c '...'`, as snap() already
   does, since a non-exported service cannot be started from a plain adb shell on a
   release build.
5. Update the "Diagnostic scripts (tools/)" section of docs/ROOT.md (line 239) and its
   mirror in docs/ROOT_RU.md to list the new sections.

**Done when**

- `adb shell su -c 'sh /data/local/tmp/root-debug.sh'` on WSA prints the pref-200 rule,
  table 52, both `ip route get 8.8.8.8` variants, rp_filter, the script and env-file
  status and the gate lines, without printing proxy credentials.
- `tools/root-debug-session.sh 127.0.0.1:58526` starts and stops the service on the
  release build, and 03-after-stop shows no leftovers.

**How to test**

- adb -s 127.0.0.1:58526 push tools/root-debug.sh /data/local/tmp/ && adb -s
  127.0.0.1:58526 shell su -c 'sh /data/local/tmp/root-debug.sh' | grep -E '^===
  |^200:|table 52|SO_MARK|DAEMON_BIN'
- grep -n 'su -c' tools/root-debug-session.sh -> the am lines are wrapped

**Risks**

- control_proxy.env can contain a control-proxy URL with a password: print variable
  names, or mask everything after the first `=`.

### - [ ] A5. DECISION: make the boot-started daemon equivalent to the app-started one (SOCKS5/HTTP listeners, TUN mode)

**Effort** M &nbsp;·&nbsp; **Files** `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt`, `app/src/main/assets/scripts/tailscaled.sh`, `docs/ROOT.md`, `docs/ROOT_RU.md`, `CHANGELOG.md`

**Steps**

1. The author picks: (a) parity through the env file [recommended], (b) app-side
   detection and restart, or (c) document the limitation. Note that (a) must pass FLAGS:
   --socks5-server is a plain flag defaulting to "" and tailscaled ignores
   TS_SOCKS5_SERVER (only containerboot reads it).
2. For (a), in RootUtils.startRootDaemon extend the `env` StringBuilder
   (RootUtils.kt:267-296 — this is exactly what is written to
   /data/adb/tailsocks/control_proxy.env and re-exported by the script) with quoted
   lines `export TAILSOCKS_SOCKS5=...`, `export TAILSOCKS_HTTP_PROXY=...`, `export
   TAILSOCKS_TUN=<tailscale0|userspace-networking>`; use shQuote, and keep the names
   inside the script's grammar (first character A-Z or _, then A-Z, 0-9 or _).
3. In tailscaled.sh, after the env parse ends (line 77), build the argument list: `set
   -- --statedir="$STATE_DIR" --socket="$SOCKET_PATH"
   --tun="${TAILSOCKS_TUN:-tailscale0}"`, append `--socks5-server="$TAILSOCKS_SOCKS5"`
   when it is set and not `none`, append
   `--outbound-http-proxy-listen="$TAILSOCKS_HTTP_PROXY"` when set, and start with
   `nohup "$DAEMON_BIN" "$@"` in place of line 108. Skip the whole tailscale0 routing
   block when TAILSOCKS_TUN is not tailscale0.
4. Decide about SOCKS5 credentials at the same time: they reach the daemon as
   TS_SOCKS5_USER / TS_SOCKS5_PASS (patch 02, appctr/daemon.go:105), and RootUtils
   exports neither, so the root daemon's SOCKS5 listener is unauthenticated today
   whichever launcher starts it. Either export them from startRootDaemon as well or
   record the gap deliberately.
5. Keep the env file root-owned 0600 (it already is), update docs/ROOT.md section 1 and
   its RU mirror, and add a CHANGELOG Fixed line: after a reboot the daemon started
   without the SOCKS5/HTTP proxy listeners, so Taildrive in Files and LAN clients failed
   until a manual restart.

**Done when**

- After a reboot on WSA, `pgrep -af libtailscale.so` shows
  `--socks5-server=127.0.0.1:48115` (or whatever is configured) and a listener exists on
  that port; browsing a Taildrive share in Files works while attached to the
  script-started daemon.
- With "Native Linux TUN (tailscale0)" OFF the script starts
  `--tun=userspace-networking` and installs no tailscale0 rules; with it ON nothing
  changes.
- The env file still parses: no new `TailSocks:` error lines and TS_DNS_FALLBACK is
  still exported.

**How to test**

- adb -s 127.0.0.1:58526 shell su -c 'cat /data/adb/tailsocks/control_proxy.env' ->
  contains `export TAILSOCKS_SOCKS5='127.0.0.1:48115'` and `export
  TAILSOCKS_TUN='tailscale0'`
- reboot WSA (the author restarts it), then su -c 'pgrep -af libtailscale.so; netstat
  -ltn 2>/dev/null | grep 48115'
- Files -> Taildrive -> a peer share opens; previously the Go log showed `SOCKS5 proxy
  is not running` (appctr/drive.go:180)

**Risks**

- Every new variable is exported into the daemon's environment: keep the TAILSOCKS_
  prefix so nothing collides with Tailscale's own TS_* knobs.
- Option (b) would force the app to kill a working daemon at every boot just to add
  flags, which contradicts the "Terminate Root Daemon on Stop = off" semantics; (a) is
  cheaper.

### - [ ] A6. DECISION: align the boot script's file modes with the app (socket 666, state dir 700, log 644)

**Effort** S &nbsp;·&nbsp; **Files** `app/src/main/assets/scripts/tailscaled.sh`, `CHANGELOG.md`

**Steps**

1. In tailscaled.sh change `chmod 666 "$LOG_FILE"` (line 109) to 644, `chmod 777
   "$SOCKET_PATH"` (line 114) to 666 and `chmod 777 "$STATE_DIR"` (line 116) to 700,
   matching RootUtils.kt:343, 347 and 349.
2. Confirm the app never writes into the state directory as its own uid in Root Mode:
   the profile backup path copies it through su and checks isRootAvailable first
   (SettingsActivity.kt:382).
3. Add a CHANGELOG Security line: the autostart script no longer leaves the daemon state
   directory (node keys) world-writable inside the app sandbox, and no longer widens the
   socket beyond what the app itself sets.

**Done when**

- After a reboot: `stat -c %a` gives 666 for the socket, 700 for files/states/<dir> and
  644 for logs/tailscaled.log; the app still attaches, the Logs screen still shows the
  daemon log, and profile backup/restore still works.

**How to test**

- adb -s 127.0.0.1:58526 shell su -c 'stat -c "%a %U %n"
  /data/data/io.github.bropines.tailscaled/files/tailscaled.sock
  /data/data/io.github.bropines.tailscaled/files/states/*
  /data/data/io.github.bropines.tailscaled/logs/tailscaled.log'
- Settings -> Profile -> backup, then restore, with Root Mode on

**Risks**

- /data/data/<pkg> is already 0700, so the practical exposure is small; the point is
  consistency with what the app's own launcher enforces.

### - [x] A7. CONDITIONAL (after the Redmi preflight): APatch sepolicy — absolute magiskpolicy path and the daemon's real domain

**Effort** S &nbsp;·&nbsp; **Files** `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt`, `app/src/main/assets/scripts/tailscaled.sh`, `docs/ROOT.md`, `docs/ROOT_RU.md`

**Steps**

1. Do this only if the Redmi shows `Not running / socket not responding` with AVC
   `connectto` denials, or if `which magiskpolicy` is empty under su.
2. In RootUtils.startRootDaemon (the magiskpolicy line, RootUtils.kt:344) and
   tailscaled.sh:110, try in order: `magiskpolicy`, `/data/adb/ap/bin/magiskpolicy`,
   `/data/adb/magisk/magiskpolicy`, `supolicy`; and allow connectto for the domain
   actually observed on the device (`magisk` and/or `su`), e.g. `--live "allow
   untrusted_app magisk unix_stream_socket connectto" "allow untrusted_app su
   unix_stream_socket connectto"`.
3. Note in docs/ROOT.md section 1 (and the RU mirror) which root solutions were
   verified: Magisk on WSA, APatch on the Redmi.

**Done when**

- On the Redmi the app reports `Running (socket responding)` and `logcat -d -b all |
  grep avc | grep connectto` is empty after a start and after a reboot.

**How to test**

- adb -s 192.168.1.83:5555 shell su -c 'ls /data/adb/ap/bin/magiskpolicy &&
  /data/adb/ap/bin/magiskpolicy --live "allow untrusted_app su unix_stream_socket
  connectto"; echo rc=$?'
- adb -s 192.168.1.83:5555 shell 'logcat -d -b all | grep -i "avc: *denied" | grep -i
  connectto'

**Risks**

- Do not guess the domain: take it from /proc/<pid>/attr/current on the device. A
  permissive su domain needs no rule at all.

### - [ ] A8. CONDITIONAL (after the Redmi reboot): wait for user-0 CE storage in the boot script on FBE phones

**Effort** S &nbsp;·&nbsp; **Files** `app/src/main/assets/scripts/tailscaled.sh`, `docs/ROOT.md`, `docs/ROOT_RU.md`, `CHANGELOG.md`

**Steps**

1. Do this only if, right after the reboot and BEFORE unlocking, `pgrep -af
   libtailscale.so` is empty and `ls -d /data/data/io.github.bropines.tailscaled` fails
   (or the log has nothing for this boot) while `getprop ro.crypto.type` is `file`.
2. In tailscaled.sh, before the `[ ! -d "$DATA_DIR" ] && exit 1` line (line 10), add a
   bounded wait (for example up to 10 minutes, `sleep 5` per turn) until `[ -d
   "/data/data/$PKG/files" ]`, so the script survives running before the first unlock.
   Only use `getprop sys.user.0.ce_available` if that property actually exists on the
   device — check first.
3. Explain the wait in docs/ROOT.md section 1 and the RU mirror; CHANGELOG Fixed: on
   phones with file-based encryption the autostart script ran before the user unlocked
   the device and exited, so the daemon never started at boot.

**Done when**

- On the Redmi, after a reboot and unlock, exactly one script-started daemon exists
  without any manual Start, and the log carries this boot's run marker.

**How to test**

- adb -s 192.168.1.83:5555 shell getprop ro.crypto.type ro.crypto.state; adb -s
  192.168.1.83:5555 shell su -c 'getprop sys.user.0.ce_available'
- reboot, do not unlock, `adb shell su -c "pgrep -af libtailscale.so; ls -d
  /data/data/io.github.bropines.tailscaled"`, then unlock and repeat after a minute

**Risks**

- A long-running service.d script is acceptable (it runs in a non-blocking stage), but
  keep the wait bounded so a wiped app never leaves a root shell looping forever.

### - [ ] A9. CONDITIONAL (after variant A on either device): do not kill a daemon that is still starting when the boot script is installed

**Effort** S &nbsp;·&nbsp; **Files** `app/src/main/java/io/github/bropines/tailscaled/core/TailscaledService.kt`, `CHANGELOG.md`

**Steps**

1. Do this only if logcat at boot shows `Root daemon socket is stale or unconfigured.
   Stopping leftover daemon and restarting.` together with a second `TailSocks: daemon
   start` marker for the same boot.
2. In the Root Mode branch of TailscaledService.startTailscale (around lines 641-662):
   when the socket connects but the status is absent or NoState AND
   RootUtils.isServiceScriptInstalled() is true, poll isDaemonAlive plus getStatus for
   up to about 15 s before declaring the daemon stale; keep today's behaviour when the
   socket does not connect at all.
3. CHANGELOG Fixed: at boot the app could kill the daemon the autostart script had just
   launched and start its own.

**Done when**

- Variant A on both devices: exactly one run marker per boot, `Attaching to existing
  socket` in logcat, and no `stale or unconfigured`.

**How to test**

- adb logcat -d | grep -E 'stale or unconfigured|Attaching to existing socket'
- su -c "grep -c 'TailSocks: daemon start'
  /data/data/io.github.bropines.tailscaled/logs/tailscaled.log" before and after the app
  start

**Risks**

- Waiting on a genuinely dead socket delays the start by the poll timeout; bound it and
  log the wait.

### - [ ] A10. OPTIONAL: version header in the installed script and an "outdated" status in Settings

**Effort** S &nbsp;·&nbsp; **Files** `app/src/main/assets/scripts/tailscaled.sh`, `app/src/main/assets/scripts/tailscale_cli.sh`, `app/src/main/java/io/github/bropines/tailscaled/core/RootUtils.kt`, `app/src/main/java/io/github/bropines/tailscaled/ui/SettingsActivity.kt`, `docs/ROOT.md`, `docs/ROOT_RU.md`

**Steps**

1. Add `# TailSocks-script-version: %VERSION_CODE%` to tailscaled.sh and
   tailscale_cli.sh, filled in by setServiceScriptInstalled / setTailscaleCliInstalled
   from BuildConfig.VERSION_CODE next to the existing %PKG_NAME% and %DAEMON_BIN%
   substitutions (RootUtils.kt:935-936, 972-973).
2. Make isServiceScriptInstalled (RootUtils.kt:957) return a tri-state (absent /
   outdated / current) by grepping that header, and show "Outdated — tap Reinstall
   Script" in the Root Mode tab next to the autostart switch. BootReceiver already
   rewrites the script on every boot and update, so no behaviour changes.
3. Mention in docs/ROOT.md section 3 that the status shows when the installed copy
   predates the app.

**Done when**

- After editing the header on the device to an older number, Settings -> Root Mode shows
  the outdated state; "Reinstall Script" or an `adb install -r` restores the current
  header.

**How to test**

- adb shell su -c 'head -5 /data/adb/service.d/tailscaled.sh' -> header carrying the
  current versionCode

**Risks**

- Cosmetic: BootReceiver already refreshes the script, so this only buys visibility.

## Needs the author's decision

Stop and ask rather than guessing; each of these changes what gets built.

- Parity of the boot-started daemon: put the SOCKS5/HTTP listen addresses and the TUN
  mode into /data/adb/tailsocks/control_proxy.env and have tailscaled.sh turn them into
  flags (recommended — an env variable alone cannot work, tailscaled reads only the
  flag), or accept that a reboot yields a daemon with no proxy listeners, or have the
  app restart such a daemon.
- Whether the root daemon's SOCKS5 listener should be authenticated at all:
  TS_SOCKS5_USER / TS_SOCKS5_PASS are never exported by RootUtils, so it is open on
  127.0.0.1 in Root Mode regardless of the settings fields.
- Align tailscaled.sh file modes with the app (socket 666, state dir 700, log 644
  instead of 777/777/666).
- Whether the boot script should honour root_tun_enabled=false (start
  --tun=userspace-networking and skip the routing block) — today it always forces
  tailscale0. Folds into the parity decision.
- APatch sepolicy handling (absolute /data/adb/ap/bin/magiskpolicy path, an `su`-domain
  rule) — only after the Redmi preflight shows a denial.
- An FBE wait in the boot script — only after the Redmi reboot shows the script exiting
  before first unlock.
- Boot-race handling (do not kill a NoState daemon while the service script is
  installed) — only if observed.
- Whether to add a script version header and an "outdated" status (cosmetic;
  BootReceiver rewrites the script on every boot and update anyway).

## Unverified

Leads, not facts. Confirm on the device or in the code before acting.

- Which build is actually installed on WSA and the Redmi. The release APKs on disk
  (16:54) do contain the patch-16 core and the current script — confirm on device with
  `dumpsys package io.github.bropines.tailscaled | grep versionName` (it embeds the git
  hash; expect the one for 3800f43 or later) and with the `netns: SO_MARK` line in the
  daemon log.
- Magisk/APatch stage timing: whether /data/adb/service.d runs before
  sys.boot_completed=1 and, on the FBE Redmi, before the first unlock (which would make
  /data/data/$PKG unresolvable and end the script at tailscaled.sh:10). Check before
  unlocking; `getprop sys.user.0.ce_available` may not exist on that ROM.
- APatch specifics: the SELinux context of the su shell and of the spawned daemon,
  whether magiskpolicy is on PATH (docs/HANDOFF_BACKLOG.md:51 and the author's notes say
  /data/adb/ap/bin/magiskpolicy), whether the su domain is permissive (which would make
  the connectto rule moot), and whether SO_MARK (CAP_NET_ADMIN) succeeds — flagged as an
  open question in docs/research/root-exit-node-design.json:166.
- Whether APatch executes /data/adb/service.d scripts at all; docs/ROOT.md:11 assumes
  Magisk/KernelSU/APatch semantics but nothing in this repo proves it for APatch.
- Whether HyperOS on the Redmi delivers BOOT_COMPLETED to the app without an autostart
  grant (docs/HANDOFF_BACKLOG.md:66 says the POCO denies the autostart op).
- The timing of the daemon's first NoState -> Starting/Running transition relative to
  BootReceiver's start; the boot-race task stays conditional on observing it.
- Whether legacy iptables/ip6tables and the `fwmark x/y` mask printing behave the same
  on the Android 16 Redmi as on WSA; both the script and the app read the rule back and
  log a marker if the mask was dropped.
- Whether either device has more than one account/state directory: the script picks
  `default` or the first tailscaled.state it finds (tailscaled.sh:19-27) while the app
  uses files/states/<activeAccount.id> (TailscaledService.kt:775), so with several
  profiles a boot-started daemon can hold a different identity than the app expects.
- WSA clock/timezone behaviour when reading daemon log timestamps — do not assume it
  matches the host.
- Everything that requires the devices themselves: whether APatch runs
  /data/adb/service.d at all, the SELinux context of its su shell and of the daemon,
  whether magiskpolicy sits at /data/adb/ap/bin, whether SO_MARK succeeds under APatch,
  and whether HyperOS delivers BOOT_COMPLETED without an autostart grant. Only device
  output can settle these.
- Whether Magisk's post-fs-data/service stage on WSA runs before or after
  sys.boot_completed, and whether the Redmi's FBE blocks the script before first unlock.
- Which APK build is installed on each device right now (the on-disk release APKs were
  verified to contain the patch-16 core and the current script; the devices were not
  touched, per the read-only instruction).
- The exact iproute2 output formatting on the Android 16 Redmi (whether the pref-200
  rule prints the mask as 0x0/0x2020000 or 0/0x2020000) — both the script and the app
  already accept either.
- The claim that the POCO's HyperOS logs 'MIUIOP ... ignore' when denying the autostart
  op: it appears in docs/HANDOFF_BACKLOG.md (written by another agent) but nothing in
  the code or in a device log in this repo confirms it.
- docs/handoff/backlog-a-root-devices.md itself was not read as source material — it was
  created mid-session by a concurrent agent and is presumably this brief's own draft;
  treating it as evidence would be circular.

## Out of scope

- Re-applying rules after a netd restart flushes them
  (docs/research/root-exit-node-design.json:281) — separate hardening, not device
  verification.
- Fixing the IPv6 exit-node leak when table 52 has no v6 default while the phone has
  global v6 (docs/research/root-exit-node-design.json:272) — report it if seen.
- Any change to the 4.0 native-TUN handoff (docs/HANDOFF_4.0_NATIVE_TUN.md); Root Mode
  routing must stay byte-identical for that work.
- The POCO 192.168.1.70 (no root, the author's daily phone): no Root Mode tests there.
- Removing the dead OverrideDNSResolvers block in appctr
  (docs/research/root-exit-node-design.json:206-208) and other Go hygiene.
- Rewriting docs/ROOT.md beyond recording verification results and the fixes above;
  docs/ROOT_RU.md must mirror any ROOT.md edit.
- Authoring docs/HANDOFF_BACKLOG.md as a whole — it now exists; only its section 3.A
  entry and the topic file docs/handoff/backlog-a-root-devices.md are in scope.
