#!/system/bin/sh
# TailSocks Root Mode diagnostic snapshot.
#
# Run under root on the device:
#   su -c sh /data/local/tmp/root-debug.sh
#
# Prints one self-contained report. Nothing is modified.
#
# The interesting part is the packet counters: they answer "is this rule
# actually being hit?", which is what separates "the rule is missing" from
# "the rule is there and the traffic never reaches it".

PKG="io.github.bropines.tailscaled"
[ -n "$1" ] && PKG="$1"
DATA="/data/data/$PKG"
SOCK="$DATA/files/tailscaled.sock"
LOG="$DATA/logs/tailscaled.log"
TABLE=1099
MARK=0x1000000

hr() { echo; echo "=== $1 ==="; }

echo "TailSocks root diagnostics — $(date)"
echo "package: $PKG"

hr "app"
pm path "$PKG" 2>/dev/null | head -1 || echo "(not installed)"
dumpsys package "$PKG" 2>/dev/null | grep -E "versionCode=|versionName=" | head -2

hr "settings"
for k in root_mode_enabled root_tun_enabled root_dns_redirect root_routing_installed \
         lan_access_enabled accept_dns accept_routes socks5 dns_proxy dns_fallbacks; do
    v=$(grep -o "name=\"$k\"[^/]*" "$DATA/shared_prefs/tailsocks_global.xml" 2>/dev/null | head -1)
    [ -n "$v" ] && echo "  $v" || echo "  $k = (unset, using default)"
done

hr "daemon"
PIDS=$(pgrep -f libtailscale.so 2>/dev/null)
if [ -n "$PIDS" ]; then
    for p in $PIDS; do
        echo "  pid $p: $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)"
    done
else
    echo "  not running"
fi
if [ -e "$SOCK" ]; then
    echo "  socket: $(ls -lZ "$SOCK" 2>/dev/null || ls -l "$SOCK")"
else
    echo "  socket: missing ($SOCK)"
fi

hr "selinux"
echo "  mode: $(getenforce 2>/dev/null || echo unknown)"
echo "  this shell: $(tr -d '\0' < /proc/self/attr/current 2>/dev/null || echo unknown)"
for p in $PIDS; do
    echo "  daemon pid $p: $(tr -d '\0' < /proc/$p/attr/current 2>/dev/null || echo unknown)"
done
echo "  recent denials:"
dmesg 2>/dev/null | grep -i 'avc: *denied' | tail -n 10 | sed 's/^/    /' || echo "    none in dmesg"

hr "interface"
ip -br addr show tailscale0 2>/dev/null || echo "  tailscale0 absent"
echo "  other tunnels:"
ip -o link show 2>/dev/null | grep -Eo '(tun[0-9]+|ppp[0-9]+|wg[0-9]+)' | grep -v tailscale0 | sort -u | sed 's/^/    /' || echo "    none"

hr "policy rules"
ip rule list 2>/dev/null | grep -iE "$TABLE|$MARK" || echo "  no v4 rule for table $TABLE"
ip -6 rule list 2>/dev/null | grep -iE "$TABLE|$MARK" || echo "  no v6 rule for table $TABLE"

hr "routing table $TABLE"
ip route show table $TABLE 2>/dev/null || echo "  empty"
ip -6 route show table $TABLE 2>/dev/null | head -5

hr "route decisions"
echo "  100.100.100.100 (MagicDNS):"
ip route get 100.100.100.100 2>&1 | head -2 | sed 's/^/    /'
echo "  100.100.100.100 with our mark (should pick tailscale0):"
ip route get 100.100.100.100 mark $MARK 2>&1 | head -2 | sed 's/^/    /'
echo "  8.8.8.8 (must NOT go through tailscale0 unless an exit node is set):"
ip route get 8.8.8.8 2>&1 | head -2 | sed 's/^/    /'

hr "mangle chain (packet counters)"
iptables -t mangle -L TAILSOCKS_MARK -v -n 2>/dev/null || echo "  TAILSOCKS_MARK absent"
echo "  hook in OUTPUT:"
iptables -t mangle -L OUTPUT -v -n 2>/dev/null | grep -E "TAILSOCKS_MARK|1099" | sed 's/^/    /' || echo "    not hooked"

hr "nat chain (packet counters)"
iptables -t nat -L TAILSOCKS_DNS -v -n 2>/dev/null || echo "  TAILSOCKS_DNS absent"
echo "  hook in OUTPUT:"
iptables -t nat -L OUTPUT -v -n 2>/dev/null | grep -E "TAILSOCKS_DNS|100.100.100.100" | sed 's/^/    /' || echo "    not hooked"

hr "legacy rules (should be empty)"
iptables -t mangle -S OUTPUT 2>/dev/null | grep -- "--set-mark $TABLE" | sed 's/^/  /' || echo "  none"
iptables -t nat -S OUTPUT 2>/dev/null | grep -- "100.100.100.100" | sed 's/^/  /' || echo "  none"

hr "forward"
iptables -S FORWARD 2>/dev/null | grep tailscale0 | sed 's/^/  /' || echo "  none"

hr "hosts"
mount 2>/dev/null | grep /system/etc/hosts | sed 's/^/  /' || echo "  not bind-mounted"
grep -c . /system/etc/hosts 2>/dev/null | sed 's/^/  lines: /'

hr "dns resolution"
# ping resolves through libc, so it exercises the same path an app would take.
# "unknown host" means resolution failed; a reply or "100% packet loss" both mean
# resolution succeeded.
for host in google.com; do
    out=$(ping -c 1 -W 3 "$host" 2>&1 | head -2)
    echo "  $host -> $(echo "$out" | head -1)"
done

hr "daemon log (last 40)"
if [ -f "$LOG" ]; then
    tail -n 40 "$LOG"
else
    echo "  no log at $LOG"
fi

echo
echo "=== end ==="
