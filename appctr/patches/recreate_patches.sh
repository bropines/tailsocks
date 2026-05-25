#!/bin/bash
set -e

# Change directory to the appctr directory
cd "$(dirname "$0")/.."

# Find Tailscale version from tailscale_src/VERSION.txt
TS_VERSION=$(cat tailscale_src/VERSION.txt 2>/dev/null || echo "1.98.3")
echo "-> Target Tailscale version: $TS_VERSION"

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

# 01-enable-socks-android.patch (net/netns/socks.go)
diff -u orig/net/netns/socks.go tailscale_src/net/netns/socks.go > patches/01-enable-socks-android.patch || true

# 02-socks5-auth.patch (cmd/tailscaled/proxy.go)
diff -u orig/cmd/tailscaled/proxy.go tailscale_src/cmd/tailscaled/proxy.go > patches/02-socks5-auth.patch || true

# 03-taildrop-monolithic-fs.patch (feature/taildrop)
diff -N -r -u orig/feature/taildrop tailscale_src/feature/taildrop > patches/03-taildrop-monolithic-fs.patch || true

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

echo "✅ Atomic patches generated successfully in appctr/patches/."
