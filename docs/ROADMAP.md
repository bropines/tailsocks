# Project Roadmap

This document outlines the planned features, architectural improvements, and refactoring goals for the TailSocks project.

## Completed Milestones
- [x] **Core Stability:** Passive daemon management and stateless configuration.
- [x] **File Sharing:** Taildrop implementation with Storage Access Framework integration.
- [x] **Profile Isolation:** Multi-account system with independent state persistence.
- [x] **Connectivity:** Custom DNS wrapping and Exit Node support.
- [x] **Tailscale Serve/Funnel:** Native UI for service hosting and public internet exposure.
- [x] **Local API v0:** 100% CLI-less operation via Go-HTTP bridge.
- [x] **System Integration:** Basic Quick Settings Tile for connectivity toggling.
- [x] **Service Monitoring:** Add health checks and request logs for hosted Serve/Funnel services.
- [x] **Search & Filter:** Implement search functionality for Peer list and Logs.
- [x] **Battery Optimization UI:** Add a prompt to help users whitelist the app from system battery restrictions.
- [x] **Account Switching in Tiles:** Enhance Quick Settings to allow switching between profiles.
- [x] **Socks5 proxy to Control Panel:** Added SOCKS5/HTTP proxy support for control plane traffic with atomic patches and netns bypass fix.
- [x] **Encrypted Backups:** Manual app state backups encrypted with a user-defined password (AES-GCM) to protect node keys.
- [x] **Quick Settings Tiles:** Quick Settings Tiles for profile switching and connection state management.
- [x] **R8 minification (4.0.0):** `isMinifyEnabled`/`isShrinkResources` are on for release builds. Gson was replaced by `kotlinx.serialization`, the AppFunctions service constructs the KSP-generated registries directly, and the `verifyReleaseNativeMethods` Gradle task fails the build if R8 drops a JNI method.
- [x] **Root Mode hardening (4.0.0):** dedicated `TAILSOCKS_MARK`/`TAILSOCKS_DNS` chains, masked fwmark `0x1000000/0x1000000`, Check Routing diagnostics and the ROOT log tab.
- [x] **Automation security (4.0.0):** broadcast receiver requires a secret token; AppFunctions (Gemini, Android 16+) actually execute and honour the automation switch.
- [x] **LAN Access, auto-reconnect, background revival and versioned backups (4.0.0).**
- [x] **Root Mode coexistence (4.0.0):** the policy ruleset is tiered — tailnet reachability, the default-route capture, the device-wide DNS redirect — with one owner per device for the last two, a partial yield that carries exactly the uids another VPN client bypasses, a health-gated DNS redirect and a CGNAT guard. Verified on the author's Redmi (APatch), including across a real reboot.
- [x] **Report the real OS (4.1.1):** a per-profile choice made when the profile is created (onboarding, the Add-account dialog; the Settings switch for a profile that has not signed in yet, a new profile otherwise). Off keeps the patch-06 masquerade — OS `linux`, App `tailscale-cli`, DeviceModel `Tailsocks`. On, the node registers as OS `android` with the real model, Android version and install source, the way the official client does. Measured 2026-09-07: such a node gets `https`/`funnel`, certificates and a working Funnel; the coordinator refuses only a node that changes its OS after registration.
- [x] **Root Mode across a reboot (4.1.1):** the boot script now takes the active profile's state directory, the SOCKS5/HTTP listen addresses, the SOCKS5 credentials and the tunnel mode (kernel TUN or userspace) from the root-owned env file, starts the daemon with the same command line the app uses, and sets the same file modes (log 644, socket 666, state dir 700). The `TS_VPN_BYPASS=0` line the boot script used to drop is written quoted, so "Ignore other VPNs = off" survives a reboot. The env file is refreshed on every Root Mode start, including when the app attaches to a daemon the boot script started.
- [x] **A daemon crash is no longer a manual Stop (4.1.1):** the desired-running flag stays set when the daemon dies on its own, so auto-reconnect restarts it within its attempt limit; when that limit is spent the service stands down with a tap-to-reconnect notification, and the 15-minute watchdog can still revive it.

## Plans

State as of 2026-10-14, at 5.0.0.

### Big

- [ ] **Remote management when the tailnet is not there — a rescue channel over TailCat (next
      version; the first candidate for a beta).** Asked for by the author on 2026-10-14. Today
      a paired device is managed over the tailnet only, so the moment that matters most is the
      one where it cannot be reached: its key expired, the coordination server is blocked for
      it, the service was stopped, or it signed in to another tailnet. TailCat needs no
      account and already carries an auth key to a television (`TSP2:`), so it can carry the
      management API too. To design before any code: who the caller is without `whois` (only
      the pairing secret is left — the tunnel must be keyed from it, end to end, the relay
      seeing ciphertext); whether the managed device listens on a relay always or only while
      its tailnet is down, and what that costs and reveals; and what is allowed over it —
      looking, restarting and handing over a new key are exactly what 5.0's rules put under
      "never from afar", so this channel gets its own short list, not the tailnet one.
- [x] **Android TV (5.0.0, experimental).** A home-screen entry with a banner on every icon
      variant; the tablet dashboard as the home screen; everything reachable with a D-pad — a
      visible focus ring, screen-edge margins, sheets and dialogs inside the safe area, text
      fields as rows that open a dialog, long texts that scroll with the arrows. What a
      television cannot do is hidden (admin console, QR scanner, Tailnet Lock signing); what it
      rarely needs sits under Settings → Advanced. A built-in file and folder browser stands
      in for the system picker, links show their address and a QR code where no browser
      exists, and Settings → Appearance → Interface switches the television interface by hand.
      Walked on Android TV emulators (API 36) and on WSA; **no television hardware has run it
      yet** — see *Verify on devices*.
- [x] **Signing in without a keyboard (5.0.0).** The login link as a QR code on a television and
      as "Sign in on another device" on a phone; an auth key sent from a phone's scanner to a
      television, over the local network or a TailCat relay, confirmed by a check code on both
      screens.
- [x] **Remote management (5.0.0, experimental, off by default).** One device lets paired
      devices manage its TailSocks over the tailnet. Pairing needs a six-digit code from its
      screen; a device of the same account is an owner, any other a guest for 24 hours. Two
      levels chosen on the managed device itself: Basic (state, connection settings, exit
      node, received files, permissions seen and requested) and Full control (its storage with
      files downloaded through Taildrop, shared folders, exit node and routes offered, the
      scrubbed log) — switched on only at the device, owners only. A change that can cut the
      channel is provisional: the device puts the old value back unless the manager confirms
      within 60 seconds. Every change is logged on the managed device and said on its screen.
      Never from afar: stopping for good, signing out, the account or login server, any
      credential, the control proxy and bypass, Root Mode. Reachable in proxy mode, in TUN on
      either engine and in Root Mode with its kernel TUN (daemon patch 22 keeps the port in
      netstack while the feature is on). Works inside one tailnet only; the protocol announces
      its capabilities, so builds of different versions degrade instead of breaking.
- [x] **Taildrive, the other half (5.0.0).** Browsing other devices' shares and opening their
      files in a player through the app's own provider, seekable over HTTP byte ranges; the
      screens say what the tailnet's policy lets this device do when nothing lists, and the
      visual policy editor has a template for sharing between one's own devices; one switch
      lets the local network into the Taildrive proxy, with the password forced on.
- [x] **Settings search (5.0.0).** Any setting in English or Russian, forgiving typos, the wrong
      keyboard layout and transliteration; `tailsocks://settings/<section>/<row>` links. A
      test reads the sources and fails when a row is missing from the catalog.
- [x] **Native TUN — the opt-in engine (4.2.0).** `tailscaled` owns the `VpnService` device
      instead of hev: `TunVpnService` establishes the tunnel itself and the bridge relaunches
      the daemon with `--tun=android-vpn` on the inherited fd, with a no-op router and netstack
      keeping only its own flows (patches 17–19); Settings → TUN then called it "Native engine
      (experimental)". Verified on the POCO: MagicDNS, split DNS, exit nodes and switching
      between them without a restart, LAN exclusions, one notification card, a single daemon
      launch on connect (the VPN comes up first from cached addresses). The risk the plan was
      written around is gone with it: SELinux allows the app `TUNGETIFF` on the tunnel fd
      (`allowxperm untrusted_app tun_device chr_file ioctl { 0x54D2 }`, read off the Redmi), and
      a real child tailscaled did `TUNGETIFF` and `SIOCGIFMTU` on an inherited fd (patch 17
      probe: `tun0`, flags `0x1001`, MTU 1500).
- [x] **Native TUN — TUN's default (2026-10-12).** Proxy stays the app's default mode; within
      TUN mode the native engine is what new installs and everyone who never picked an engine
      run on, and a stored `hev` stays hev (Settings → TUN → TUN engine; `hev-socks5-tunnel` is
      the compatibility engine and keeps its own settings). What was left, done in code:
      accepted subnet routes without an exit node are on the device, rebuilt from LocalAPI
      status (both engines; behind an exit node only what its default routes do not already
      carry: routes inside an "Excluded IP Ranges" entry, and IPv6 while all-IPv6 is off); a
      start that found no node addresses comes up once the netmap is in; the always-on start
      brings the connection up with it; with "Use MagicDNS" off the device
      names no DNS server instead of a quad-100 that answers SERVFAIL; the device is unmetered;
      a start with the connection stopped establishes nothing. Wi‑Fi ↔ LTE, doze and Android 7/8
      needed no code that was clearly wrong; all of it is under *Verify on devices*. Stopping
      the daemon and starting it again on the other engine is the intended behaviour, not a
      debt; the fd swap, `CallbackRouter` and `SCM_RIGHTS` that
      [`NATIVE_TUN_PLAN.md`](NATIVE_TUN_PLAN.md) argues for stay that document's ambition, kept
      for the record and not wanted here.
- [ ] **tsnet — an idea, not taken into 5.0.** The daemon moved inside the app process. Incompatible with
      Root Mode, where it must be a separate process under `su`.
- [x] **One binary instead of two (2026-10-12).** Option (e) of the old "separate CLI binary"
      item: `libtailscale.so` is built with upstream's `ts_include_cli` tag and is the CLI as
      well — it runs as `tailscale` when its argv[0] is `tailscale` (the link in the data
      directory, which now points at the daemon) or when `TS_BE_CLI=1` (the Root Mode wrapper,
      since a shell cannot set argv[0] portably). `libtailscale_cli.so` is gone. Measured:
      on the device 40.0 → 28.0 MB on arm64, 39.0 → 27.1 on armeabi-v7a, 39.3 → 27.3 on x86,
      43.2 → 30.2 on x86_64 (about −12 MB each); the release APKs against 4.9.1 lose 4.4–5.1 MB
      per ABI (arm64 31.3 → 26.9 MB) and 19 MB universal (105.0 → 86.0 MB). The CLI's
      behaviour is upstream's own, checked on a host build of the same tags: the `tailscale`
      link and `TS_BE_CLI=1` give the CLI, any other name the daemon. Still owed: Console
      and the Root wrapper (`su -c tailscale status`) on a device.
  - Note: `useLegacyPackaging` cannot be turned off — the daemon is `exec()`d from
    `nativeLibraryDir`, which requires the libraries to be extracted to disk.

### Needs the author's decision

- [x] **The honest OS — what the research settled (2026-09-07).** Patch 06 substitutes
      `OS = linux` on the theory that the control plane ignores services advertised by Android
      nodes. That theory does not hold: the client has no `Hostinfo.OS` gate on serve, funnel or
      cert — the only gates are the `https` and `funnel` node capabilities carried in the netmap.
      The real Android gate is the `!android` build tag on `ipn/localapi/cert.go`, which patch 05
      already removes, so the masquerade was never what made Serve/Funnel work. peerAPI and
      Taildrop are unaffected: a `Pixel 8` with `OS = android` on this tailnet advertises
      `peerapi4`/`peerapi6`, and only tvOS is refused in code. The single documented consequence
      of honesty is device posture — `node:os` flips from linux to android. Upstream
      `tailscale/tailscale#18245` shows an `OS = android` node obtaining certificates from
      control. The switch itself has shipped (see Completed, off by default). **Measured 2026-09-07 on the
      Redmi:** masked, the node holds `https` and `funnel`, Funnel answers from the public
      internet in 5 s, `tailscale cert` issues, and the coordinator pulls the service list over
      c2n; honest, for the same node (registered as Linux), the coordinator answers «node OS
      changed since last connection, was node state copied between devices?», sends no netmap,
      no capabilities, and `cert` fails — so a registered node cannot switch. The second half ran the same day on a
      fresh profile: a node registered as Android from the start holds `https` and `funnel`,
      `tailscale cert` issues, Funnel answers HTTP 200 from the public internet in 5 s, and the
      admin console names the machine after the device model (`xiaomi-23030rac7y`,
      "Android (16)"). Hence the setting is a property of the profile, fixed at creation. Decided
      2026-09-07: it stays off by default for new profiles; the masquerade remains the norm.
- [x] **Issue #3** — answered and closed; Root Mode is the feature it asked for.

### Verify on devices

- [ ] **5.0.0 — what only real devices can tell.** Built and walked on emulators in a private
      test tailnet and on the author's phone and WSA; not yet seen:
  1. A television with ARM hardware: first start with the remote, the dashboard, a file
     picked and sent, a share opened, the focus ring on a real panel's overscan.
  2. Sign-in by QR to completion on a television, and a key sent from a phone — once on the
     same Wi-Fi, once with the phone on mobile data (the relay path).
  3. Remote management as a **guest**: a device of another user in the same tailnet, or a
     shared-in node. Pairing from another tailnet is impossible by design.
  4. Pairing with a managed device in Root Mode (the endpoint answers there; the pairing
     itself was not run).
  5. A share's video in a real player. MT Manager's player said "Unexpected runtime error" on
     the first open and then played and seeked; the provider was hardened after that report —
     try VLC and a large file with its index at the end.
  6. Android 7 (API 24–25): a share's media goes by the http link there; not run on a device.
  7. Taildrive's "the policy does not allow it" texts on a tailnet that really refuses.
  8. The notice a managed device shows inside the app when a paired device acts — on a
     television, from across the room.
- [ ] **Netmap cache (1.104).** Whether the coordination server grants `cache-network-maps` to
      our nodes — masked (OS `linux`) and honest (OS `android`) — is not known: Settings →
      Censorship bypass says it on the device. Upstream ships the feature on by default for
      Linux, Windows and macOS in 1.104.1 and compiles it out of its Android app "until we have
      UI affordances for debugging it". The log tells: `writing netmap to disk cache` on every
      full map from control, `Start: loaded netmap from disk cache; N peers` on a start that
      found one; the files are `files/states/<id>/profile-data/<profile>/netmap-cache/` (names
      are hex: `73656c66` is `self`, `706565722d…` a `peer-…`). A start without control: point
      the control proxy at a dead address (`socks5://127.0.0.1:9`), restart — the line above,
      peers with a direct path come up, the card says "No contact with the coordination
      server", `tailscale debug netmap` in the Console shows `"Cached": true`. Do it in
      userspace, native TUN and Root Mode, and across a reboot with the boot script.
- [x] **Root Mode on WSA after a reboot (verified by the author, 2026-09-25):** autostart through `service.d`, and the app attaching
      to a daemon it did not launch.
- [x] **The honest-OS experiment** — both halves ran on 2026-09-07 (see *Needs the author's
      decision*). Not measured: a *tagged* Android node hosting a `svc:` (the fresh profile was
      user-owned, and the daemon refuses service hosting without tags regardless of OS).
- [x] **Received-file permissions in Root Mode (verified by the author, 2026-09-25).** `RootUtils` launches the root daemon under `umask 022`, so a received file is readable by the app the moment it lands. The fix was made blind (`umask 022` plus
      handing the directory to the app). Check Routing now prints the real modes — look at them
      and confirm.
- [ ] **Native TUN as the default — what only a device can tell.** Logcat tag
      `TunVpnService` and the app's log (CORE). On the POCO unless said otherwise:
  1. Update over a build with TUN on hev and the engine never picked: the VPN comes back on
     native ("Native TUN started"). A phone where native was switched on and back off stays on hev.
  2. Fresh install, VPN (TUN) picked in onboarding, then sign in: the VPN comes up by itself
     within a tick of Running ("TUN: the node's addresses are known now").
  3. Subnet routes: a peer with an approved route (e.g. 192.168.x.0/24), "Accept subnet routes" on, no
     exit node — the subnet answers from another app; switched off — it stops. Remove the
     route in the console — within a minute (five after an earlier rebuild) the log says "the
     accepted subnet routes changed" and the daemon relaunches once. With an exit node and the
     default exclusions on Android 13+, the subnet still goes to the tailnet.
  4. Always-on: pick TailSocks as the always-on VPN while disconnected — the connection starts
     and the VPN comes up; reboot — it comes up without opening the app. With "Block connections
     without VPN": apps work through the tunnel, the daemon still reaches control and DERP (the
     VPN app is exempt), the excluded apps have no network at all (Android's lockdown) — confirm,
     and note whether Android restarts the VPN after Stop in the app.
  5. Always-on with proxy or Root mode selected: nothing is established and the log says
     "Always-on VPN start ignored".
  6. "Use MagicDNS" off on native: internet names resolve through the network's DNS, tailnet
     names do not. Check hev in the same state: it still hands the device 100.100.100.100, which
     has no upstream with that pref off.
  7. Wi‑Fi ↔ LTE on native, with and without an exit node: the device stays (no relaunch), the
     daemon rebinds on the new link, MagicDNS keeps answering.
  8. Doze: screen off 30+ minutes with an exit node, with and without the battery-optimisation
     exemption — push notifications still arrive (FCM rides the tunnel).
  9. Metering: with the VPN up on Wi‑Fi, a "Wi‑Fi only" download in the Play Store proceeds.
  10. Android 7/8/9: native comes up ("android-vpn: running on inherited TUN fd" in the daemon
      log). If the daemon cannot use the fd there, the hev engine's "TUN fd probe" line says why,
      and hev is the answer for that device.
  11. Picking an exit node or editing the excluded apps while disconnected raises no VPN.
- [ ] **The IPv6 exit-node leak** does not reproduce on the Redmi as of 2026-09-07: table `52`
      has a default route and traffic leaves through the tunnel with a tailnet source address.
      Check the POCO, where the network is different; if it does not reproduce there either,
      strike the item.
- [x] **A peer's version without the Admin API — not possible (checked 2026-10-05).** WhoIs on
      five peers of a Tailscale-hosted tailnet (Windows, Linux, macOS, Android): the
      coordinator ships other nodes only `Hostname`, `OS`, `Services` and `sshHostKeys` of a
      peer's `Hostinfo`, never `IPNVersion`. The version stays an Admin API feature.
- Deferred, not implemented — recorded so they are not lost again:
  - A foreign tunnel restarting with a new netId: the ruleset signature keys on the netId, but this has never been observed on a device.
  - FBE phones: wait for user-0 CE storage before starting.

### Small

- [x] **Beta releases, the groundwork (5.0.0).** One package and one key, two channels told
      apart by the tag: `vX.Y.Z-beta.N` is published as a GitHub pre-release, the in-app update
      check offers betas only with "Receive beta versions" on, F-Droid's tag pattern never
      matches one, and a beta's version code sits just under its release's. The rule and the
      steps are in [RELEASING.md](RELEASING.md). No beta has been cut yet.
- [x] **A network too small for direct connections is said (5.0.0).** The network check warns
      when the link MTU is below what a WireGuard-wrapped tailnet packet needs (1360, or 1340
      over IPv4), and the diagnostics report always names the interface and its MTU. Found on
      WSA, whose `eth0` inherits 1280 from the Windows host's VPN adapters: every large packet
      on a direct path was lost in silence. Fitting the tunnel to such a link automatically is
      not done — a kernel TUN cannot go below 1280 with IPv6 on it.
- [x] **IPv6 in the DNS redirect — said out loud (2026-09-26).** On kernels without an IPv6
      `nat` table (4.19 on the Redmi) there is nowhere to write the v6 rules, so device-wide DNS
      covers IPv4 only. The apply script now asks — `ip6tables -t nat -S`, and only when IPv6 is
      up, so a device with it off raises no false alarm — and the answer becomes a warning in the
      Root log and a marker in the diagnostics report instead of silence. Still open, and only
      measurable on such a device: whether a query actually leaves over IPv6 past MagicDNS.
- [x] **Console `status` / `netcheck` / `ping` do not work in Root Mode.** Done 2026-09-08. The
      `tailscale` link in the data directory was made only by the userspace launch, and Android
      renames the native library directory on every reinstall, so in Root Mode it dangled (seen
      on the Redmi: a link from 2026-09-05 into a removed `/data/app/~~…` directory). Both launch
      paths now refresh the links, and the CLI runner falls back to `libtailscale_cli.so` itself.
      `netcheck` additionally needs a netlink RIB dump the CLI cannot get as an app process (in
      any mode), so the bare command is answered by the in-process netcheck of the Netcheck screen.
- [ ] **The five loading indicators inside buttons** are still the old ones — the new component
      stops being legible at 14 dp. Look at it on a device and decide.
- [ ] **Stable Material 3.** `1.5.0-alpha27` is in use for components 1.4.0 does not have, and it
      is still the latest — no stable release exists yet. When one appears it is one line in
      `gradle/libs.versions.toml`.
- [x] **Update Tailscale to 1.104.1 (2026-10-12).** Upstream's only code change since 1.104.0
      is in app connectors (`appc`, overlapping route removal), which this build omits; all
      twenty-one patches apply with `-F0`, unchanged. The netmap cache that 1.104.1's notes
      announce for desktops was already in 1.104.0 and is compiled in here; see *Verify on
      devices*.
- [x] **Update Tailscale to 1.104.0 (2026-10-01).** Go 1.27.1 with it. Fifteen of the twenty
      patches applied as they were; 03, 10 and 20 only needed their context moved. Upstream now
      does two things we patched: netstack's packet pump survives a failed injection and has its
      own loopback queue, and the DNS forwarder reaches tailnet resolvers through netstack over
      UDP — so 09 keeps only the fake-TUN routing and the bound dials, and 14 only TCP over
      netstack and the DNS rescue. `feature/androidbin` and `feature/androiddns` do not apply to
      our GOOS=android cgo daemon (both are switched off there by build tags) and are omitted;
      so is the new `connreject` diagnostics. Next: tailcat as a library in the bridge (#10).
- [x] **Update Tailscale (2026-09-25).** Pinned at `v1.102.5`, up from 1.102.1. All nineteen
      patches applied with `-F0`; two hunks landed at an offset (04 and 10), which the build
      allows, and nothing had to be re-fitted. It does not pull the dependency versions the
      scanner bot asked for — `x/crypto` is still what upstream pins. The next step up is the
      stable line that carries `feature/androidbin`; see issue #10.
- [x] **An English easter egg (4.4.0).** The status card's long press carries its own set in each language, written separately rather than translated.
- Deferred, not implemented — recorded so they are not lost again:
  - Re-apply the ruleset after a netd restart flushes it; nothing reacts to that today.
  - Do not kill a daemon that is still starting when the app adopts it — there is a single probe today.
  - A version header in the installed boot script, and an "outdated" state in Settings.

### Audit of the second project

- [ ] **`bropines/tailscale-termux-cli`** — the audit started 2026-09-07 and its report is kept
      outside this repository. Fixing what it finds is a separate session, run from that
      project's directory.

### Dropped

- Dropped deliberately, with no code behind any of them: the MCP server, the Kotlin LocalAPI
  bridge, the traffic analyzer, and custom DERP maps.
