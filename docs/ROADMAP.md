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

State as of 2026-09-07, after 4.1.1.

### Big

- [ ] **Native TUN.** The plan is [`NATIVE_TUN_PLAN.md`](NATIVE_TUN_PLAN.md): seven steps (0–6,
      the last one optional polish), each leaving the app shippable. Target: unscheduled, to be
      re-set before work starts — it slipped past 4.0.0 and 4.1.0, so every "4.1" in the plan is
      stale. The main risk is gone: SELinux policy allows the app `TUNGETIFF` on the tunnel fd
      (`allowxperm untrusted_app tun_device chr_file ioctl { 0x54D2 }`, read off the Redmi).
      Still unverified on a device: passing the fd to the child process over `SCM_RIGHTS`, the
      behaviour when the owner dies, and the latency of a swap. Verifying them needs a debug
      helper inside the APK — checking through `su` proves nothing, it is a different SELinux
      domain.
- [ ] **tsnet — an idea for 5.0.** The daemon moved inside the app process. Incompatible with
      Root Mode, where it must be a separate process under `su`.
- [ ] **The separate CLI binary.** The author's decision, deferred. The cost is measured: about
      6 MB in every per-ABI slice, 25 MB in the universal APK, and roughly 22 MB on device
      because native libraries are extracted at install. What it buys: the Root Mode shell
      wrapper (`su -c tailscale …`) and a handful of Console commands that already have LocalAPI
      equivalents.
  - **Choose between:** (a) keep it as it is — zero work.
  - (b) Download it on demand from the app's own GitHub release — the smallest APK, but it adds a downloader, hash verification, published CI assets, version pinning and reachability concerns, and a downloaded binary can likely only be executed by root.
  - (c) `lite` / `full` Gradle product flavors — a contained build change, but the variant matrix doubles and CI renaming plus updater awareness follow.
  - (d) Ship the CLI in the universal APK only — the same mechanism as (c), with the release page still at five assets (four per-ABI APKs plus the universal one).
  - (e) The `ts_include_cli` build tag upstream offers: one binary serves both roles, `tailscaled` dispatching to the CLI when it is invoked as `tailscale`, so there is no second 6 MB library at all. This option was missing from the earlier list.
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
- [ ] **Scanner-bot issues #5, #6, #7** — verified 2026-09-07: `x/crypto/ssh` is not compiled into any shipped binary (`ts_omit_ssh` plus upstream's `!android` build constraint on the SSH server), the version is dictated by the pinned upstream module, and the two PRs change only the bridge's `go.mod`. The closing comment is written; the author posts it (the assistant is not allowed to write to GitHub).
- [ ] **Issue #3** — the request Root Mode started from. The author has already answered; either
      close it or wait for `TheLastFlame` to confirm on his tablet.

### Verify on devices

- [ ] **Root Mode on WSA after a reboot:** autostart through `service.d`, and the app attaching
      to a daemon it did not launch.
- [x] **The honest-OS experiment** — both halves ran on 2026-09-07 (see *Needs the author's
      decision*). Not measured: a *tagged* Android node hosting a `svc:` (the fresh profile was
      user-owned, and the daemon refuses service hosting without tags regardless of OS).
- [ ] **Received-file permissions in Root Mode.** The fix was made blind (`umask 022` plus
      handing the directory to the app). Check Routing now prints the real modes — look at them
      and confirm.
- [ ] **The IPv6 exit-node leak** does not reproduce on the Redmi as of 2026-09-07: table `52`
      has a default route and traffic leaves through the tunnel with a tailnet source address.
      Check the POCO, where the network is different; if it does not reproduce there either,
      strike the item.
- [ ] **A peer's version from the Admin API** is implemented and works only with a token
      configured. All that is left is a look on a device where one is.
- Deferred, not implemented — recorded so they are not lost again:
  - A foreign tunnel restarting with a new netId: the ruleset signature keys on the netId, but this has never been observed on a device.
  - FBE phones: wait for user-0 CE storage before starting.

### Small

- [ ] **IPv6 in the DNS redirect.** On the Redmi's kernel (4.19) there is no IPv6 `nat` table —
      there is nowhere to write the rules; a firmware limit, not a gap of ours. To do: say so in
      the diagnostics instead of the present silence, and check whether a DNS query goes out over
      IPv6 past MagicDNS.
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
- [ ] **Update Tailscale** from 1.102.1 to the current upstream release, v1.102.3 (2026-08-20).
      Correction to the earlier note: it does *not* pull the dependency versions the scanner bot
      asked for — v1.102.3 still pins `x/crypto` v0.54.0. Those arrive only with a later upstream
      release.
- [ ] **An English easter egg** — if it is done at all, as a different joke, not a translation.
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
