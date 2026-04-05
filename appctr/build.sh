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

echo "[2/4] Injecting Android Netmon fix..."
cp patches/fix_android_netmon.go tailscale_src/cmd/tailscaled/

echo "[3/4] Compiling binaries in PIE mode..."
cd tailscale_src

go get github.com/wlynxg/anet@latest
go mod tidy

TAGS="ts_omit_taildrop,ts_omit_systray,ts_omit_kube,ts_omit_aws,ts_omit_bird,ts_omit_drive,ts_omit_qrcodes,ts_omit_desktop_sessions,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved,ts_omit_sdnotify,ts_omit_tpm,ts_omit_logtail,ts_omit_synology,ts_omit_syspolicy,ts_omit_ssh,ts_omit_iptables,ts_omit_tap,ts_omit_linuxdnsfight,ts_omit_captiveportal,ts_omit_appconnectors,ts_omit_completion,ts_omit_completion_scripts,ts_omit_c2n,ts_omit_oauthkey"
echo "-> Compiling Daemon (Core)..."
GOOS=android GOARCH=arm64 go build \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o ../libtailscale.so ./cmd/tailscaled

echo "-> Compiling CLI (Console)..."
GOOS=android GOARCH=arm64 go build \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o ../libtailscale_cli.so ./cmd/tailscale

cd ..

echo "[4/4] Building appctr.aar (Gomobile Bridge)..."
go mod tidy
# Передаем те же теги в gomobile, чтобы обертка соответствовала ядру
gomobile bind -ldflags='-s -w -buildid= -checklinkname=0' -trimpath -target="android/arm64" -androidapi 21 -tags "$TAGS" -o appctr.aar -v .

echo "📦 Copying binaries to jniLibs..."
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp libtailscale.so ../app/src/main/jniLibs/arm64-v8a/
cp libtailscale_cli.so ../app/src/main/jniLibs/arm64-v8a/

echo "✅ Done! Ready to assemble APK."