---
name: tailsocks-bridge-master
description: Specialized guidance for the Go-Kotlin bridge via gomobile. Use when working with JNI, AppCtr module, ApplySettings logic, and streaming logs to Android UI.
---

# TailSocks Bridge Master

Expert in the "Hedgehog Bridge" architecture between Go and Kotlin/Android.

## Gomobile Bind Strategy
- **Entry Point:** `appctr.go` is the main Go module compiled into `appctr.aar`.
- **Exported Types:** Always use exported structs (e.g., `StartOptions`) to pass configuration from Kotlin to Go.
- **Callbacks:** Use interfaces (e.g., `Closer`) to allow Go to signal events back to Kotlin (like service termination).
- **Binding Limits:** Stick to basic types (int, string, bool) and simple structs/interfaces. Avoid complex Go types (maps, channels) in the public JNI surface.

## ApplySettings (Intelligent Restart)
- **Restart Hierarchy:**
  1. **Full Restart:** If `Socks5Server`, `HttpProxy`, `AuthKey`, or `Socks5Auth` change.
  2. **DNS Only:** If `DnsProxy`, `DnsFallbacks`, or `DohFallback` change.
  3. **ReUp (Tailscale Up):** Always triggered to sync machine tags and policy changes from the Tailscale Admin Console.
- **Invariants:** Only one `daemon` process (`tailscaled`) and one `dnsProxy` should run at a time.

## Log Management
- **Streaming:** Go uses `slog` to stream logs. These are intercepted and passed to the UI.
- **Filtering:** Use `logWithFilter` to prevent context window bloat in the UI and logcat by omitting high-frequency network heartbeat logs.

## Android Integration
- **Context Handling:** Do NOT pass Android `Context` to Go. Use paths and configuration strings.
- **Paths:** Always use app-specific internal storage paths (`/data/data/com.example.app/...`) passed from Kotlin.
- **Wait for Socket:** When starting `tailscaled`, wait for the unix socket (`tailscaled.sock`) to be available before calling `local.Client` methods.
