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

TS_VERSION="main"

echo "[1/4] Preparing Tailscale sources..."
if [ ! -d "tailscale_src" ]; then
    curl -sL "https://github.com/tailscale/tailscale/archive/refs/heads/${TS_VERSION}.tar.gz" | tar -xz
    mv tailscale-${TS_VERSION} tailscale_src
fi

echo "[2/4] Injecting fixes and dummy modules..."
cp patches/fix_android_netmon.go tailscale_src/cmd/tailscaled/
mkdir -p tailscale_src/feature/ssh
echo "package ssh" > tailscale_src/feature/ssh/android_stub.go

echo "[3/4] Compiling binaries in PIE mode..."
cd tailscale_src

# Replace the problematic systray library with our dummy patch
go mod edit -replace fyne.io/systray=../patches/dummy_systray
go get github.com/wlynxg/anet@latest
go mod tidy

echo "-> Compiling Daemon (Core)..."
GOOS=android GOARCH=arm64 go build \
    -buildmode=pie \
    -tags ts_omit_taildrop \
    -ldflags="-s -w -checklinkname=0" \
    -o ../libtailscale.so ./cmd/tailscaled

echo "-> Compiling CLI (Console)..."
GOOS=android GOARCH=arm64 go build \
    -buildmode=pie \
    -tags ts_omit_taildrop \
    -ldflags="-s -w -checklinkname=0" \
    -o ../libtailscale_cli.so ./cmd/tailscale

cd ..

echo "[4/4] Building appctr.aar (Gomobile Bridge)..."
go mod tidy
gomobile bind -ldflags='-s -w -buildid= -checklinkname=0' -trimpath -target="android/arm64" -androidapi 21 -o appctr.aar -v .

echo "📦 Copying binaries to jniLibs..."
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp libtailscale.so ../app/src/main/jniLibs/arm64-v8a/
cp libtailscale_cli.so ../app/src/main/jniLibs/arm64-v8a/

echo "✅ Done! Ready to assemble APK."