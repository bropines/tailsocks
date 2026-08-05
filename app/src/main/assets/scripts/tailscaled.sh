#!/system/bin/sh
# TailSocks Root Autostart Service Script

PKG="io.github.bropines.tailscaled.dev"
[ ! -d "/data/data/$PKG" ] && PKG="io.github.bropines.tailscaled"

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

# Find libtailscale.so binary across architectures
DAEMON_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/x86_64/libtailscale.so|')"
[ ! -x "$DAEMON_BIN" ] && DAEMON_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/arm64/libtailscale.so|')"
[ ! -x "$DAEMON_BIN" ] && DAEMON_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/arm/libtailscale.so|')"
[ ! -x "$DAEMON_BIN" ] && DAEMON_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/x86/libtailscale.so|')"
[ ! -x "$DAEMON_BIN" ] && DAEMON_BIN="$(find /data/app -name "libtailscale.so" 2>/dev/null | head -n1)"

if [ ! -x "$DAEMON_BIN" ]; then
    echo "TailSocks daemon binary not found" >> "$LOG_FILE"
    exit 1
fi

export TS_LOGS_DIR="$LOGS_DIR"
export TS_NO_LOGS_NO_SUPPORT=true
export TS_AUTH_ONCE=true

# Log rotation: if log > 2MB, keep last 500 lines
if [ -f "$LOG_FILE" ]; then
    LOG_SIZE="$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)"
    if [ "$LOG_SIZE" -gt 2097152 ] 2>/dev/null; then
        tail -n 500 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
    fi
fi

# Run daemon with resolved STATE_DIR
nohup "$DAEMON_BIN" --statedir="$STATE_DIR" --socket="$SOCKET_PATH" --tun=tailscale0 >> "$LOG_FILE" 2>&1 &
chmod 666 "$LOG_FILE" 2>/dev/null || true

# Fix socket permissions and SELinux context persistently in background loop
(
    for i in $(seq 1 300); do
        if [ -S "$SOCKET_PATH" ] || [ -e "$SOCKET_PATH" ]; then
            chmod 777 "$SOCKET_PATH" 2>/dev/null
            chmod 777 "$STATE_DIR" 2>/dev/null || true
            chmod 777 "$(dirname "$STATE_DIR")" 2>/dev/null || true
            chcon --reference="$DATA_DIR/files" "$SOCKET_PATH" 2>/dev/null || \
                chcon u:object_r:app_data_file:s0 "$SOCKET_PATH" 2>/dev/null || true
        fi
        sleep 1
    done
) &

