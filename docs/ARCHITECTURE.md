# 🧠 Architecture & Deep Dive

TailSocks operates entirely in `userspace-networking` mode without utilizing Android's `VpnService`. This design bypasses strict system limitations (like `netlinkrib` access errors) but requires creative architectural solutions.

## 1. The DNS Routing Masterpiece

Standard Android applications cannot easily route UDP packets (standard DNS queries) into a userspace network without a TUN interface. To solve this, TailSocks features a custom-built local DNS server in Go (running on port 1053).

It operates using a tri-tier logic:
* **Local Netmap Resolution:** If you query a known local node, the proxy instantly extracts the IP directly from memory using the `tailscale ip` command.
* **UDP-to-TCP Wrapping (Split DNS):** For internal domains (e.g., `olegdev.com`), the proxy intercepts the system's UDP query, wraps it into a TCP frame, and forcefully pushes it through our SOCKS5 tunnel directly to Tailscale's internal DNS coordinator (`100.100.100.100`).
* **External DoH Fallback:** Queries for the public web (e.g., `google.com`) completely bypass the Go daemon. They are routed directly to configured DoH servers (like Cloudflare) or native ad-blockers like AdGuard. This ensures zero local DNS leaks, ultra-fast pings, and massive battery savings.

## 2. Daemon State-Machine Anti-Deadlock

A critical flaw in the official userspace-networking implementation causes the daemon to hang (`i/o timeout`) when restarting the app without clearing the cache. The daemon struggles to correctly update routing paths from its previous state. 
* **The Fix:** We hardcoded the `--reset` flag on every daemon startup. This forces the userspace state machine to cleanly rebuild the virtual network upon every initialization, guaranteeing a 100% stable startup.

## 3. UI Thread Stabilization

Rapidly toggling the proxy connection previously caused race conditions. 
* **The Fix:** We implemented a 3-second software debounce in the Jetpack Compose UI. This grace period provides the daemon ample time to gracefully close sockets and save state files before a new instance is spawned.

## 4. Web UI Integration

An asynchronous controller continuously monitors the tunnel status. Once the connection is successfully established, it spins up the official Tailscale Web UI server locally at `127.0.0.1:8080`, accessible via a single tap from the app settings.