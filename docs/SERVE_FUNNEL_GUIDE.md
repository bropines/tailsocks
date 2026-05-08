# Tailscale Serve, Funnel & Services: Complete Guide for TailSocks

*Note: Starting from v2.0-beta, the Serve UI has been overhauled with a chip-based interface and two-step LocalAPI synchronization for improved reliability.*

TailSocks allows you to expose local services to your Tailnet (Serve) or the public internet (Funnel) using Tailscale's userspace engine. Since TailSocks does not use a VPN TUN interface, these features are essential for making local resources accessible.

## 1. Tailscale Serve (Internal Access)
Serve makes a local port available to other devices in your Tailnet under your machine's DNS name (e.g., `my-phone.tailnet-1234.ts.net`).

### Handler Types:
*   **Proxy:** Forwards traffic to a local address (e.g., `127.0.0.1:8080`). This is the most common use case for web apps.
*   **Path (Static Dir):** Serves files from a specific directory on your Android device. *Note: Ensure TailSocks has "All Files Access" or relevant permissions.*
*   **Text:** Directly serves a plaintext or HTML string. Useful for simple status pages or debugging.
*   **Redirect:** Sends an HTTP 302/301 redirect to another URL. Supports variables like `${HOST}` and `${REQUEST_URI}`.

### Protocol Options:
*   **Web (HTTPS/HTTP):** Tailscale manages TLS certificates automatically for HTTPS.
*   **TCP Forward:** Raw byte-stream forwarding.
    *   **Terminate TLS:** Tailscale decrypts the connection before forwarding it to your local target.
    *   **Proxy Protocol:** Sends a [PROXY protocol](https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt) header (v1 or v2) to the target, preserving the original client's IP address.

---

## 2. Tailscale Funnel (Public Access)
Funnel extends Serve by allowing traffic from the **public internet** to reach your device.

### Requirements:
1.  **HTTPS Enabled:** Your tailnet must have MagicDNS and HTTPS enabled.
2.  **ACL Permissions:** You must have the `funnel` capability in your tailnet policy.
3.  **Restricted Ports:** Funnel only works on specific ports: `443`, `8443`, and `10000`.

### ACL Configuration (example):
Add this to your tailnet policy via the Tailscale Admin Console:
```hujson
"nodeAttrs": [
    {
        "target": ["tag:server", "my-phone@example.com"],
        "attr": ["funnel"]
    }
]
```

---

## 3. Tailscale Services (`svc:`)
Tailscale Services allow you to host a service under a **different hostname** than your machine name. For example, your machine `my-phone` can host a service at `webapp.tailnet-1234.ts.net`.

### Features & Limitations:
*   **Independent Hostname:** Accessible via `https://service-name.tailnet.ts.net`.
*   **Manual Approval Required:** After creating a virtual service in TailSocks, you **must** log into the Tailscale Admin Console (Web UI), navigate to the "Machines" tab, find the new service, and manually approve it before it becomes accessible on the Tailnet.
*   **No L3 Tun Mode:** Because TailSocks runs in userspace-networking mode without a VpnService, the L3 Tun mode (forwarding all traffic to a virtual IP) is **not supported**. You can only use Serve/Funnel on specific ports.
*   **ACL Requirements:** Requires a `tag` or `service` definition in your ACLs if you are using advanced policies.

---

## 🛠 Using Serve & Funnel in TailSocks

1.  Open **Serve & Funnel** from the main menu.
2.  Tap the **(+)** button to add a new rule.
3.  Choose between **Node-scoped** (uses machine name) or **Service-scoped** (uses a custom name).
4.  Select **TCP** or **Web** mode.
5.  Configure the **Target** (e.g., `127.0.0.1:8000`).
6.  Toggle **Funnel** if you need public access (ensure ACLs are set!).
7.  Tap **Add**. The link will be generated automatically.

### Variables in Redirects:
*   `https://example.com/${REQUEST_URI}`: Redirects the user while preserving the path.
*   `301:https://newsite.com`: Permanent redirect.

### Proxy Protocol:
*   **v1:** Human-readable text header (e.g., `PROXY TCP4 1.2.3.4 ...`).
*   **v2:** Binary header (more efficient).
Use this if your backend server (Nginx, Go, etc.) supports and expects PROXY protocol headers.
