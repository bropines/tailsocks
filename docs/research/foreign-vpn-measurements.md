# Foreign-VPN coexistence — facts measured on the device

Device: Redmi, APatch root, `192.168.1.94:5555`. Foreign VPN: Exclave
(`com.github.dyhkwong.sagernet`, uid 10354), session name `Exclave`, `bypassable=false`.
TailSocks build `fe02d6`, Root Mode with an exit node selected.
All of the following was read off the device, not inferred.

## Identities

| Thing | Value |
|---|---|
| Exclave uid | 10354 |
| TailSocks uid | 10375 |
| Chrome uid | 10361 |
| Exclave interface / address | `tun0`, `172.19.0.1/30` |
| Exclave DNS server | `172.19.0.2` |
| Exclave netId | `0x84` (132) |
| Wi-Fi (`wlan0`) netId | `0x73` (115) |

## netd's rules for a foreign VpnService

With Exclave up, `ip rule show` gains (v4 and v6 identically):

```
12000:	from all iif tun0 lookup local_network
13000:	from all fwmark 0xc0084/0xcffff lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 0-10353 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10355-10374 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10376-20353 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 20355-20374 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 20376-99999 lookup tun0
16000:	from all fwmark 0x10084/0x1ffff iif lo uidrange <same ranges> lookup tun0
17000:	from all iif lo oif tun0 uidrange <same ranges> lookup tun0
28000:	from all fwmark 0xc0084/0xdffff lookup wlan0
```

Four things follow, and they are the whole design:

1. **VPN membership is expressed as uid ranges, in the clear, readable from root.**
   The ranges are the *members*; the gaps are the apps the VPN bypasses. Here the gaps are
   exactly 10354 (Exclave itself) and 10375 (TailSocks) — Exclave already excludes us.
2. **netd's VPN rules start at 13000.** Our catch-all sits at priority 200 and therefore
   beats them for every uid, which is precisely why the foreign client's apps break.
   Measured consequence: `ip route get 1.1.1.1 uid 10361` → `dev tailscale0 table 52`,
   even though Chrome is a member of Exclave's tunnel.
3. **The selector is `fwmark 0x0/0x20000`** — "protectedFromVpn not set". Our daemon's
   protect bit (patch 16) therefore keeps its own sockets out of the foreign tunnel, as
   designed.
4. **Widening `CATCH_ALL_MASK` does not help.** Measured directly:
   `ip route get 1.1.1.1 mark 0x84 uid 10361` still returns `dev tailscale0 table 52`.
   Ordinary app traffic carries netId 0 in the mark, so no mask over the netId field
   changes the outcome. Do not attempt this as a coexistence fix.

## DNS carries the netId, so DNS can be split precisely

Counters in `mangle OUTPUT` on `udp --dport 53`, sampled over 18 s while a page was opened
in Chrome:

| mark (mask `0x1ffff`) | packets | meaning |
|---|---|---|
| `0x10084` | 6 | resolver working on behalf of an app **inside** Exclave's tunnel |
| `0x73` | 18 | physical-network resolution |
| `0x84`, `0x10073` | 0 | — |

So the netId lives in the low 16 bits and the "explicitly selected" bit varies.
**`-m mark --mark 0x84/0xffff -j RETURN` at the top of `TAILSOCKS_DNS` leaves the foreign
VPN's DNS alone** while we keep everything else. This works for platform-resolver traffic,
which per-uid matching cannot cover (an ordinary app's `getaddrinfo` is emitted by netd, not
by the app).

Today we do steal it: the daemon logs, at a rate high enough to trip the rate limiter,

```
netstack inject ts-service: src=100.100.100.100 dst=172.19.0.1 sendToHost=true ...
```

`172.19.0.1` is Exclave's own tunnel address. The queries reach MagicDNS carrying a source
address that belongs to another tunnel, because `nat OUTPUT` rewrites the destination *after*
the kernel has already chosen the source. Same root cause as the mangle-mark exclusion that
was removed in M4.

## The foreign client behaves correctly on its side

Exclave's route set covers the IPv4 space in `/8`-sized pieces but deliberately steps around
the CGNAT range: it installs `100.0.0.0/10` and `100.128.0.0/9` and **not** `100.64.0.0/10`.
Together with its exclusion of uid 10375, this means our T1 (tailnet reachability by
destination) coexists with it by construction — the tailnet stays reachable from inside its
tunnel with no configuration on either side.

## The proxy already coexists with Root Mode

The daemon in Root Mode is running with `--socks5-server=127.0.0.1:48115` (port from the
app's settings), and that proxy egresses **through the exit node**:

| probe | result |
|---|---|
| direct from the device | `2a0b:4142:f7a::2` (exit node, v6) |
| through the SOCKS proxy | `2a0b:4142:f7a::2` |
| through the SOCKS proxy, forced v4 | `144.31.125.162` (exit node, v4) |
| tailnet peer through the SOCKS proxy | HTTP 200 |

So "Root Mode or proxy" is a false choice — they run together today. Any front-end that does
per-app upstream selection (the author's AdGuard + Exclave + TailSocks stack) can send chosen
apps to us, with exit-node egress, while another VPN owns the device.

## Method note — do not repeat this mistake

`su <uid> -c ...` is **not** a valid way to test an app's connectivity on this device. Sockets
created that way are denied before routing: `ping` from uid 10361 returns
`sendmsg: Operation not permitted` even to the LAN gateway, which our rules never touch, while
uid 1000 and uid 2000 succeed on the same targets. Android's per-uid enforcement is in BPF
(`prog_netd_skfilter_egress_xtbpf`, reached from `bw_mangle_POSTROUTING`), i.e. *after* any
probe placed at the head of `mangle POSTROUTING`, so such a probe counts packets that are
then silently dropped. Measure real app traffic with `-m owner --uid-owner` counters instead,
and read `ip route get <dst> uid <uid>` for the routing decision.
