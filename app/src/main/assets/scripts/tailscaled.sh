#!/system/bin/sh
# TailSocks Root Autostart Service Script

# %PKG_NAME% and %DAEMON_BIN% are filled in by RootUtils.setServiceScriptInstalled.
PKG="%PKG_NAME%"
[ ! -d "/data/data/$PKG" ] && PKG="io.github.bropines.tailscaled"
[ ! -d "/data/data/$PKG" ] && PKG="io.github.bropines.tailscaled.dev"

DATA_DIR="/data/data/$PKG"
[ ! -d "$DATA_DIR" ] && exit 1

LOGS_DIR="$DATA_DIR/logs"
mkdir -p "$LOGS_DIR"

# State Directory Resolution:
# 1. Check default state dir
# 2. Search for any existing tailscaled.state in states/
# 3. Fallback to states/root
STATE_DIR="$DATA_DIR/files/states/default"
if [ ! -f "$STATE_DIR/tailscaled.state" ]; then
    FOUND_STATE="$(find "$DATA_DIR/files/states" -name "tailscaled.state" 2>/dev/null | head -n1)"
    if [ -n "$FOUND_STATE" ]; then
        STATE_DIR="$(dirname "$FOUND_STATE")"
    else
        STATE_DIR="$DATA_DIR/files/states/root"
    fi
fi
mkdir -p "$STATE_DIR"

SOCKET_PATH="$DATA_DIR/files/tailscaled.sock"
LOG_FILE="$LOGS_DIR/tailscaled.log"

# The daemon binary is this package's own libtailscale.so, baked in at install
# time. If that path is stale (the install directory moved and the app has not
# re-installed the script yet), derive it from the package manager — for THIS
# package only, once it is up. Never search the filesystem for a file called
# libtailscale.so: any other app can ship one, and this runs as root.
DAEMON_BIN="%DAEMON_BIN%"
if [ ! -x "$DAEMON_BIN" ]; then
    i=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ $i -lt 60 ]; do sleep 1; i=$((i+1)); done
    APK_PATH="$(pm path "$PKG" 2>/dev/null | head -n1 | cut -d: -f2)"
    for arch in arm64 arm x86_64 x86; do
        CANDIDATE="$(printf '%s' "$APK_PATH" | sed "s|base.apk|lib/$arch/libtailscale.so|")"
        if [ -n "$APK_PATH" ] && [ -x "$CANDIDATE" ]; then DAEMON_BIN="$CANDIDATE"; break; fi
    done
fi

if [ ! -x "$DAEMON_BIN" ]; then
    echo "TailSocks daemon binary not found" >> "$LOG_FILE"
    exit 1
fi

export TS_LOGS_DIR="$LOGS_DIR"
export TS_NO_LOGS_NO_SUPPORT=true
export TS_AUTH_ONCE=true
export TS_DNS_FALLBACK="1.1.1.1,8.8.8.8"

# Control-proxy environment written by TailSocks through su. Only the root-owned
# copy is honoured: the app data directory is writable by the app uid (and by a
# restored backup), so nothing from there may ever be sourced by root. Lines are
# parsed, not eval'd — the accepted grammar is exactly what RootUtils emits:
#   export NAME='single-quoted value'   (embedded quotes written as '\'')
ENV_FILE="/data/adb/tailsocks/control_proxy.env"
if [ -f "$ENV_FILE" ] && [ "$(stat -c %u "$ENV_FILE" 2>/dev/null)" = "0" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            export\ [A-Z_]*=\'*\')
                name="${line#export }"; name="${name%%=*}"
                case "$name" in *[!A-Z_0-9]*) continue;; esac
                value="${line#*=\'}"; value="${value%\'}"
                value="$(printf '%s' "$value" | sed "s/'\\\\''/'/g")"
                export "$name=$value"
                ;;
        esac
    done < "$ENV_FILE"
fi

# Log rotation: if log > 2MB, keep last 500 lines
if [ -f "$LOG_FILE" ]; then
    LOG_SIZE="$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)"
    if [ "$LOG_SIZE" -gt 2097152 ] 2>/dev/null; then
        tail -n 500 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
    fi
fi

# Everything the daemon appends after this offset belongs to this run; the
# exit-node catch-all below is gated on a line from THIS run, not an older one.
# An unknown size leaves no safe window to search, so the gate then stays shut
# rather than falling back to the whole file (an older run's line must never
# vouch for this one).
LOG_START=""
if [ -f "$LOG_FILE" ]; then
    LOG_START="$(stat -c %s "$LOG_FILE" 2>/dev/null)"
    [ -n "$LOG_START" ] || LOG_START="$(wc -c < "$LOG_FILE" 2>/dev/null | tr -d ' ')"
else
    LOG_START=0
fi
case "$LOG_START" in ''|*[!0-9]*) LOG_START="";; esac

# Run marker for the app: RootUtils.daemonMarksSockets bounds "this run" by the
# last such line when TailSocks adopts a daemon this script started (this core
# prints no start banner of its own). Same text as RootUtils.RUN_MARKER; lines
# tagged 'TailSocks:' are never taken as daemon output.
echo "TailSocks: daemon start" >> "$LOG_FILE"

# Run daemon with resolved STATE_DIR (native safesocket patch forces 0666 on tailscaled.sock)
nohup "$DAEMON_BIN" --statedir="$STATE_DIR" --socket="$SOCKET_PATH" --tun=tailscale0 >> "$LOG_FILE" 2>&1 &
chmod 666 "$LOG_FILE" 2>/dev/null || true
magiskpolicy --live "allow untrusted_app magisk unix_stream_socket connectto" 2>/dev/null || supolicy --live "allow untrusted_app magisk unix_stream_socket connectto" 2>/dev/null || true
# Wait for daemon socket then apply table 1099 routing safely
for i in $(seq 1 30); do
    if [ -S "$SOCKET_PATH" ] || [ -e "$SOCKET_PATH" ]; then
        chmod 777 "$SOCKET_PATH"
        chcon u:object_r:app_data_file:s0 "$SOCKET_PATH" 2>/dev/null || true
        chmod 777 "$STATE_DIR" 2>/dev/null || true

        # Wait for tailscale0 and apply table 1099 policy routing.
        # Rules live in dedicated chains so they stay idempotent across reboots
        # and can be removed in one shot; TailSocks uses the same layout.
        for j in $(seq 1 30); do
            if ip link show tailscale0 >/dev/null 2>&1; then
                # Drop rules left by TailSocks 3.5.x, which appended to OUTPUT directly
                # and used the bare mark 1099 (it overwrote Android's own fwmark bits).
                while iptables -t mangle -D OUTPUT -d 100.64.0.0/10 -j MARK --set-mark 1099 2>/dev/null; do :; done
                while ip6tables -t mangle -D OUTPUT -d fd7a:115c:a1e0::/48 -j MARK --set-mark 1099 2>/dev/null; do :; done
                while ip rule del fwmark 1099 table 1099 2>/dev/null; do :; done
                while ip rule del fwmark 1099 lookup 1099 2>/dev/null; do :; done
                while iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination 100.100.100.100:53 2>/dev/null; do :; done
                while iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination 100.100.100.100:53 2>/dev/null; do :; done
                while iptables -t nat -D OUTPUT -d 100.64.0.0/10 -p udp --dport 53 -j ACCEPT 2>/dev/null; do :; done
                while iptables -t nat -D OUTPUT -d 100.64.0.0/10 -p tcp --dport 53 -j ACCEPT 2>/dev/null; do :; done

                # Same layout as RootUtils.applyTailscale0Routing: a single high
                # fwmark bit set through a mask so Android's netId/flag bits survive.
                ip route replace 100.64.0.0/10 dev tailscale0 table 1099 metric 1
                ip rule del fwmark 0x1000000/0x1000000 table 1099 2>/dev/null || true
                ip rule add fwmark 0x1000000/0x1000000 table 1099 priority 100
                # Destination rule too (same as RootUtils): the fwmark is applied
                # after source selection, so root-owned sockets — the daemon's own
                # DNS forwarder — otherwise leave tailscale0 with the Wi-Fi source.
                ip rule del to 100.64.0.0/10 table 1099 2>/dev/null || true
                ip rule add to 100.64.0.0/10 table 1099 priority 100

                iptables -t mangle -N TAILSOCKS_MARK 2>/dev/null || iptables -t mangle -F TAILSOCKS_MARK
                iptables -t mangle -A TAILSOCKS_MARK -j MARK --set-xmark 0x1000000/0x1000000
                iptables -t mangle -C OUTPUT -d 100.64.0.0/10 -j TAILSOCKS_MARK 2>/dev/null || iptables -t mangle -A OUTPUT -d 100.64.0.0/10 -j TAILSOCKS_MARK

                iptables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -o tailscale0 -j ACCEPT
                iptables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -i tailscale0 -j ACCEPT

                ip -6 route replace fd7a:115c:a1e0::/48 dev tailscale0 table 1099 metric 1 2>/dev/null || true
                ip -6 rule del fwmark 0x1000000/0x1000000 table 1099 2>/dev/null || true
                ip -6 rule add fwmark 0x1000000/0x1000000 table 1099 priority 100 2>/dev/null || true
                ip -6 rule del to fd7a:115c:a1e0::/48 table 1099 2>/dev/null || true
                ip -6 rule add to fd7a:115c:a1e0::/48 table 1099 priority 100 2>/dev/null || true

                ip6tables -t mangle -N TAILSOCKS_MARK 2>/dev/null || ip6tables -t mangle -F TAILSOCKS_MARK
                ip6tables -t mangle -A TAILSOCKS_MARK -j MARK --set-xmark 0x1000000/0x1000000 2>/dev/null || true
                ip6tables -t mangle -C OUTPUT -d fd7a:115c:a1e0::/48 -j TAILSOCKS_MARK 2>/dev/null || ip6tables -t mangle -A OUTPUT -d fd7a:115c:a1e0::/48 -j TAILSOCKS_MARK 2>/dev/null || true

                ip6tables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || true
                ip6tables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || true

                # System-wide DNS redirect to MagicDNS, installed only when both
                # "accept_dns" (MagicDNS) and "root_dns_redirect" are on — the same
                # gate the app applies. Both default to true and are written to the
                # global prefs only once the user flips them. It sits before the
                # exit-node section on purpose: that one may wait up to 20 s and the
                # redirect does not depend on it.
                PREFS_XML="$DATA_DIR/shared_prefs/tailsocks_global.xml"
                DNS_REDIRECT=1
                if [ -f "$PREFS_XML" ]; then
                    grep -q 'name="accept_dns" value="false"' "$PREFS_XML" 2>/dev/null && DNS_REDIRECT=0
                    grep -q 'name="root_dns_redirect" value="false"' "$PREFS_XML" 2>/dev/null && DNS_REDIRECT=0
                fi

                # Remove any previous DNS chain first so a disabled redirect does not
                # linger from an earlier boot.
                while iptables -t nat -D OUTPUT -p udp --dport 53 -j TAILSOCKS_DNS 2>/dev/null; do :; done
                while iptables -t nat -D OUTPUT -p tcp --dport 53 -j TAILSOCKS_DNS 2>/dev/null; do :; done
                iptables -t nat -F TAILSOCKS_DNS 2>/dev/null || true
                iptables -t nat -X TAILSOCKS_DNS 2>/dev/null || true

                if [ "$DNS_REDIRECT" = "1" ]; then
                    # The tailnet range and the daemon's own upstream resolvers
                    # (TS_DNS_FALLBACK) are excluded: without that, the resolver's own
                    # queries are redirected back into itself and no external name
                    # ever resolves.
                    iptables -t nat -N TAILSOCKS_DNS 2>/dev/null || iptables -t nat -F TAILSOCKS_DNS
                    iptables -t nat -A TAILSOCKS_DNS -d 100.64.0.0/10 -j RETURN
                    OLD_IFS="$IFS"; IFS=","
                    for resolver in $TS_DNS_FALLBACK; do
                        [ -n "$resolver" ] && iptables -t nat -A TAILSOCKS_DNS -d "$resolver" -j RETURN
                    done
                    IFS="$OLD_IFS"
                    iptables -t nat -A TAILSOCKS_DNS -p udp --dport 53 -j DNAT --to-destination 100.100.100.100:53
                    iptables -t nat -A TAILSOCKS_DNS -p tcp --dport 53 -j DNAT --to-destination 100.100.100.100:53
                    iptables -t nat -C OUTPUT -p udp --dport 53 -j TAILSOCKS_DNS 2>/dev/null || iptables -t nat -I OUTPUT 1 -p udp --dport 53 -j TAILSOCKS_DNS
                    iptables -t nat -C OUTPUT -p tcp --dport 53 -j TAILSOCKS_DNS 2>/dev/null || iptables -t nat -I OUTPUT 1 -p tcp --dport 53 -j TAILSOCKS_DNS
                fi

                # --- Exit-node catch-all (same layout as RootUtils; see docs/ROOT.md §4) ---
                # Desktop-Linux rules a pre-4.0 core left behind (a killed daemon never
                # ran Close()). Matched by content so a third-party rule at 52xx survives.
                # Both families are purged here, before the gate: the purge is harmless
                # anywhere and must not wait for it.
                while ip rule del pref 5210 lookup main 2>/dev/null; do :; done
                while ip rule del pref 5230 lookup default 2>/dev/null; do :; done
                while ip rule del pref 5250 type unreachable 2>/dev/null; do :; done
                while ip rule del pref 5270 lookup 52 2>/dev/null; do :; done
                while ip -6 rule del pref 5210 lookup main 2>/dev/null; do :; done
                while ip -6 rule del pref 5230 lookup default 2>/dev/null; do :; done
                while ip -6 rule del pref 5250 type unreachable 2>/dev/null; do :; done
                while ip -6 rule del pref 5270 lookup 52 2>/dev/null; do :; done

                # pref 200 sends every unmarked, locally generated packet through the
                # daemon's table 52 (peer routes, subnet routes, 'default dev tailscale0'
                # while an exit node is selected, LAN throws). That is only safe when
                # THIS daemon marks its own sockets with 0x2000000: an unmarked daemon
                # would route its own WireGuard/DERP packets into tailscale0 the moment
                # table 52 holds a default route. The netns probe logs once per start
                # (with Go's date/time prefix and a component prefix such as
                # 'magicsock:' in front); wait for that exact line in this run's part
                # of the log, else install nothing at pref 200 (tailnet routing via
                # table 1099 does not depend on it). 'TailSocks:' lines are the
                # script's and the app's own and never count.
                DAEMON_MARKS=0
                k=0
                while [ -n "$LOG_START" ] && [ $k -lt 20 ]; do
                    if tail -c +$((LOG_START + 1)) "$LOG_FILE" 2>/dev/null | grep -v 'TailSocks:' | grep -q 'netns: SO_MARK 0x2000000 set on tailscaled sockets'; then
                        DAEMON_MARKS=1
                        break
                    fi
                    sleep 1
                    k=$((k + 1))
                done
                if [ "$DAEMON_MARKS" = "1" ]; then
                    # Mask 0x2020000 = the daemon's bypass bit 25 + Android's
                    # protectedFromVpn bit 17, so sockets Android keeps off VPNs skip
                    # the exit node too (netd's own idiom). 'iif lo' = output lookups
                    # only; tethered clients stay on netd's rules.
                    while ip rule del priority 200 lookup 52 2>/dev/null; do :; done
                    ip rule add fwmark 0x0/0x2020000 iif lo lookup 52 priority 200
                    # A rule that reads back without the mask (bare 'fwmark 0x0' / 'fwmark 0')
                    # has kernel mask 0 and matches everything, the daemon included: remove
                    # it rather than loop. iproute2 4.x prints the zero mark as 0x0, 5.x+ as 0.
                    if ! ip rule show 2>/dev/null | grep -qE '^200:.*fwmark (0x0|0)/0x2020000.*lookup 52'; then
                        while ip rule del priority 200 lookup 52 2>/dev/null; do :; done
                        echo "TailSocks: ip dropped the fwmark mask; exit-node catch-all NOT installed" >> "$LOG_FILE"
                    fi
                    # Replies to the daemon's marked sockets arrive on Wi-Fi/cellular and
                    # are reverse-path checked with mark 0, which now resolves to
                    # tailscale0 through table 52: strict rp_filter (1) would drop them.
                    # The kernel uses max(all, iface), so all=2 is enough. The original
                    # is saved once (temp+mv) for RootUtils.cleanupTailscale0Routing.
                    mkdir -p /data/adb/tailsocks && chmod 700 /data/adb/tailsocks
                    for f in /proc/sys/net/ipv4/conf/*/rp_filter; do
                        if [ "$(cat "$f" 2>/dev/null)" = "1" ]; then
                            [ -f /data/adb/tailsocks/rp_filter.orig ] || { cat /proc/sys/net/ipv4/conf/all/rp_filter > /data/adb/tailsocks/rp_filter.orig.tmp && mv /data/adb/tailsocks/rp_filter.orig.tmp /data/adb/tailsocks/rp_filter.orig; }
                            echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter
                            break
                        fi
                    done
                elif [ -z "$LOG_START" ]; then
                    # Neither token of the gate may appear in these lines: the app
                    # (and this script on a later boot) greps the log for them.
                    echo "TailSocks: exit node unavailable: daemon log size unknown, this run cannot be verified; pref-200 catch-all not installed" >> "$LOG_FILE"
                else
                    echo "TailSocks: exit node unavailable: daemon did not report SO_MARK support within 20s; pref-200 catch-all not installed" >> "$LOG_FILE"
                fi

                # IPv6 exit-node catch-all: only where IPv6 exists and is enabled
                # (else 'ip -6 rule add' fails on every boot and the log line below
                # would be a false alarm). The stale-rule purge already ran above.
                if [ "$DAEMON_MARKS" = "1" ] && [ -d /proc/sys/net/ipv6 ] && [ "$(cat /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null)" = "0" ]; then
                    while ip -6 rule del priority 200 lookup 52 2>/dev/null; do :; done
                    ip -6 rule add fwmark 0x0/0x2020000 iif lo lookup 52 priority 200 2>/dev/null || true
                    if ! ip -6 rule show 2>/dev/null | grep -qE '^200:.*fwmark (0x0|0)/0x2020000.*lookup 52'; then
                        while ip -6 rule del priority 200 lookup 52 2>/dev/null; do :; done
                        echo "TailSocks: IPv6 exit-node catch-all not installed" >> "$LOG_FILE"
                    fi
                fi
                break
            fi
            sleep 1
        done
        break
    fi
    sleep 0.2
done


