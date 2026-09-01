#!/usr/bin/env bash
# Drives a full Root Mode start/stop cycle on a device over adb and captures a
# snapshot at every phase, so a failure can be located in time rather than
# guessed at afterwards.
#
#   tools/root-debug-session.sh <adb-serial> [package]
#   tools/root-debug-session.sh 192.168.1.83:44895
#
# Produces ./root-debug-<timestamp>/ containing:
#   00-before.txt    state before anything is started
#   01-*.txt         snapshots while the daemon comes up
#   02-after.txt     state once it settled
#   03-after-stop.txt   state after the service is stopped — this is the one
#                       that shows whether rules were left behind
#   logcat.txt       app logcat for the whole session
#   applog.txt       the in-app log buffer (Go core + root daemon)
set -u

SERIAL="${1:?usage: $0 <adb-serial> [package]}"
PKG="${2:-io.github.bropines.tailscaled}"
ADB=(adb -s "$SERIAL")
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="root-debug-$STAMP"
HERE=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$OUT"
echo "-> output: $OUT"

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "!! device $SERIAL is not connected" >&2
    exit 1
fi

echo "-> pushing probe"
"${ADB[@]}" push "$HERE/root-debug.sh" /data/local/tmp/root-debug.sh >/dev/null
"${ADB[@]}" shell chmod 755 /data/local/tmp/root-debug.sh >/dev/null

snap() {
    local name="$1"
    echo "   snapshot: $name"
    "${ADB[@]}" shell "su -c 'sh /data/local/tmp/root-debug.sh $PKG'" 2>&1 | tr -d '\r' > "$OUT/$name.txt"
}

echo "-> clearing logcat"
"${ADB[@]}" logcat -c >/dev/null 2>&1
"${ADB[@]}" logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null' EXIT

snap 00-before

echo "-> starting service"
"${ADB[@]}" shell "am start-foreground-service -n $PKG/.core.TailscaledService -a START_ACTION" >/dev/null 2>&1 \
    || "${ADB[@]}" shell "am startservice -n $PKG/.core.TailscaledService -a START_ACTION" >/dev/null 2>&1

for i in 5 15 30; do
    sleep "$i"
    snap "01-t${i}s"
done

snap 02-after

echo "-> pulling in-app log"
"${ADB[@]}" shell "su -c 'cat /data/data/$PKG/logs/tailscaled.log'" 2>&1 | tr -d '\r' > "$OUT/applog.txt"

echo "-> stopping service"
"${ADB[@]}" shell "am start-foreground-service -n $PKG/.core.TailscaledService -a STOP_ACTION" >/dev/null 2>&1 \
    || "${ADB[@]}" shell "am startservice -n $PKG/.core.TailscaledService -a STOP_ACTION" >/dev/null 2>&1
sleep 12
snap 03-after-stop

kill $LOGCAT_PID 2>/dev/null
trap - EXIT

echo
echo "-> leftovers after stop (should all be clean):"
grep -A 3 -E "^=== (policy rules|routing table|mangle chain|nat chain|hosts)" "$OUT/03-after-stop.txt" \
    | grep -vE "^--$" | sed 's/^/   /'
echo
echo "done: $OUT"
