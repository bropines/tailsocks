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

### 4. Verification
Try to ping or open a Tailscale node address (e.g., `my-pc.ts.net`). AdGuard should now forward these specific requests to TailSocks while keeping the rest of your traffic filtered and clean.
