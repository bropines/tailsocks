#!/bin/bash
set -e

# Check NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "❌ Error: ANDROID_NDK_HOME is not set! Please export it."
    exit 1
fi

# Determine Tailscale version from TAILSCALE_VERSION file, env var, or CLI parameter
if [ -n "$1" ] && [[ "$1" != --* ]]; then
    TS_VERSION="$1"
    if [[ "$TS_VERSION" != v* ]]; then
        TS_VERSION="v${TS_VERSION}"
    fi
    echo "$TS_VERSION" > TAILSCALE_VERSION
    echo "-> Updated TAILSCALE_VERSION to: $TS_VERSION"
elif [ -n "$TS_VER_TAG" ]; then
    TS_VERSION="$TS_VER_TAG"
    echo "-> Using provided TS_VER_TAG: $TS_VERSION"
elif [ -f "TAILSCALE_VERSION" ]; then
    TS_VERSION=$(cat TAILSCALE_VERSION | tr -d ' \n\r')
    echo "-> Read Tailscale version from TAILSCALE_VERSION: $TS_VERSION"
else
    TS_VERSION="v1.98.3"
    echo "$TS_VERSION" > TAILSCALE_VERSION
    echo "-> Defaulting Tailscale version to: $TS_VERSION"
fi

# Check if force clean rebuild is requested or if version changed
FORCE_REBUILD=0
if [ "$1" == "--clean" ] || [ "$2" == "--clean" ] || [ "$1" == "--force" ] || [ "$2" == "--force" ]; then
    FORCE_REBUILD=1
elif [ -d "tailscale_src" ]; then
    EXISTING_VER=$(cat tailscale_src/.build_version 2>/dev/null || echo "")
    if [ "$EXISTING_VER" != "$TS_VERSION" ]; then
        echo "-> Tailscale version changed ($EXISTING_VER -> $TS_VERSION). Forcing clean download & patch."
        FORCE_REBUILD=1
    fi
fi

if [ "$FORCE_REBUILD" -eq 1 ]; then
    echo "-> Cleaning old tailscale_src and orig..."
    rm -rf tailscale_src orig
fi

echo "[1/4] Preparing and Patching Tailscale sources (${TS_VERSION})..."
if [ ! -d "tailscale_src" ]; then
    echo "-> Downloading sources for ${TS_VERSION}..."
    curl -sL "https://github.com/tailscale/tailscale/archive/refs/tags/${TS_VERSION}.tar.gz" | tar -xz
    mv tailscale-${TS_VERSION#v} tailscale_src
    echo "$TS_VERSION" > tailscale_src/.build_version

    echo "-> Applying atomic patches..."
    for p in patches/*.patch; do
        if [ -f "$p" ]; then
            echo "Applying patch: $(basename "$p")"
            patch -p1 -d tailscale_src < "$p"
        fi
    done

    echo "✅ Sources patched successfully for ${TS_VERSION}."
else
    echo "-> Sources already exist and patched for ${TS_VERSION}. Skipping download."
fi

echo "[2/4] Compiling binaries in PIE mode..."

cd tailscale_src
mkdir -p tmp

# Enable Go native toolchain and align module language version if needed
export GOTOOLCHAIN=auto
go mod edit -go=1.23 2>/dev/null || true
sed -i '/^tool /d' go.mod 2>/dev/null || true
go mod tidy

TAGS="ts_omit_systray,ts_omit_kube,ts_omit_aws,ts_omit_bird,ts_omit_qrcodes,ts_omit_desktop_sessions,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved,ts_omit_sdnotify,ts_omit_tpm,ts_omit_logtail,ts_omit_synology,ts_omit_syspolicy,ts_omit_ssh,ts_omit_iptables,ts_omit_tap,ts_omit_linuxdnsfight,ts_omit_captiveportal,ts_omit_appconnectors,ts_omit_completion,ts_omit_completion_scripts,ts_omit_oauthkey"

echo "-> Compiling Daemon (Core) [ARM64]..."
export CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
export CGO_ENABLED=1
GOOS=android GOARCH=arm64 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_arm64.so ./cmd/tailscaled

echo "-> Compiling CLI (Console) [ARM64]..."
GOOS=android GOARCH=arm64 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_cli_arm64.so ./cmd/tailscale

echo "-> Compiling Daemon (Core) [ARM 32-bit]..."
export CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang"
export CGO_ENABLED=1
GOOS=android GOARCH=arm GOARM=7 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_arm.so ./cmd/tailscaled

echo "-> Compiling CLI (Console) [ARM 32-bit]..."
GOOS=android GOARCH=arm GOARM=7 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_cli_arm.so ./cmd/tailscale

echo "-> Compiling Daemon (Core) [x86 32-bit]..."
export CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/i686-linux-android21-clang"
export CGO_ENABLED=1
GOOS=android GOARCH=386 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_x86.so ./cmd/tailscaled

echo "-> Compiling CLI (Console) [x86 32-bit]..."
GOOS=android GOARCH=386 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_cli_x86.so ./cmd/tailscale

echo "-> Compiling Daemon (Core) [x86_64 64-bit]..."
export CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android21-clang"
export CGO_ENABLED=1
GOOS=android GOARCH=amd64 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_x86_64.so ./cmd/tailscaled

echo "-> Compiling CLI (Console) [x86_64 64-bit]..."
GOOS=android GOARCH=amd64 go build -v \
    -buildmode=pie \
    -tags "$TAGS" \
    -ldflags="-s -w -checklinkname=0" \
    -o tmp/libtailscale_cli_x86_64.so ./cmd/tailscale

cd ..

GIT_HASH=$(git rev-parse --short=7 HEAD 2>/dev/null || echo "dev")
BUILD_TIME=$(date -u +"%Y-%m-%d_%H%M%S")
FULL_CORE_VER="${TS_VERSION}-${GIT_HASH}-${BUILD_TIME}"

gomobile bind -ldflags="-s -w -buildid= -checklinkname=0 -X appctr.coreVersion=${FULL_CORE_VER}" -trimpath -target="android/arm,android/arm64,android/386,android/amd64" -androidapi 21 -tags "$TAGS" -o tmp/appctr.aar -v .

echo "[4/4] Copying binaries to jniLibs..."
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp tailscale_src/tmp/libtailscale_arm64.so ../app/src/main/jniLibs/arm64-v8a/libtailscale.so
cp tailscale_src/tmp/libtailscale_cli_arm64.so ../app/src/main/jniLibs/arm64-v8a/libtailscale_cli.so

mkdir -p ../app/src/main/jniLibs/armeabi-v7a
cp tailscale_src/tmp/libtailscale_arm.so ../app/src/main/jniLibs/armeabi-v7a/libtailscale.so
cp tailscale_src/tmp/libtailscale_cli_arm.so ../app/src/main/jniLibs/armeabi-v7a/libtailscale_cli.so

mkdir -p ../app/src/main/jniLibs/x86
cp tailscale_src/tmp/libtailscale_x86.so ../app/src/main/jniLibs/x86/libtailscale.so
cp tailscale_src/tmp/libtailscale_cli_x86.so ../app/src/main/jniLibs/x86/libtailscale_cli.so

mkdir -p ../app/src/main/jniLibs/x86_64
cp tailscale_src/tmp/libtailscale_x86_64.so ../app/src/main/jniLibs/x86_64/libtailscale.so
cp tailscale_src/tmp/libtailscale_cli_x86_64.so ../app/src/main/jniLibs/x86_64/libtailscale_cli.so

echo "✅ Done! Ready to assemble APK."
