#!/bin/bash
set -e

# Change to the appctr directory where the script is located
cd "$(dirname "$0")"

# Determine Tailscale version (default to 1.98.3 if unknown)
TS_VERSION="1.98.3"
if [ -d "tailscale_src" ] && [ -f "tailscale_src/VERSION.txt" ]; then
    TS_VERSION=$(cat tailscale_src/VERSION.txt)
fi

echo "-> Target Tailscale version: $TS_VERSION"

# Ensure orig directory exists
if [ ! -d "orig" ]; then
    echo "-> Downloading clean sources to orig/..."
    curl -sL "https://github.com/tailscale/tailscale/archive/refs/tags/v${TS_VERSION}.tar.gz" | tar -xz
    mv tailscale-${TS_VERSION} orig
fi

# Recreate tailscale_src from orig
echo "-> Recreating tailscale_src from orig/..."
rm -rf tailscale_src
cp -r orig tailscale_src

# Apply atomic patches
echo "-> Applying atomic patches..."
for p in patches/*.patch; do
    if [ -f "$p" ]; then
        echo "Applying patch: $(basename "$p")"
        patch -p0 -d tailscale_src < "$p"
    fi
done

echo "✅ tailscale_src prepared and patched successfully."
