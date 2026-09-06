# Native TUN — the design for 4.1 (tailscaled owns the VpnService fd)

**Status: not implemented.** Nothing described here is in the app. TUN mode
still runs on hev-socks5-tunnel: it owns the VpnService tunnel and pushes every
packet into the daemon's SOCKS5 port. This document is the design that replaces
that, planned for 4.1 — it records what was decided, why the alternatives were
rejected, and in what order the work can land while keeping the app shippable
after every step.

Two constraints hold throughout, and neither is negotiable: **Proxy mode and
Root mode stay byte-for-byte unchanged** — engine behaviour is keyed on the
`--tun=` name, never on `GOOS` — and steps 1-2 are "dark": the code is present
and nothing selects it.

## 1. Goal

Replace the current TUN mode (hev-socks5-tunnel owns the VpnService TUN and
pushes all traffic into the daemon's SOCKS5 port — a double network stack:
lwIP → SOCKS → gVisor netstack → WireGuard) with the architecture of the
official Tailscale Android client: **the VpnService creates the TUN, and
tailscaled itself owns that fd**, so MagicDNS, split DNS, search domains,
subnet routes, exit nodes and the 100.100.100.100 service IP work natively.

Non-goals: moving tailscaled in-process (tsnet) — that is a 5.0.0 idea; changing
Root Mode; removing the SOCKS5/HTTP proxies (they stay for proxy mode and LAN
clients).

## 2. Why not the obvious alternatives

- **Reuse libtailscale (official Go glue):** it runs tailscaled in-process and
  talks to LocalBackend in memory; our daemon is a separate PIE binary that is
  also used under `su` in Root Mode. Not reusable as-is.
- **`--tun=fd:N` in upstream:** does not exist; `--tun=<name>` always opens
  `/dev/net/tun` itself (`net/tstun/tun.go`).
- **Making the app's DNS proxy split-aware instead:** treats a symptom; the
  double stack remains. The author chose the rebuild.

## 3. Architecture (decided)

1. **Kotlin `TunVpnService`** builds `VpnService.Builder` from a JSON config
   the daemon produces (MTU 1280, IPv4+IPv6, addresses, DNS servers, search
   domains, routes, `excludeRoute` on API 33+, `addDisallowedApplication` for
   the app's own package plus the user's excluded apps, `setUnderlyingNetworks`,
   `setMetered(false)` on Q+), calls `establish()`, `detachFd()`, and hands the
   raw fd to the Go bridge. `onRevoke` → tell the daemon "vpn down" and show a
   "permission lost" notification; do not clear `tun_mode_enabled`.
2. **Go bridge (`appctr/`)** spawns the daemon with `--tun=android-vpn` only
   when `StartOptions.TunMode` is set (the `su` path never does) and an
   inherited AF_UNIX **socketpair**. Frames over it: `config`/`status` from the
   daemon; `tun` (fd via **SCM_RIGHTS**), `down`, `want_config`, `netstate` to
   the daemon; EOF → daemon exits. gomobile exports for Kotlin:
   `SetVpnController`, `ClearVpnController`, `RebuildVpn`, `VpnDown` and a
   `VpnController` interface. The bridge translates the daemon's router config
   into Builder input (mask routes, skip loopback, subtract excluded CIDRs,
   `excludeRoute` only where the API exists).
3. **Daemon: engine mode `android-vpn`** — new `cmd/tailscaled/android_vpn.go`
   (+ `multitun_android.go`, vendored from libtailscale's `multitun.go`), both
   `//go:build android`. A `feature.Hook` consulted in `tryEngine`
   (`cmd/tailscaled/tailscaled.go`, the `else` branch that today calls
   `tstunNew`) sets `conf.Tun` (the multiTUN), `conf.Router` and `conf.DNS`
   (both `router.CallbackRouter`) and `conf.ReconfigureVPN`; the flush sends
   `{LocalAddrs, Routes, LocalRoutes, Nameservers, SearchDomains}` to the bridge
   with a `reflect.DeepEqual` gate and waits for a fresh fd. Also:
   `onlyNetstack || androidVPNMode` for `dialer.UseNetstackForIP`; guard
   `router.CleanUp` so osrouter's cleanup never runs for this name; netstack
   created with `ProcessLocalIPs=false`, `ProcessSubnets=true`,
   `sys.NetstackRouter.Set(true)` exactly like `libtailscale/backend.go`.
   `tun.CreateUnmonitoredTUNFromFD` is the device constructor (it does
   `SetNonblock`, `os.NewFile`, `TUNGETIFF`, no MTU/netlink).
4. **Loop prevention is UID-based**: the app disallows its own package from the
   VPN; the child daemon shares the UID, so WireGuard/DERP/control traffic
   bypasses the tunnel. No `protect()` (not callable from another process), no
   `SO_MARK` (collides with netd's fwmark bookkeeping).
5. **Netstack stays** in TUN mode: it terminates 100.100.100.100 (MagicDNS,
   web client, Taildrive) locally and forwards peer traffic when this node is
   an exit node / subnet router (Android has no kernel forwarding).
6. **DNS (corrected by the critic):** `OverrideDNSResolvers` does **not** exist
   in v1.102.1 — what the bridge pushes today in `syncSettings` is silently
   discarded by LocalAPI. `DefaultResolvers` come only from the netmap / exit
   node; the "." upstream comes from `GetBaseConfigFunc`. Therefore the daemon's
   `baseDNS()` must never be empty: LinkProperties DNS from `netstate` frames →
   `TS_DNS_FALLBACK` → hard-coded public fallback (mirror
   `libtailscale/net.go` `getDNSBaseConfig`). Define the `CorpDNS=false` case
   explicitly (zero nameservers in the Builder → use the underlying network's
   DNS or document reliance on the platform fallback).
7. **Lock invariant:** the flush runs under wgengine's `wgLock`; the control
   channel loop in the daemon must be a pure dispatcher (read frame → hand off;
   ack by id) so it can never wait on a `wgLock` holder. On any failure after a
   `tun` frame: close the fd, reply `ok=false`. On ack timeout: error into
   Reconfig, no hang.
8. **Stop ordering:** bump generation → signal/kill daemon → wait → **then**
   close the channel. Daemon exits 0 after a clean `down`, non-zero only on
   unexpected EOF; the supervisor treats EOF-exit as expected when the
   generation was retired (otherwise every intentional Stop looks like a crash
   and fires `CloseCallBack` → `stopMe`).
9. **Underlying network:** pick it with a `NOT_VPN` `NetworkRequest` (prefer
   validated, non-metered), forward only that network's `LinkProperties` in
   `netstate` frames, use it for `setUnderlyingNetworks`. Filter `tun*`/VPN
   interfaces defensively on the daemon side.

## 4. Steps (each leaves the app shippable)

| # | Step | Where | Done when |
|---|------|-------|-----------|
| 0 | Patch hygiene | `recreate_patches.sh`: add diff targets for `android_vpn.go` and `multitun_android.go`, then decide whether the `tailscaled.go` hunks extend `08` or get a patch of their own. **Numbers up to `16-android-somark` are taken** (it ships in 4.0 and sets the root daemon's `SO_MARK 0x2000000`), so the next free one is `17`. The stray `drive_test.go.orig` hunk is already gone — `recreate_patches.sh` deletes leftover `*.orig`/`*.rej` and diffs with `-x`, so there is nothing to clean up here. | `build.sh` from a clean `orig/` reproduces all patches; `-F0` apply clean |
| 1 | Daemon `android-vpn` mode (dark) | `cmd/tailscaled/android_vpn.go`, `multitun_android.go`, hunks in `tailscaled.go` | `go build`/`go vet` for `GOOS=android`; daemon launched by hand from a root adb shell with `--tun=android-vpn` and a socketpair test harness accepts a tun fd created by a test VpnService and passes traffic; existing `userspace-networking` and `tailscale0` runs unchanged |
| 2 | Bridge: TunMode spawn path, fd channel, VpnController (dark) | `appctr/daemon.go`, `appctr/appctr.go`, new `appctr/vpn.go` | unit-level: config→Builder-input translation covered by Go tests (masking, loopback skip, CIDR subtraction); gomobile exports compile; nothing selects the mode yet |
| 3 | Kotlin native `TunVpnService` behind hidden `tun_engine_native` | `core/TunVpnService.kt`, `core/TailscaledService.kt` (`startTunMode`/`stopTunMode`), `core/GlobalSettings.kt` | opt-in beta works on the author's devices: MagicDNS, split DNS (bropines.ru → peer resolver), exit node on/off, network change, revoke, Always-on; hev still default |
| 4 | Flip default, migrate settings | `GlobalSettings`, Settings UI, strings, docs | `tun_engine_native=true`; keep `tun_mode_enabled`, `tun_excluded_apps`; delete `tun_full_tunnel`, `tun_address`, `tun_ipv6_enabled`, `tun_excluded_cidrs` (non-default CIDR list → `exit_node_allow_lan=true`, logged once) |
| 5 | Delete hev and the flag | `app/src/main/jni/hev-socks5-tunnel`, `.gitmodules`, `Application.mk`, JNI externals in `TunVpnService.kt`, R8 keeps for it; keep byedpi + `verifyReleaseNativeMethods` | one release after step 4 |
| 6 | Polish (optional) | base DNS parity with libtailscale; per-profile hostinfo masquerade switch; official disallowed-apps list | — |

## 5. Verify on a device before trusting the design

- `TUNGETIFF` / `SIOCGIFMTU` on the VpnService fd from the **child** process
  (same UID/SELinux domain, different process) on Android 7 and 8.
- SCM_RIGHTS between two processes of one `untrusted_app` UID — check logcat
  for `avc:` denials.
- One `100.64.0.0/10` route, not a /32 per peer
  (`shouldUseOneCGNATRoute: android automatic=true` in the daemon log).
- Always-on VPN with "block connections without VPN": does the daemon keep
  network access with the app's UID disallowed?
- What Android does with an established VPN whose VpnService process died while
  the child still holds the fd.
- Establish latency and packet loss on fd swap (exit node toggle, netmap
  change); confirm the DeepEqual gate avoids needless re-establishes.
- Work profiles / multi-user (`INTERACT_ACROSS_USERS`), and another VPN active
  (`establish()` returns null).
- Throughput at MTU 1280 vs today's hev path.

## 6. Where the details are

- Design page (human-readable, Russian):
  https://claude.ai/code/artifact/911ac729-57d2-4f78-96e0-7d0d23da8901
- Full research with `file:line` evidence, the architect's design and the
  critic's review: `docs/research/native-tun-design.json` (keys `research`,
  `design`, `critique`).
- Official client reference: https://github.com/tailscale/tailscale-android
  (`libtailscale/net.go` `updateTUN`, `libtailscale/multitun.go`,
  `libtailscale/backend.go` `newBackend`, `android/.../IPNService.kt`
  `newBuilder`).
- Upstream entry points in our tree: `appctr/tailscale_src/cmd/tailscaled/tailscaled.go`
  (`tryEngine`, `tstunNew`, `onlyNetstack`), `wgengine/userspace.go`
  (`Config.ReconfigureVPN`), `wgengine/router/callback.go`,
  `wgengine/netstack/netstack.go` (`ProcessLocalIPs`, `ProcessSubnets`,
  `handleLocalPackets`).
- Current TUN implementation to be replaced: `app/src/main/java/io/github/bropines/tailscaled/core/TunVpnService.kt`,
  `app/src/main/jni/hev-socks5-tunnel/`, `appctr/daemon.go` (spawn),
  `appctr/dns.go` (bridge DNS proxy, stays for proxy mode).

## 7. Definition of done for the whole effort

TUN mode on the author's POCO (Android 16, no root) and WSA (Android 13):
MagicDNS names, split-DNS domains served by tailnet peers, exit node with and
without LAN access, subnet routes, Taildrive via 100.100.100.100:8080, network
switch Wi-Fi ↔ cellular, VPN revoke and re-grant — all without hev in the APK,
with Proxy and Root modes behaving exactly as before, and `CHANGELOG.md`
describing the change under 4.1.0.
