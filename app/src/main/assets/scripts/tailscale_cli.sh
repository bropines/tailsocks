#!/system/bin/sh
# TailSocks Tailscale CLI Wrapper
PKG="%PKG_NAME%"
[ ! -d "/data/data/$PKG" ] && PKG="io.github.bropines.tailscaled"
[ ! -d "/data/data/$PKG" ] && PKG="io.github.bropines.tailscaled.dev"

# The CLI is the daemon binary itself, libtailscale.so, built with upstream's
# ts_include_cli tag: it acts as the CLI when argv[0] is "tailscale" or when
# TS_BE_CLI is set, and as tailscaled otherwise. A shell cannot choose argv[0]
# portably, so the variable is what makes this a CLI call and not a second
# daemon. (Up to 4.9 the CLI was a separate libtailscale_cli.so.)
CLI_BIN="%CLI_BIN%"
if [ ! -x "$CLI_BIN" ]; then
    APK_PATH="$(pm path $PKG 2>/dev/null | head -n1 | cut -d: -f2)"
    for arch in arm64 arm x86_64 x86; do
        CANDIDATE="$(printf '%s' "$APK_PATH" | sed "s|base.apk|lib/$arch/libtailscale.so|")"
        if [ -n "$APK_PATH" ] && [ -x "$CANDIDATE" ]; then CLI_BIN="$CANDIDATE"; break; fi
    done
fi
# No filesystem-wide search: `find /data/app -name libtailscale.so | head -n1`
# would happily exec a same-named library shipped by any other installed app
# from a root shell. The binary must come from this package's own install.

SOCKET_PATH="/data/data/$PKG/files/tailscaled.sock"

if [ ! -x "$CLI_BIN" ]; then
    echo "TailSocks CLI binary not found"
    exit 1
fi

export TS_BE_CLI=1
if echo "$@" | grep -q -- '--socket='; then
    exec "$CLI_BIN" "$@"
else
    exec "$CLI_BIN" --socket="$SOCKET_PATH" "$@"
fi
