#!/bin/bash
set -e

# Check NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "❌ Error: ANDROID_NDK_HOME is not set! Please export it."
    exit 1
fi

# Settings for Android ARM64
export CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
export CGO_ENABLED=1

# Dynamically find the latest stable Tailscale tag
echo "-> Fetching latest stable Tailscale version..."
TS_VERSION=$(git ls-remote --tags --sort="v:refname" https://github.com/tailscale/tailscale.git | grep -v 'pre\|beta\|rc\|{}$' | tail -n1 | sed 's/.*\///')

if [ -z "$TS_VERSION" ]; then
    echo "❌ Error: Could not find latest Tailscale tag. Falling back to v1.78.1"
    TS_VERSION="v1.78.1"
fi
echo "-> Using Tailscale version: $TS_VERSION"

echo "[1/4] Preparing and Patching Tailscale sources..."
if [ ! -d "tailscale_src" ]; then
    echo "-> Downloading sources..."
    curl -sL "https://github.com/tailscale/tailscale/archive/refs/tags/${TS_VERSION}.tar.gz" | tar -xz
    mv tailscale-${TS_VERSION#v} tailscale_src
fi

# Всегда сбрасываем и накатываем патчи заново для чистоты
echo "-> Applying patches..."
cd tailscale_src
# Инициализация гита для безопасного сброса
if [ ! -d ".git" ]; then
    git init -q && git add . && git commit -m "base" -q
fi
git checkout . -q

if [ -f "../patches/tailsocks.patch" ]; then
    patch -p0 -N < ../patches/tailsocks.patch || echo "⚠️ Patch already applied."
fi
cd ..

# Инъекция netmon
cp patches/fix_android_netmon.go tailscale_src/cmd/tailscaled/ 2>/dev/null || true

echo "✅ Sources ready."

echo "[2/4] Compiling binaries in PIE mode..."
cd tailscale_src
mkdir -p tmp

go get github.com/wlynxg/anet@latest
go mod tidy

TAGS="ts_omit_systray,ts_omit_kube,ts_omit_aws,ts_omit_bird,ts_omit_drive,ts_omit_qrcodes,ts_omit_desktop_sessions,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved,ts_omit_sdnotify,ts_omit_tpm,ts_omit_logtail,ts_omit_synology,ts_omit_syspolicy,ts_omit_ssh,ts_omit_iptables,ts_omit_tap,ts_omit_linuxdnsfight,ts_omit_captiveportal,ts_omit_appconnectors,ts_omit_completion,ts_omit_completion_scripts,ts_omit_c2n,ts_omit_oauthkey"

echo "-> Compiling Daemon (Core)..."
GOOS=android GOARCH=arm64 go build \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale.so ./cmd/tailscaled

echo "-> Compiling CLI (Console)..."
GOOS=android GOARCH=arm64 go build \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_cli.so ./cmd/tailscale

cd ..

echo "[3/4] Building appctr.aar (Gomobile Bridge)..."
go mod tidy

mkdir -p tmp

gomobile bind -ldflags="-s -w -buildid= -checklinkname=0 -X appctr.coreVersion=$TS_VERSION" -trimpath -target="android/arm64" -androidapi 21 -tags "$TAGS" -o tmp/appctr.aar -v .

echo "[4/4] Copying binaries to jniLibs..."
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp tailscale_src/tmp/libtailscale.so ../app/src/main/jniLibs/arm64-v8a/
cp tailscale_src/tmp/libtailscale_cli.so ../app/src/main/jniLibs/arm64-v8a/

echo "✅ Done! Ready to assemble APK."
