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
```json
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
*   **Create first, then approve:** define the service in the Tailscale Admin Console (**Services** page) before this node advertises it, then — once the node has advertised it — approve the node as a host there. Alternatively let the policy do it with `autoApprovers.services`. Until both happen the tailnet does not answer on the service name.
*   **No L3 Tun Mode:** Because TailSocks runs in userspace-networking mode without a VpnService, the L3 Tun mode (forwarding all traffic to a virtual IP) is **not supported**. You can only use Serve/Funnel on specific ports.
*   **ACL Requirements:** Requires a `tag` or `service` definition in your ACLs if you are using advanced policies.

---

## 🛠 Using Serve & Funnel in TailSocks

The screen opens on a card for this node: its address, whether it can hold an HTTPS
certificate, whether Funnel is allowed and on which ports, and whether it may host
services (a tagged node). Below it is one list of rules. Each card shows the address a
rule is reached at, what it does, whether it is **Public** (Funnel) or **Tailnet**-only,
and whether something answers on its target.

1.  Tap **+**.
2.  Choose what to expose: a **local service** (HTTP proxy), a **text**, a **redirect** or a raw **TCP port**.
3.  Enter the target and the port on this node — or several, `443, 2550`, one daemon entry each; 443, 8443 and 10000 — the Funnel ports — are one tap away.
4.  Turn on **Public internet (Funnel)** if the rule should be reachable from outside. When the switch cannot be turned on it says why: no Funnel capability, a port Funnel does not allow, plain HTTP, or a service.
5.  For a new rule, pick the scope: **this device**, or a **service** (`svc:name`, tagged nodes only). After saving, the app offers to define the service in the tailnet and approve this node as its host through the Admin API (Settings → Admin API must be set up; the device credential is asked first); without the API, do both in the admin console. The same action is in the card menu as **Publish in the tailnet**.
6.  **Advanced** holds the daemon's own terms: the mount path (several handlers can share one port), plain HTTP instead of HTTPS, a backend with a self-signed certificate (`https+insecure://`), TLS termination and PROXY protocol for TCP rules.
7.  Tap **Add**. A card's menu offers edit, copy link and delete; the screen's menu exports the HTTPS certificate or removes every rule.

### Variables in Redirects:
*   `https://example.com/${REQUEST_URI}`: Redirects the user while preserving the path.
*   `301:https://newsite.com`: Permanent redirect.

### Proxy Protocol:
*   **v1:** Human-readable text header (e.g., `PROXY TCP4 1.2.3.4 ...`).
*   **v2:** Binary header (more efficient).
Use this if your backend server (Nginx, Go, etc.) supports and expects PROXY protocol headers.
