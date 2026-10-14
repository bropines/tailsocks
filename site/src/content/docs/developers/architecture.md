---
title: Architecture
description: How the daemon, the bridge and the app fit together, and why each workaround exists.
---

TailSocks provides robust Tailscale connectivity on Android by running the engine in userspace. This page details the technical solutions used to overcome platform-specific limitations.

## Hybrid communication bridge

The system is divided into three functional layers:

- **Tailscale Daemon (Core):** A Position Independent Executable (PIE) shared library (`libtailscale.so`) built from upstream source with specific build tags to exclude non-mobile components (D-Bus, Kubernetes, etc.). The same file is the CLI: with upstream's `ts_include_cli` tag it runs as `tailscale` when invoked under that name (the Console) or with `TS_BE_CLI=1` (the Root Mode wrapper).
- **Management Bridge (`appctr`):** A Go-based controller that interacts with the daemon via local unix sockets and exposes a JNI-friendly API to the Android layer.
- **Kotlin/Compose UI:** The presentation layer that handles user preferences and renders real-time diagnostics.

## Multi-account isolation

To support multiple Tailscale profiles on a single device, TailSocks implements strict filesystem and configuration isolation:

- **State Redirection:** Each account is assigned a unique ID, mapping to an isolated state directory in `files/states/{id}`. This ensures that machine keys and netmap caches never overlap.
- **Netmap Cache:** tailscaled 1.104+ keeps the last network map in `files/states/{id}/profile-data/<profile>/netmap-cache/` and starts from it before the coordination server answers, when the server grants the node the `cache-network-maps` attribute. Settings → Censorship bypass can turn it off (`TS_USE_CACHED_NETMAP=false`) and clear it; backups leave it out.
- **Preference Scoping:** Settings are stored in account-specific `SharedPreferences` (`appctr_{id}`).
- **Process Lifecycle:** A full daemon termination and re-initialization occurs during account switching to prevent cross-account state leakage.

## Native DNS engine and reactive sync

Standard Android applications cannot route UDP packets into a userspace network map without a TUN interface. TailSocks solves this using a reactive, multi-stage resolution logic:

1. **IPN Bus Synchronization:** The Go bridge maintains a persistent connection to the daemon's internal notification bus (`mask=1032`). This allows real-time tracking of `NetMap` changes, MagicDNS suffixes, and `SplitDNSRoutes`.
2. **In-Memory Peer Resolution:** All nodes in the network are cached by their FQDN and short names. Resolution of `*.ts.net` names occurs in **0ms** by querying the internal memory map directly.
3. **Split DNS (TCP-over-SOCKS5):** For domains matching corporate routes (e.g., `therodev.com`), the bridge wraps UDP queries into TCP frames and tunnels them via SOCKS5 to the specific internal resolver IP provided by the netmap.
4. **Smart Upstream Fallback:** Public queries (e.g., `google.com`) are attempted via Tailscale's `dns-query` API first. If the daemon returns a `SERVFAIL` (common in userspace-only mode), the bridge automatically falls back to user-configured system/DoH resolvers.
5. **Answer Cache:** Every answer from steps 3–4 is kept for its TTL (floor 10 s, cap 1 h; NXDOMAIN and NODATA for 30 s or the SOA minimum if shorter). A hit is re-stamped with the client's transaction ID and its TTLs counted down. The cache is dropped together with the peer cache and whenever the split-DNS routes change, so a stale route never serves an old answer. Each query is logged at debug level with `cached=true|false`.

## Native diagnostics (Netcheck)

- **Android Limitations:** Permission restrictions (`netlinkrib: permission denied`) prevent the `tailscaled` daemon from identifying network interfaces, leading to failed diagnostics in the core.
- **Solution:** `appctr` implements a native `GetNetcheckFromAPI` method that runs the `tailscale.com/net/netcheck` package within the application process.
- **Interface Synchronization:** Using `TS_NET_STATE` environment variable, the bridge passes interface states (injected from Kotlin) to the core. Netcheck uses a `NewStatic` network monitor to provide accurate STUN/DERP reports bypassing daemon limitations.

## Taildrop and Files API

TailSocks implements a robust file transfer manager:

- **Inbound:** Uses the `TS_TAILDROP_DIR` environment variable to point the core at `files/taildrop/<account>` (`core/TaildropPaths.kt`) — a tree that holds user data only, kept apart from the node keys in `files/states/`.
- **DocumentsProvider:** `TailsocksFileProvider` exposes those per-account Taildrop directories (and the Taildrive WebDAV shares) to the Android Storage Access Framework (SAF). This allows users to browse Taildrop files using the system "Files" app.
- **Outbound:** Leverages the SAF to read files and streams them into the daemon's `file-put` API via the bridge, with proper URL path escaping for reliability.

## Self-healing and stateless configuration

To mitigate "sticky" routing issues common in userspace engines:

- **Explicit State Enforcement:** Every `tailscale up` command includes explicit "negation flags" (e.g., `--exit-node=`) to force the daemon to clear previous settings that are no longer selected in the UI.
- **Health Validation Loop:** A background task periodically validates that the selected Exit Node exists in the current account's netmap, automatically clearing the configuration if the node becomes unreachable or invalid.
- **Control Plane Proxy:** Supports global SOCKS5/HTTP proxies for coordination server communication. Specifically routes SOCKS5 traffic through `ALL_PROXY` with forced `HTTP_PROXY` clearing to prevent protocol errors (e.g., "unknown Socks version").

## Patch analysis and dependencies

TailSocks maintains minimal patches to the upstream Tailscale core to ensure mobile compatibility. These patches are located in `appctr/patches/` and inject necessary capabilities that cannot be replicated via the external LocalAPI:

- **`cmd/tailscaled/proxy.go`:** Adds `Username`/`Password` fields to the outbound SOCKS5 listener (read from env vars).
- **`feature/taildrop/ext.go`:** Registers a pure-Go `fsFileOps` to avoid Android JNI panics and points Taildrop to the app's isolated data dir.
- **`feature/taildrop/send.go`:** Copies an incoming file through the byte counter again, so `Notify.IncomingFiles` reports progress while it arrives (upstream 1.84+ skips it).
- **`ipn/ipnlocal/local.go`:** Appends VIP services to the `HostInfo` struct so Virtual Services (`svc:`) are visible to the coordination server.
- **`fix_android_netmon.go`:** Implements a custom `netmon.InterfaceGetter` to work around `netlink` permission denials on Android 10+ and, by default, presents the node to the control plane as a Linux CLI (`OS = linux`, `App = tailscale-cli`, `DeviceModel = Tailsocks`). With *Report the real OS* on (`TS_HONEST_HOSTINFO=1`) it reports the real Android device instead; nothing in the client depends on these fields.
