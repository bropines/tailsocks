#!/bin/bash
set -e

# Change directory to the appctr directory
cd "$(dirname "$0")/.."

# Remove stray patch leftovers before diffing: a failed/fuzzy `patch` run drops
# *.orig and *.rej files under tailscale_src, and the directory diffs (03/07)
# would otherwise sweep them into the generated patches.
find tailscale_src \( -name "*.orig" -o -name "*.rej" \) -delete 2>/dev/null || true

# Find Tailscale version from TAILSCALE_VERSION file or tailscale_src
TS_VERSION=$(cat TAILSCALE_VERSION 2>/dev/null || cat tailscale_src/VERSION.txt 2>/dev/null || echo "v1.98.3")
TS_VERSION="${TS_VERSION#v}"
echo "-> Target Tailscale version: v$TS_VERSION"

# Ensure orig directory exists
if [ ! -d "orig" ]; then
    echo "-> Downloading clean sources to orig/..."
    curl -sL "https://github.com/tailscale/tailscale/archive/refs/tags/v${TS_VERSION}.tar.gz" | tar -xz
    mv tailscale-${TS_VERSION} orig
fi

# Clean old patch files
echo "-> Cleaning old patches..."
rm -f patches/*.patch

# Re-create atomic patches by running diff on modified components
echo "-> Generating atomic patches..."

# 01-enable-socks-android.patch (net/netns/socks.go and netns.go)
{
    diff -u orig/net/netns/socks.go tailscale_src/net/netns/socks.go || true
    diff -u orig/net/netns/netns.go tailscale_src/net/netns/netns.go || true
} > patches/01-enable-socks-android.patch

# 02-socks5-auth.patch (cmd/tailscaled/proxy.go)
diff -u orig/cmd/tailscaled/proxy.go tailscale_src/cmd/tailscaled/proxy.go > patches/02-socks5-auth.patch || true

# 03-taildrop-monolithic-fs.patch (feature/taildrop)
diff -N -r -u -x "*.orig" -x "*.rej" orig/feature/taildrop tailscale_src/feature/taildrop > patches/03-taildrop-monolithic-fs.patch || true

# 04-vip-services.patch (ipn/ipnlocal)
{
    diff -u orig/ipn/ipnlocal/local.go tailscale_src/ipn/ipnlocal/local.go || true
    diff -u orig/ipn/ipnlocal/serve.go tailscale_src/ipn/ipnlocal/serve.go || true
} > patches/04-vip-services.patch

# 05-localapi-cert.patch (ipn/localapi)
{
    diff -u orig/ipn/localapi/cert.go tailscale_src/ipn/localapi/cert.go || true
    diff -u orig/ipn/localapi/disabled_stubs.go tailscale_src/ipn/localapi/disabled_stubs.go || true
} > patches/05-localapi-cert.patch

# 06-android-netmon.patch (creates cmd/tailscaled/fix_android_netmon.go)
diff -N -u /dev/null tailscale_src/cmd/tailscaled/fix_android_netmon.go > patches/06-android-netmon.patch || true

# 07-taildrive-android.patch (drive)
diff -N -r -u -x "*.orig" -x "*.rej" orig/drive tailscale_src/drive > patches/07-taildrive-android.patch || true

# 08-netstack-cgnat.patch (cmd/tailscaled/tailscaled.go)
# NOTE: in Tailscale v1.102.1 the UseNetstackForIP hook moved out of
# cmd/tailscaled/netstack.go into cmd/tailscaled/tailscaled.go. Diffing the old
# file produced an empty patch that `|| true` swallowed, silently dropping the
# TailscaleServiceIP netstack route (Taildrive/WebDAV). Keep this pointed at
# whichever file currently defines the hook.
diff -u orig/cmd/tailscaled/tailscaled.go tailscale_src/cmd/tailscaled/tailscaled.go > patches/08-netstack-cgnat.patch || true

# 09-netstack-loopback.patch (net/tstun/wrap.go and wgengine/netstack/netstack.go)
{
    diff -u orig/net/tstun/wrap.go tailscale_src/net/tstun/wrap.go || true
    diff -u orig/wgengine/netstack/netstack.go tailscale_src/wgengine/netstack/netstack.go || true
} > patches/09-netstack-loopback.patch

# 10-taildrive-userspace-dial.patch (cmd/tailscaled and tests)
{
    diff -u orig/cmd/tailscaled/tailscaled_drive.go tailscale_src/cmd/tailscaled/tailscaled_drive.go || true
    diff -u orig/cmd/tailscaled/tailscaled_windows.go tailscale_src/cmd/tailscaled/tailscaled_windows.go || true
    diff -u orig/ipn/ipnlocal/local_test.go tailscale_src/ipn/ipnlocal/local_test.go || true
} > patches/10-taildrive-userspace-dial.patch

# 11-noop-dns-fallback.patch (net/dns/noop.go)
diff -u orig/net/dns/noop.go tailscale_src/net/dns/noop.go > patches/11-noop-dns-fallback.patch || true

# 12-socket-permissions.patch (safesocket/unixsocket.go)
diff -u orig/safesocket/unixsocket.go tailscale_src/safesocket/unixsocket.go > patches/12-socket-permissions.patch || true

# 13-android-osrouter.patch (wgengine/router/osrouter/router_linux.go, net/netmon/netmon_linux.go, and net/netmon/netmon_polling.go)
{
    diff -u orig/wgengine/router/osrouter/router_linux.go tailscale_src/wgengine/router/osrouter/router_linux.go || true
    diff -u orig/net/netmon/netmon_linux.go tailscale_src/net/netmon/netmon_linux.go || true
    diff -u orig/net/netmon/netmon_polling.go tailscale_src/net/netmon/netmon_polling.go || true
} > patches/13-android-osrouter.patch || true

# 14-dns-forwarder-netstack.patch (net/dns/resolver/forwarder.go)
# Split-DNS resolvers on tailnet IPs are dialled through netstack in
# userspace-networking mode instead of an OS socket the OS cannot route.
diff -u orig/net/dns/resolver/forwarder.go tailscale_src/net/dns/resolver/forwarder.go > patches/14-dns-forwarder-netstack.patch || true

# 15-dnscache-static-hosts.patch (net/dnscache/dnscache.go)
# TS_STATIC_HOSTS lets the control host's pinned resolver answer for the
# control-proxy hostname (the app pre-resolves it); it was set by the app for
# years but nothing in the daemon ever read it.
diff -u orig/net/dnscache/dnscache.go tailscale_src/net/dnscache/dnscache.go > patches/15-dnscache-static-hosts.patch || true

# Guard: a zero-byte patch means a diff target moved or vanished and `|| true`
# swallowed it — exactly how 08-netstack-cgnat was silently lost during the
# v1.102.1 bump. Refuse to finish with any empty patch so it can never ship blank.
empty=""
for p in patches/*.patch; do
    [ -s "$p" ] || empty="$empty $(basename "$p")"
done
if [ -n "$empty" ]; then
    echo "❌ Empty patch(es) generated:$empty" >&2
    echo "   A diff target likely moved. Fix the diff path in this script before committing." >&2
    exit 1
fi

echo "✅ Atomic patches generated successfully in appctr/patches/."




