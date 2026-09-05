#!/system/bin/sh
# TailSocks Tailscale CLI Wrapper
PKG="%PKG_NAME%"
[ ! -d "/data/data/$PKG" ] && PKG="io.github.bropines.tailscaled"
[ ! -d "/data/data/$PKG" ] && PKG="io.github.bropines.tailscaled.dev"

CLI_BIN="%CLI_BIN%"
if [ ! -x "$CLI_BIN" ]; then
    CLI_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/x86_64/libtailscale_cli.so|')"
fi
if [ ! -x "$CLI_BIN" ]; then
    CLI_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/arm64/libtailscale_cli.so|')"
fi
if [ ! -x "$CLI_BIN" ]; then
    CLI_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/arm/libtailscale_cli.so|')"
fi
if [ ! -x "$CLI_BIN" ]; then
    CLI_BIN="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2 | sed 's|base.apk|lib/x86/libtailscale_cli.so|')"
fi
# No filesystem-wide search: `find /data/app -name libtailscale_cli.so | head -n1`
# would happily exec a same-named library shipped by any other installed app
# from a root shell. The binary must come from this package's own install.

SOCKET_PATH="/data/data/$PKG/files/tailscaled.sock"

if [ ! -x "$CLI_BIN" ]; then
    echo "TailSocks CLI binary not found"
    exit 1
fi

if echo "$@" | grep -q -- '--socket='; then
    exec "$CLI_BIN" "$@"
else
    exec "$CLI_BIN" --socket="$SOCKET_PATH" "$@"
fi
