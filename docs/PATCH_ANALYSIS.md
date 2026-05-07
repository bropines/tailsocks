# Patch Analysis: What Can Be Moved to `appctr`?

This document analyses each modification in `tailsocks.patch` and `fix_android_netmon.go`,
classifying them as **required in source** or **movable to appctr**.

---

## 1. `cmd/tailscaled/proxy.go` — SOCKS5 Authentication

**What it does:** Adds `Username`/`Password` fields (read from `TS_SOCKS5_USER` / `TS_SOCKS5_PASS`
env vars) to the `socks5.Server` struct that `tailscaled` creates for its outbound SOCKS5 listener.

**Can it move to `appctr`?** ❌ **No.**
The SOCKS5 server struct is instantiated deep inside `tailscaled`'s startup path
(`cmd/tailscaled/proxy.go`). There is no LocalAPI endpoint to configure SOCKS5 credentials
after the fact. The env vars themselves **are** set by `appctr/daemon.go` at process launch —
that part is already handled at the appctr layer. The patch just makes the daemon read them.

---

## 2. `feature/taildrop/ext.go` — Custom FS Provider + `TS_TAILDROP_DIR`

**What it does:**
- Registers a pure-Go `fsFileOps` file system provider to avoid a JNI panic on Android.
- Reads `TS_TAILDROP_DIR` env var to set the Taildrop receive directory.

**Can it move to `appctr`?** ❌ **No** (FS provider) / ✅ **Already there** (`TS_TAILDROP_DIR`).
The `fsFileOps` implementation must be compiled into the Tailscale binary — it hooks into the
internal `newFileOps` function pointer which has no public API surface. The directory path
itself is already controlled via `TS_TAILDROP_DIR` set in `appctr/daemon.go`.

---

## 3. `ipn/ipnlocal/local.go` — VIP Services in `HostInfo`

**What it does:** Replaces the `ShouldUploadServices` hook check (which returns `false` on
Android) with a direct append of VIP services to `hi.Services` before each map update is
sent to the coordination server.

**Can it move to `appctr`?** ❌ **No.**
This is deep inside the daemon's `sendMapRequest` path. There is no LocalAPI surface to
inject into `HostInfo` construction. Without this patch, the coordination server's NetMap
response never includes the VIP service node entry, making the service invisible to other peers
regardless of `AdvertiseServices` state.

---

## 4. `ipn/ipnlocal/serve.go` — `Active: true` for ServeConfig Services

**What it does:** Sets `Active: true` when building a `VIPService` entry from `ServeConfig`
(the block that processes TCP service rules).

**Can it move to `appctr`?** ⚠️ **Partially redundant, but kept as a safety net.**
After the `AdvertiseServices` PATCH fix (implemented in `appctr/localapi.go`), the daemon's
`vipServicesFromPrefsLocked` now sets `Active: true` via the `AdvertiseServices` loop. This
makes the `serve.go` patch technically redundant for the normal flow.

However, it covers an edge case: a service present in `ServeConfig` but not yet in
`AdvertiseServices` (e.g. race condition during startup). Keeping this patch costs nothing
and adds robustness.

**Verdict:** Retain the patch. It is a one-liner safety net.

---

## 5. `fix_android_netmon.go` — Android Network Monitor + HostInfo Masking

**What it does:**
- Registers a custom `InterfaceGetter` via `netmon.RegisterInterfaceGetter` to work around
  `netlink` permission denial on Android 10+. Interface state is injected via `TS_NET_STATE`
  env var (populated by `appctr/appctr.go:InjectNetworkState`).
- Registers a `HostinfoNewHook` that sets `OS="linux"`, `App="tailscale-cli"`, and
  `DeviceModel="Tailsocks"` to bypass mobile-specific policy restrictions on the
  coordination server side.

**Can it move to `appctr`?** ❌ **No.**
Both hooks (`RegisterInterfaceGetter`, `RegisterHostinfoNewHook`) are `init()`-time
registrations inside the `tailscaled` process. They must be compiled into the daemon binary.
The env-var injection (`TS_NET_STATE`) **is** the appctr-side integration point — it is
already implemented in `appctr/appctr.go`.

---

## Summary Table

| Patch | Component | Required in Source | Appctr Integration |
|---|---|---|---|
| SOCKS5 auth | `proxy.go` | ✅ Yes | `TS_SOCKS5_USER/PASS` env vars (daemon.go) |
| Taildrop FS | `taildrop/ext.go` | ✅ Yes | `TS_TAILDROP_DIR` env var (daemon.go) |
| VIP in HostInfo | `local.go` | ✅ Yes | None possible |
| Active: true | `serve.go` | ⚠️ Safety net | `AdvertiseServices` PATCH (**the real fix**, localapi.go) |
| Android NetMon + Masking | `fix_android_netmon.go` | ✅ Yes | `TS_NET_STATE` injection (appctr.go) |

**Conclusion:** The only patch that was made redundant by appctr-level work is the `serve.go`
`Active: true` patch. All other patches address hooks, struct fields, and internal functions
that have no equivalent LocalAPI surface and cannot be replicated externally.
