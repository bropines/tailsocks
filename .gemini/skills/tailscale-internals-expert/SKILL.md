---
name: tailscale-internals-expert
description: Deep expertise in Tailscale source code, including tsnet, ipn, wgengine, and patching for Android integration. Use when modifying tailscale_src, handling proxy authentication, or optimizing the Go-based core.
---

# Tailscale Internals Expert (TailSocks Edition)

You are an expert in Tailscale's Go-based architecture, specifically as it relates to the TailSocks Android implementation.

## Core Architecture
- **Daemon Mode:** We run `tailscaled` as a PIE binary (`libtailscale.so`) in userspace mode (`--tun=userspace-networking`).
- **Bridge:** Managed via `appctr` Go module, which handles process lifecycle, environment variables, and log filtering.
- **Paths:** Uses `pathControl` to manage unique locations for sockets, state, and binaries on Android.

## Patching Mandates
- **SOCKS5 Auth:** Tailscale's default `proxy.go` doesn't support inbound SOCKS5 auth.
  - Patch file: `tailscale_src/cmd/tailscaled/proxy.go`.
  - Injection: `Username: os.Getenv("TS_SOCKS5_USER")`, `Password: os.Getenv("TS_SOCKS5_PASS")` inside the `&socks5.Server` struct.
- **Android Netmon:** Always include `patches/fix_android_netmon.go` to use `anet` for network change detection on Android.

## Optimization & Build Tags
- **Binary Size:** Use heavy stripping via `TAGS` (e.g., `ts_omit_ssh`, `ts_omit_kube`, `ts_omit_taildrop`).
- **Linker Flags:** Always use `-s -w -checklinkname=0` to ensure compatibility with Android's linker and reduce size.

## Debugging & Logs
- **Log Filtering:** `appctr` filters noisy logs (magicsock, netcheck, ratelimit) by default.
- **Environment:** `TS_LOGS_DIR` should point to a writable app data directory.
- **No-Support Logs:** Set `TS_NO_LOGS_NO_SUPPORT=true` to reduce disk I/O on Android.

## Workflows
- **Sync with Upstream:** When updating `tailscale_src`, always re-apply `sed` patches from `build.sh`.
- **DNS Proxying:** The custom DNS server (port 1053) wraps UDP over SOCKS5 to bypass Android's UDP restrictions. Use `RestartDNS` in `appctr` for independent lifecycle management.
- **Auth Key Sync:** `registerMachineWithAuthKey` (ReUp) is used for initial login and tag sync.
