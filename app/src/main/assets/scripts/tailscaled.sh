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

# Run marker for the app: RootUtils.daemonMarksSockets bounds "this run" by the
# last such line when TailSocks adopts a daemon this script started (this core
# prints no start banner of its own). Same text as RootUtils.RUN_MARKER; lines
# tagged 'TailSocks:' are never taken as daemon output.
echo "TailSocks: daemon start" >> "$LOG_FILE"

# Run daemon with resolved STATE_DIR (native safesocket patch forces 0666 on tailscaled.sock).
# umask 022: received Taildrop files are created 0666 minus umask, and the app
# must be able to read them while the daemon runs (same line in RootUtils).
umask 022
nohup "$DAEMON_BIN" --statedir="$STATE_DIR" --socket="$SOCKET_PATH" --tun=tailscale0 >> "$LOG_FILE" 2>&1 &
chmod 666 "$LOG_FILE" 2>/dev/null || true
# No SELinux rule is patched in here. Nothing but root talks to the socket at
# boot (the CLI wrapper runs in the same domain as the daemon), and the fixed
# "allow untrusted_app magisk unix_stream_socket connectto" this line used to
# apply changed the policy for every untrusted app on the device, at every boot,
# while naming domains that are wrong on most of them. The app injects the rule
# for the two real domains if — and only if — its own connect is denied
# (RootUtils.allowSocketConnect).
# Wait for daemon socket then apply table 53 routing safely
for i in $(seq 1 30); do
    if [ -S "$SOCKET_PATH" ] || [ -e "$SOCKET_PATH" ]; then
        chmod 777 "$SOCKET_PATH"
        chcon u:object_r:app_data_file:s0 "$SOCKET_PATH" 2>/dev/null || true
        chmod 777 "$STATE_DIR" 2>/dev/null || true

        # Wait for tailscale0 and apply table 53 policy routing.
        # Rules live in dedicated chains so they stay idempotent across reboots
        # and can be removed in one shot; TailSocks uses the same layout.
        #
        # Tailnet reachability only (docs/ROOT.md §4, tier T1). The two
        # device-wide tiers — the pref-200 catch-all into the daemon's table 52
        # and the TAILSOCKS_DNS redirect — are deliberately NOT installed here,
        # because every softener they need is out of reach at late_start: the
        # LAN throw routes and the per-app exclusions need packages resolved to
        # uids (no PackageManager this early), and the decision whether this
        # device is even ours needs another VPN client that has not started yet.
        # Installing the claim without its exemptions is what took the LAN and
        # the user's excluded apps into the exit node after every reboot. The
        # app installs both tiers, with their exemptions, on its first Running
        # tick; until then this device has tailnet reachability and nothing else.
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

                # And the tier every build up to 4.0 wrote into table 1099, by
                # content and never with a flush: netd names its per-network
                # tables after the interface index plus 1000, so on a phone that
                # has cycled enough netdevs table 1099 belongs to a live network.
                # Left behind, the pref-100 rules keep pointing the whole CGNAT
                # range at whatever netd puts there next.
                while ip rule del fwmark 0x1000000/0x1000000 table 1099 2>/dev/null; do :; done
                while ip rule del to 100.64.0.0/10 iif lo table 1099 2>/dev/null; do :; done
                while ip rule del to 100.64.0.0/10 table 1099 2>/dev/null; do :; done
                while ip -6 rule del fwmark 0x1000000/0x1000000 table 1099 2>/dev/null; do :; done
                while ip -6 rule del to fd7a:115c:a1e0::/48 iif lo table 1099 2>/dev/null; do :; done
                while ip -6 rule del to fd7a:115c:a1e0::/48 table 1099 2>/dev/null; do :; done
                ip route del 100.64.0.0/10 dev tailscale0 table 1099 2>/dev/null || true
                ip -6 route del fd7a:115c:a1e0::/48 dev tailscale0 table 1099 2>/dev/null || true

                # Same layout as RootUtils.applyTailscale0Routing, table included:
                # a single high fwmark bit set through a mask so Android's
                # netId/flag bits survive, steering into table 53.
                ip route replace 100.64.0.0/10 dev tailscale0 table 53 metric 1
                ip rule del fwmark 0x1000000/0x1000000 table 53 2>/dev/null || true
                ip rule add fwmark 0x1000000/0x1000000 table 53 priority 100
                # Destination rule too (same as RootUtils): the fwmark is applied
                # after source selection, so root-owned sockets — the daemon's own
                # DNS forwarder — otherwise leave tailscale0 with the Wi-Fi source.
                # `iif lo` for the same reason RootUtils uses it: without it this
                # also claims packets merely passing through the device.
                while ip rule del to 100.64.0.0/10 iif lo table 53 2>/dev/null; do :; done
                while ip rule del to 100.64.0.0/10 table 53 2>/dev/null; do :; done
                ip rule add to 100.64.0.0/10 iif lo table 53 priority 100

                iptables -t mangle -N TAILSOCKS_MARK 2>/dev/null || iptables -t mangle -F TAILSOCKS_MARK
                iptables -t mangle -A TAILSOCKS_MARK -j MARK --set-xmark 0x1000000/0x1000000
                iptables -t mangle -C OUTPUT -d 100.64.0.0/10 -j TAILSOCKS_MARK 2>/dev/null || iptables -t mangle -A OUTPUT -d 100.64.0.0/10 -j TAILSOCKS_MARK

                iptables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -o tailscale0 -j ACCEPT
                iptables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -i tailscale0 -j ACCEPT

                ip -6 route replace fd7a:115c:a1e0::/48 dev tailscale0 table 53 metric 1 2>/dev/null || true
                ip -6 rule del fwmark 0x1000000/0x1000000 table 53 2>/dev/null || true
                ip -6 rule add fwmark 0x1000000/0x1000000 table 53 priority 100 2>/dev/null || true
                while ip -6 rule del to fd7a:115c:a1e0::/48 iif lo table 53 2>/dev/null; do :; done
                while ip -6 rule del to fd7a:115c:a1e0::/48 table 53 2>/dev/null; do :; done
                ip -6 rule add to fd7a:115c:a1e0::/48 iif lo table 53 priority 100 2>/dev/null || true

                ip6tables -t mangle -N TAILSOCKS_MARK 2>/dev/null || ip6tables -t mangle -F TAILSOCKS_MARK
                ip6tables -t mangle -A TAILSOCKS_MARK -j MARK --set-xmark 0x1000000/0x1000000 2>/dev/null || true
                ip6tables -t mangle -C OUTPUT -d fd7a:115c:a1e0::/48 -j TAILSOCKS_MARK 2>/dev/null || ip6tables -t mangle -A OUTPUT -d fd7a:115c:a1e0::/48 -j TAILSOCKS_MARK 2>/dev/null || true

                ip6tables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || true
                ip6tables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || true

                break
            fi
            sleep 1
        done
        break
    fi
    sleep 0.2
done


