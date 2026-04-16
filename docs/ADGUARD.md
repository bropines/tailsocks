# 🛡️ Using TailSocks DNS with AdGuard

To use TailSocks internal DNS (for `.ts.net` and split-DNS resolution) alongside AdGuard, follow these steps to avoid routing loops and ensure fast resolution.

### 1. Configure TailSocks DNS
*   Open **TailSocks Settings**.
*   In **DNS Settings**, set a **DNS Proxy Listen Address** (e.g., `127.0.0.1:1053`).
*   Ensure the service is **Active**.

### 2. Disable AdGuard Filtering for TailSocks (Crucial!)
To prevent a recursive routing loop (AdGuard -> TailSocks -> AdGuard):
*   Go to AdGuard **App Management**.
*   Find **TailSocks**.
*   **Turn off** "Route traffic through AdGuard" (or "AdGuard protection").

### 3. Configure AdGuard Low-Level Settings
*   Open AdGuard **Settings**.
*   Go to **General Settings** -> **Advanced** -> **Low-level settings**.
*   Find **Fallback upstream servers** (or `dns.fallback_upstream`).
*   Add your TailSocks DNS address on a new line:
    ```text
    127.0.0.1:1053
    ```
*   Find **Fallback domains** (or `dns.fallback_domains`).
*   Add the following patterns to ensure Tailnet domains are resolved via TailSocks:
    ```text
    *.ts.net
    *.YOUR_SPLIT_DOMAIN.site
    ```
    *(Replace `YOUR_SPLIT_DOMAIN.site` with your actual corporate or private domain configured in Tailscale).*

### 4. Connect AdGuard to TailSocks Proxy (Mandatory)
Since TailSocks runs in userspace, resolving a `.ts.net` IP is not enough—AdGuard must also know how to reach that IP. You **must** route the traffic through the SOCKS5 proxy.

#### Option A: Direct Proxy (Simple)
*   In AdGuard, go to **Settings** -> **Proxy**.
*   Add a **SOCKS5 Proxy**: `127.0.0.1:1055` (or your configured port).
*   Enable it for all traffic or use AdGuard's "Proxy applications" rules.

#### Option B: Chain via Exclave/Rules (Advanced)
If you use a setup like `AdGuard -> Exclave -> TailSocks`, ensure you have a specific routing rule:
*   Route all `*.ts.net` and `100.64.0.0/10` traffic to the TailSocks SOCKS5 proxy.

### 5. FAQ: Internet Routing without an Exit Node
**Question:** If I don't specify an **Exit Node**, where does my traffic go?

**Answer:**
*   **Tailnet Traffic (Internal):** Requests to 100.x.y.z or `*.ts.net` are always routed through the encrypted Tailscale tunnel to your other nodes.
*   **Internet Traffic (Public):** If no Exit Node is selected, the Tailscale daemon simply bridges public requests to your phone's **normal network** (Wi-Fi or 4G/5G). It works like a regular local proxy.
*   **With an Exit Node:** All traffic is encrypted and "shipped" to the Exit Node, making it appear as if you are browsing from that remote machine.

### 6. Verification
Try to ping or open a Tailscale node address (e.g., `my-pc.ts.net`). AdGuard should now forward these specific requests to TailSocks while keeping the rest of your traffic filtered and clean.
